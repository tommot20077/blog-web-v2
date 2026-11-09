package dowob.xyz.blog.module.file.consumer;

import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.file.TestFileApplication;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FileUserDeletedConsumer} 整合測試（真實 PostgreSQL、模擬 MinIO；直接呼叫處理方法，不需要真 RabbitMQ）。
 *
 * <p>驗證 DATA-02／DATA-07 的 file 面：使用者刪帳後，其上傳、且未被任何保留文章引用的檔案
 * （metadata 列與 MinIO 物件）被清除；仍被已發布／已下架文章引用的檔案保留，他人的檔案不動。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = TestFileApplication.class)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("FileUserDeletedConsumer 整合測試")
class FileUserDeletedConsumerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Container
    static com.redis.testcontainers.RedisContainer redis =
            new com.redis.testcontainers.RedisContainer("redis:8.8.0-alpine");

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
    }

    @Autowired private FileUserDeletedConsumer consumer;
    @Autowired private FileMetadataRepository fileMetadataRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private MinioClient minioClient;
    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ArticleLookupFacade articleLookupFacade;

    private final UUID deletedUser = UUID.randomUUID();
    private final UUID bystander = UUID.randomUUID();

    /** 清空測試資料並重置 mock 互動紀錄。 */
    @AfterEach
    void cleanUp() {
        fileMetadataRepository.deleteAll();
        Mockito.reset(minioClient, articleLookupFacade);
    }

    private static UserDeletedEvent eventOf(UUID userUuid) {
        return new UserDeletedEvent(UUID.randomUUID(), userUuid, Instant.now());
    }

    /**
     * 寫入一筆檔案 metadata。
     *
     * @param uploader    上傳者
     * @param usageType   用途
     * @param articleUuid 綁定的文章；null 表示未綁定
     * @return 檔案 UUID
     */
    private UUID insertFile(UUID uploader, UsageType usageType, UUID articleUuid) {
        FileMetadata metadata = new FileMetadata();
        UUID fileId = UUID.randomUUID();
        metadata.setId(fileId);
        metadata.setNewEntity(true);
        metadata.setOriginalName("a.jpg");
        metadata.setStoragePath("articles/2026/01/01/" + fileId + ".jpg");
        metadata.setContentType("image/jpeg");
        metadata.setSize(100L);
        metadata.setUsageType(usageType);
        metadata.setUploaderId(uploader);
        metadata.setHasThumbnail(false);
        metadata.setCreatedAt(LocalDateTime.now());
        metadata.setArticleUuid(articleUuid);
        fileMetadataRepository.save(metadata);
        return fileId;
    }

    private boolean exists(UUID fileId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM file_metadata WHERE id = ?", Integer.class, fileId) == 1;
    }

    private void stubArticle(UUID articleUuid, String status) {
        when(articleLookupFacade.findByUuid(articleUuid))
                .thenReturn(Optional.of(new ArticleData(articleUuid, status, null, deletedUser, null)));
    }

    @Test
    @DisplayName("處理事件：未綁定、綁定草稿、頭像的檔案被刪除（含 MinIO 物件）；綁定已發布／已下架文章的檔案與他人的檔案保留")
    void onUserDeleted_removesUnreferencedFilesKeepsRetainedAndOthers() throws Exception {
        UUID published = UUID.randomUUID();
        UUID archived = UUID.randomUUID();
        UUID draft = UUID.randomUUID();
        stubArticle(published, "PUBLISHED");
        stubArticle(archived, "ARCHIVED");
        stubArticle(draft, "DRAFT");
        UUID unbound = insertFile(deletedUser, UsageType.ARTICLE_CONTENT, null);
        UUID avatar = insertFile(deletedUser, UsageType.AVATAR, null);
        UUID boundToDraft = insertFile(deletedUser, UsageType.ARTICLE_CONTENT, draft);
        UUID boundToPublished = insertFile(deletedUser, UsageType.ARTICLE_CONTENT, published);
        UUID boundToArchived = insertFile(deletedUser, UsageType.ARTICLE_COVER, archived);
        UUID others = insertFile(bystander, UsageType.ARTICLE_CONTENT, null);

        consumer.onUserDeleted(eventOf(deletedUser));

        assertThat(exists(unbound)).isFalse();
        assertThat(exists(avatar)).isFalse();
        assertThat(exists(boundToDraft)).isFalse();
        assertThat(exists(boundToPublished)).as("仍被已發布文章引用").isTrue();
        assertThat(exists(boundToArchived)).as("仍被已下架（保留）文章引用").isTrue();
        assertThat(exists(others)).as("他人的檔案不動").isTrue();
        ArgumentCaptor<RemoveObjectArgs> removed = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        /* 3 個檔案 × (原檔 + 縮圖路徑)：deleteFile 現在一律嘗試刪縮圖路徑（RACE-16／DATA-14），不看 hasThumbnail */
        verify(minioClient, Mockito.times(6)).removeObject(removed.capture());
        assertThat(removed.getAllValues()).extracting(RemoveObjectArgs::object)
                .anyMatch(path -> path.contains(unbound.toString()))
                .anyMatch(path -> path.contains(avatar.toString()))
                .anyMatch(path -> path.contains(boundToDraft.toString()))
                .filteredOn(path -> path.contains("_thumb")).hasSize(3);
    }

    @Test
    @DisplayName("MinIO 刪除失敗：先刪物件後刪 DB，故 metadata 保留（物件還在、紀錄還在），例外往外拋由容器重試")
    void onUserDeleted_minioFailure_keepsMetadataAndRethrows() throws Exception {
        UUID unbound = insertFile(deletedUser, UsageType.ARTICLE_CONTENT, null);
        Mockito.doThrow(new RuntimeException("MinIO down")).when(minioClient).removeObject(any(RemoveObjectArgs.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.onUserDeleted(eventOf(deletedUser)))
                .isInstanceOf(RuntimeException.class);

        assertThat(exists(unbound)).isTrue();
    }

    @Test
    @DisplayName("同一事件處理兩次：不出錯，第二次為 no-op，保留的檔案不受影響")
    void onUserDeleted_sameEventTwice_isIdempotent() throws Exception {
        UUID published = UUID.randomUUID();
        stubArticle(published, "PUBLISHED");
        UUID unbound = insertFile(deletedUser, UsageType.ARTICLE_CONTENT, null);
        UUID kept = insertFile(deletedUser, UsageType.ARTICLE_CONTENT, published);
        UserDeletedEvent event = eventOf(deletedUser);

        consumer.onUserDeleted(event);
        consumer.onUserDeleted(event);

        assertThat(exists(unbound)).isFalse();
        assertThat(exists(kept)).isTrue();
    }

    @Test
    @DisplayName("缺 userUuid 的舊 payload：不刪除任何檔案，仍正常返回")
    void onUserDeleted_nullUserUuid_removesNothing() throws Exception {
        UUID unbound = insertFile(deletedUser, UsageType.ARTICLE_CONTENT, null);

        consumer.onUserDeleted(eventOf(null));

        assertThat(exists(unbound)).isTrue();
        assertThat(List.of(unbound)).hasSize(1);
    }
}
