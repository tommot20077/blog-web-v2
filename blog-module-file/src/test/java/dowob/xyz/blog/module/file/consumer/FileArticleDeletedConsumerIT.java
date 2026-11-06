package dowob.xyz.blog.module.file.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.file.TestFileApplication;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import dowob.xyz.blog.module.file.support.SeaweedFsContainer;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * {@link FileArticleDeletedConsumer} 整合測試（DATA-07）：真實 PostgreSQL ＋ 真實 S3 相容儲存（SeaweedFS），
 * 直接呼叫處理方法（不需要真 RabbitMQ）。
 *
 * <p>驗證刪除文章事件後，綁定該文章、且不被作者其餘文章引用的檔案，其<b>物件（原檔與縮圖）與 metadata 都消失</b>；
 * 仍被其餘文章引用的檔案、頭像、他人檔案與未綁定檔案都不動；同一事件重送冪等；MinIO 失敗不留下「物件在、紀錄沒了」的孤兒。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = TestFileApplication.class)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("FileArticleDeletedConsumer 整合測試")
class FileArticleDeletedConsumerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static com.redis.testcontainers.RedisContainer redis =
            new com.redis.testcontainers.RedisContainer("redis:7-alpine");

    @Container
    static SeaweedFsContainer s3Storage = new SeaweedFsContainer();

    /**
     * 動態注入容器連線設定
     *
     * @param registry Spring 動態屬性源
     */
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("minio.endpoint", s3Storage::getS3Url);
        registry.add("minio.access-key", s3Storage::getAccessKey);
        registry.add("minio.secret-key", s3Storage::getSecretKey);
        registry.add("minio.bucket-name", () -> "blog-files");
    }

    @Autowired private FileArticleDeletedConsumer consumer;
    @Autowired private FileMetadataRepository fileMetadataRepository;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private MinioClient minioClient;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ArticleLookupFacade articleLookupFacade;

    private final UUID author = UUID.randomUUID();
    private final UUID deletedArticle = UUID.randomUUID();
    private final UUID otherArticle = UUID.randomUUID();

    /**
     * 確保測試用 bucket 存在。
     *
     * @param minioClient 連到 SeaweedFS 的 client
     * @throws Exception 建立失敗
     */
    @BeforeAll
    static void setupBucket(@Autowired MinioClient minioClient) throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket("blog-files").build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket("blog-files").build());
        }
    }

    /** 清空測試資料並重置 mock。 */
    @AfterEach
    void cleanUp() {
        fileMetadataRepository.deleteAll();
        jdbc.update("DELETE FROM processed_events WHERE consumer_name = ?", FileArticleDeletedConsumer.CONSUMER_NAME);
        Mockito.reset(articleLookupFacade);
    }

    private static ArticleDeletedEvent eventOf(UUID eventId, UUID articleUuid) {
        return new ArticleDeletedEvent(eventId, articleUuid, List.of(), List.of(), Instant.now(), null, null);
    }

    private void putObject(String path) throws Exception {
        byte[] bytes = "x".getBytes();
        minioClient.putObject(PutObjectArgs.builder().bucket("blog-files").object(path)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1).contentType("image/jpeg").build());
    }

    private boolean objectExists(String path) throws Exception {
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket("blog-files").object(path).build());
            return true;
        } catch (ErrorResponseException e) {
            return false;
        }
    }

    /** 寫入一筆檔案 metadata 並在 S3 放入原檔與縮圖兩個物件，回傳檔案 UUID。 */
    private UUID insertFileWithObjects(UUID uploader, UsageType usageType, UUID articleUuid) throws Exception {
        UUID fileId = UUID.randomUUID();
        String path = pathOf(fileId);
        putObject(path);
        putObject(thumbOf(path));
        FileMetadata metadata = new FileMetadata();
        metadata.setId(fileId);
        metadata.setNewEntity(true);
        metadata.setOriginalName("a.jpg");
        metadata.setStoragePath(path);
        metadata.setContentType("image/jpeg");
        metadata.setSize(100L);
        metadata.setUsageType(usageType);
        metadata.setUploaderId(uploader);
        metadata.setHasThumbnail(true);
        metadata.setCreatedAt(LocalDateTime.now());
        metadata.setArticleUuid(articleUuid);
        fileMetadataRepository.save(metadata);
        return fileId;
    }

    private static String pathOf(UUID fileId) {
        return "articles/2026/01/01/" + fileId + ".jpg";
    }

    private static String thumbOf(String path) {
        return path.replace(".jpg", "_thumb.jpg");
    }

    private boolean metadataExists(UUID fileId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM file_metadata WHERE id = ?", Integer.class, fileId) == 1;
    }

    @Test
    @DisplayName("文章刪除事件：只被該文章綁定的檔案，物件（原檔＋縮圖）與 metadata 都消失；被其他文章引用、頭像、他人、未綁定、綁其他文章的檔案不動")
    void onArticleDeleted_removesObjectsAndMetadataOfUnreferencedFilesOnly() throws Exception {
        UUID orphan = insertFileWithObjects(author, UsageType.ARTICLE_CONTENT, deletedArticle);
        UUID orphanCover = insertFileWithObjects(author, UsageType.ARTICLE_COVER, deletedArticle);
        UUID shared = insertFileWithObjects(author, UsageType.ARTICLE_CONTENT, deletedArticle);
        UUID avatar = insertFileWithObjects(author, UsageType.AVATAR, null);
        UUID unbound = insertFileWithObjects(author, UsageType.ARTICLE_CONTENT, null);
        UUID boundElsewhere = insertFileWithObjects(author, UsageType.ARTICLE_CONTENT, otherArticle);
        UUID bystander = insertFileWithObjects(UUID.randomUUID(), UsageType.ARTICLE_CONTENT, null);
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(author)).thenReturn(Set.of(shared, boundElsewhere));

        consumer.onArticleDeleted(eventOf(UUID.randomUUID(), deletedArticle));

        for (UUID gone : List.of(orphan, orphanCover)) {
            assertThat(metadataExists(gone)).as("metadata %s", gone).isFalse();
            assertThat(objectExists(pathOf(gone))).as("原檔 %s", gone).isFalse();
            assertThat(objectExists(thumbOf(pathOf(gone)))).as("縮圖 %s", gone).isFalse();
        }
        for (UUID kept : List.of(shared, avatar, unbound, boundElsewhere, bystander)) {
            assertThat(metadataExists(kept)).as("metadata %s 應保留", kept).isTrue();
            assertThat(objectExists(pathOf(kept))).as("原檔 %s 應保留", kept).isTrue();
        }
    }

    @Test
    @DisplayName("冪等：同一事件（同 eventId）處理兩次不出錯，processed_events 只有一列，保留的檔案不受影響")
    void onArticleDeleted_sameEventTwice_isIdempotent() throws Exception {
        UUID orphan = insertFileWithObjects(author, UsageType.ARTICLE_CONTENT, deletedArticle);
        UUID shared = insertFileWithObjects(author, UsageType.ARTICLE_CONTENT, deletedArticle);
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(author)).thenReturn(Set.of(shared));
        UUID eventId = UUID.randomUUID();

        consumer.onArticleDeleted(eventOf(eventId, deletedArticle));
        consumer.onArticleDeleted(eventOf(eventId, deletedArticle));

        assertThat(metadataExists(orphan)).isFalse();
        assertThat(metadataExists(shared)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ? AND consumer_name = ?",
                Integer.class, eventId, FileArticleDeletedConsumer.CONSUMER_NAME)).isEqualTo(1);
    }

    @Test
    @DisplayName("冪等：不同 eventId 但檔案已被清掉（重新投遞的補送事件）→ 不出錯、無檔案可刪")
    void onArticleDeleted_alreadyCleaned_secondDeliveryWithNewEventIdIsNoOp() throws Exception {
        UUID orphan = insertFileWithObjects(author, UsageType.ARTICLE_CONTENT, deletedArticle);
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(author)).thenReturn(Set.of());

        consumer.onArticleDeleted(eventOf(UUID.randomUUID(), deletedArticle));
        consumer.onArticleDeleted(eventOf(UUID.randomUUID(), deletedArticle));

        assertThat(metadataExists(orphan)).isFalse();
    }

    @Test
    @DisplayName("fail-safe：查詢其餘文章引用失敗 → 例外往外拋（交 MQ 重試），不刪任何物件或 metadata，也不留下已處理標記")
    void onArticleDeleted_facadeFailure_deletesNothingAndRethrows() throws Exception {
        UUID orphan = insertFileWithObjects(author, UsageType.ARTICLE_CONTENT, deletedArticle);
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(author)).thenThrow(new IllegalStateException("db down"));
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> consumer.onArticleDeleted(eventOf(eventId, deletedArticle)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(metadataExists(orphan)).isTrue();
        assertThat(objectExists(pathOf(orphan))).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId))
                .as("失敗時標記一起 rollback，重試才會真的重做").isZero();
    }

    @Test
    @DisplayName("缺 articleUuid 的舊 payload：不刪除任何檔案，仍正常返回")
    void onArticleDeleted_nullArticleUuid_doesNothing() throws Exception {
        UUID file = insertFileWithObjects(author, UsageType.ARTICLE_CONTENT, deletedArticle);

        consumer.onArticleDeleted(eventOf(UUID.randomUUID(), null));

        assertThat(metadataExists(file)).isTrue();
    }

    @Test
    @DisplayName("部分失敗後重試收斂：A 的 MinIO 刪除成功、B 失敗 → 整批 rollback（A 物件已無但 DB 列還在）；重試時 A 的 removeObject 為 no-op、DB 列被刪，B 也完成，最終一致")
    void onArticleDeleted_partialFailureThenRetry_converges() throws Exception {
        UUID fileA = insertFileWithObjects(author, UsageType.ARTICLE_CONTENT, deletedArticle);
        UUID fileB = insertFileWithObjects(author, UsageType.ARTICLE_CONTENT, deletedArticle);
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(author)).thenReturn(Set.of());
        ArticleDeletedEvent event = eventOf(UUID.randomUUID(), deletedArticle);
        /* 對 B 的原檔刪除失敗；A 的物件不受影響，會被真正刪除 */
        Mockito.doThrow(new RuntimeException("MinIO hiccup")).when(minioClient)
                .removeObject(Mockito.argThat(a -> a != null && a.object().equals(pathOf(fileB))));

        assertThatThrownBy(() -> consumer.onArticleDeleted(event)).isInstanceOf(RuntimeException.class);

        /* 此時最容易出錯的中間態：A 的物件已被刪、DB 列還在（先刪物件後刪 DB 的刻意順序）；B 物件與列都在 */
        assertThat(metadataExists(fileA)).isTrue();
        assertThat(objectExists(pathOf(fileA))).isFalse();
        assertThat(metadataExists(fileB)).isTrue();
        assertThat(objectExists(pathOf(fileB))).isTrue();

        Mockito.reset(minioClient);
        consumer.onArticleDeleted(event);

        assertThat(metadataExists(fileA)).isFalse();
        assertThat(metadataExists(fileB)).isFalse();
        assertThat(objectExists(pathOf(fileA))).isFalse();
        assertThat(objectExists(pathOf(fileB))).isFalse();
    }
}
