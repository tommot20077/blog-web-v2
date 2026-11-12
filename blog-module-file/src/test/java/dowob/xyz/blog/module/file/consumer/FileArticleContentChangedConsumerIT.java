package dowob.xyz.blog.module.file.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleContentChangedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleContentChangedEvent.Action;
import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.file.TestFileApplication;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * {@link FileArticleContentChangedConsumer} 整合測試（真實 PostgreSQL；直接呼叫處理方法，不需要真 RabbitMQ）。
 *
 * <p>驗證 W5A／Q12：文章內容變更事件（SAVED／PUBLISHED／RESTORED）到達後，file 模組依文章最新內文回填
 * {@code file_metadata.article_uuid}；同一 eventId 重送結果一致（{@code processed_events} 去重）；
 * 文章已不存在時略過；失敗時冪等標記一併 rollback，重試會真的重做。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = TestFileApplication.class)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("FileArticleContentChangedConsumer 整合測試")
class FileArticleContentChangedConsumerIT {

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

    @Autowired private FileArticleContentChangedConsumer consumer;
    @Autowired private FileMetadataRepository fileMetadataRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private MinioClient minioClient;
    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ArticleLookupFacade articleLookupFacade;

    private final UUID author = UUID.randomUUID();
    private final UUID articleUuid = UUID.randomUUID();

    /** 清空測試資料並重置 mock。 */
    @AfterEach
    void cleanUp() {
        fileMetadataRepository.deleteAll();
        jdbc.update("DELETE FROM processed_events WHERE consumer_name = ?", FileArticleContentChangedConsumer.CONSUMER_NAME);
        Mockito.reset(articleLookupFacade);
    }

    private static String contentOf(UUID... fileIds) {
        StringBuilder sb = new StringBuilder("# 標題\n");
        for (UUID id : fileIds) {
            sb.append("![圖](/api/v1/files/").append(id).append("/content)\n");
        }
        return sb.toString();
    }

    private static ArticleContentChangedEvent event(UUID eventId, UUID articleUuid, Action action) {
        return new ArticleContentChangedEvent(eventId, articleUuid, UUID.randomUUID(), action, Instant.now());
    }

    /** 文章存在、作者為 {@link #author}、目前內文如參數 */
    private void stubArticle(String content) {
        when(articleLookupFacade.findByUuid(articleUuid))
                .thenReturn(Optional.of(new ArticleData(articleUuid, "DRAFT", null, author, null)));
        when(articleLookupFacade.findContentByUuid(articleUuid)).thenReturn(Optional.of(content));
    }

    /** 寫入一筆「尚未綁定」的檔案（上傳者為 {@link #author}） */
    private UUID insertUnboundFile() {
        return insertFile(author, null);
    }

    private UUID insertFile(UUID uploader, UUID boundArticle) {
        FileMetadata metadata = new FileMetadata();
        UUID fileId = UUID.randomUUID();
        metadata.setId(fileId);
        metadata.setNewEntity(true);
        metadata.setOriginalName("a.jpg");
        metadata.setStoragePath("articles/2026/01/01/" + fileId + ".jpg");
        metadata.setContentType("image/jpeg");
        metadata.setSize(100L);
        metadata.setUsageType(UsageType.ARTICLE_CONTENT);
        metadata.setUploaderId(uploader);
        metadata.setHasThumbnail(false);
        metadata.setCreatedAt(LocalDateTime.now());
        metadata.setArticleUuid(boundArticle);
        fileMetadataRepository.save(metadata);
        return fileId;
    }

    private UUID boundArticleOf(UUID fileId) {
        return jdbc.queryForObject("SELECT article_uuid FROM file_metadata WHERE id = ?", UUID.class, fileId);
    }

    private int processedCount(UUID eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ? AND consumer_name = ?",
                Integer.class, eventId, FileArticleContentChangedConsumer.CONSUMER_NAME);
    }

    @Test
    @DisplayName("縱深防護：bindFilesToArticle 的 SQL 帶 uploader_id 條件——即使 service 判斷後 uploader 被換成別人（等同傳入的 ids 含他人檔案），真 SQL 也綁不到")
    void bindFilesToArticle_sqlRequiresMatchingUploader() {
        UUID mine = insertUnboundFile();
        UUID someoneElsesNow = insertFile(UUID.randomUUID(), null);

        int updated = fileMetadataRepository.bindFilesToArticle(articleUuid, author, List.of(mine, someoneElsesNow));

        assertThat(updated).isEqualTo(1);
        assertThat(boundArticleOf(mine)).isEqualTo(articleUuid);
        assertThat(boundArticleOf(someoneElsesNow)).as("uploader 不符的檔案不得被綁定").isNull();
    }

    @Test
    @DisplayName("SAVED：內文引用的未綁定檔案被回填為該文章；未被引用的不動")
    void onContentChanged_saved_backfillsReferencedFiles() {
        UUID referenced = insertUnboundFile();
        UUID unreferenced = insertUnboundFile();
        stubArticle(contentOf(referenced));

        consumer.onContentChanged(event(UUID.randomUUID(), articleUuid, Action.SAVED));

        assertThat(boundArticleOf(referenced)).isEqualTo(articleUuid);
        assertThat(boundArticleOf(unreferenced)).isNull();
    }

    @Test
    @DisplayName("RESTORED：還原後內文改引用另一張圖 → 新圖綁定、舊圖解除（完整替換，避免權限殘留）")
    void onContentChanged_restored_replacesBindings() {
        UUID oldFile = insertFile(author, articleUuid);
        UUID restoredFile = insertUnboundFile();
        stubArticle(contentOf(restoredFile));

        consumer.onContentChanged(event(UUID.randomUUID(), articleUuid, Action.RESTORED));

        assertThat(boundArticleOf(restoredFile)).isEqualTo(articleUuid);
        assertThat(boundArticleOf(oldFile)).isNull();
    }

    @Test
    @DisplayName("PUBLISHED：同樣依最新內文回填（內容不變時結果不變）")
    void onContentChanged_published_isStable() {
        UUID file = insertFile(author, articleUuid);
        stubArticle(contentOf(file));

        consumer.onContentChanged(event(UUID.randomUUID(), articleUuid, Action.PUBLISHED));

        assertThat(boundArticleOf(file)).isEqualTo(articleUuid);
    }

    @Test
    @DisplayName("同一事件（同 eventId）重送：結果一致，processed_events 只有一列，第二次不再讀取內文（真的被去重）")
    void onContentChanged_sameEventTwice_isIdempotent() {
        UUID file = insertUnboundFile();
        stubArticle(contentOf(file));
        UUID eventId = UUID.randomUUID();
        ArticleContentChangedEvent event = event(eventId, articleUuid, Action.SAVED);

        consumer.onContentChanged(event);
        /* 若第二次真的重做，內文已變成不含此圖，綁定會被解除；去重生效則綁定維持 */
        when(articleLookupFacade.findContentByUuid(articleUuid)).thenReturn(Optional.of("無圖"));
        consumer.onContentChanged(event);

        assertThat(boundArticleOf(file)).as("重複事件被跳過，綁定維持第一次的結果").isEqualTo(articleUuid);
        assertThat(processedCount(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("不同 eventId、同一內文重送：結果與第一次一致（回填本身冪等）")
    void onContentChanged_differentEventSameContent_sameResult() {
        UUID file = insertUnboundFile();
        stubArticle(contentOf(file));

        consumer.onContentChanged(event(UUID.randomUUID(), articleUuid, Action.SAVED));
        consumer.onContentChanged(event(UUID.randomUUID(), articleUuid, Action.SAVED));

        assertThat(boundArticleOf(file)).isEqualTo(articleUuid);
    }

    @Test
    @DisplayName("文章已不存在：略過，不丟例外、不動既有綁定")
    void onContentChanged_articleMissing_skips() {
        UUID file = insertFile(author, articleUuid);
        when(articleLookupFacade.findContentByUuid(articleUuid)).thenReturn(Optional.empty());

        consumer.onContentChanged(event(UUID.randomUUID(), articleUuid, Action.SAVED));

        assertThat(boundArticleOf(file)).isEqualTo(articleUuid);
    }

    @Test
    @DisplayName("內文引用他人上傳的檔案：不綁定（擁有權不變量由 bindToArticle 守住）")
    void onContentChanged_foreignFile_notBound() {
        UUID foreign = insertFile(UUID.randomUUID(), null);
        stubArticle(contentOf(foreign));

        consumer.onContentChanged(event(UUID.randomUUID(), articleUuid, Action.SAVED));

        assertThat(boundArticleOf(foreign)).isNull();
    }

    @Test
    @DisplayName("失敗後重試：標記隨交易 rollback（不被誤判為已處理），第二次真的完成綁定")
    void onContentChanged_failureThenRetry_redoesWork() {
        UUID file = insertUnboundFile();
        UUID eventId = UUID.randomUUID();
        ArticleContentChangedEvent event = event(eventId, articleUuid, Action.SAVED);
        when(articleLookupFacade.findContentByUuid(articleUuid)).thenThrow(new IllegalStateException("article db down"));

        assertThatThrownBy(() -> consumer.onContentChanged(event)).isInstanceOf(IllegalStateException.class);
        assertThat(processedCount(eventId)).as("失敗不留下已處理標記").isZero();
        assertThat(boundArticleOf(file)).isNull();

        Mockito.reset(articleLookupFacade);
        stubArticle(contentOf(file));
        consumer.onContentChanged(event);

        assertThat(boundArticleOf(file)).isEqualTo(articleUuid);
        assertThat(processedCount(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("缺 articleUuid 的 payload：正常返回、不動任何檔案")
    void onContentChanged_nullArticleUuid_noop() {
        UUID file = insertUnboundFile();

        consumer.onContentChanged(event(UUID.randomUUID(), null, Action.SAVED));

        assertThat(boundArticleOf(file)).isNull();
    }
}
