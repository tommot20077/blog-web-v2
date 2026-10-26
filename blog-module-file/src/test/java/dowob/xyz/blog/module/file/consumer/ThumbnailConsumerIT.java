package dowob.xyz.blog.module.file.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.file.TestFileApplication;
import dowob.xyz.blog.module.file.event.ImageUploadedEvent;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ThumbnailConsumer} 整合測試（真實 PostgreSQL、模擬 MinIO）
 *
 * <p>縮圖處理耗時（下載、縮圖、上傳）。這段期間其他流程可能改動同一筆 {@code file_metadata}：
 * {@code bindToArticle} 寫入 {@code article_uuid}，或檔案被刪除。consumer 最後寫旗標時，
 * 不得蓋掉別人寫入的欄位，也不得因檔案已被刪除而失敗。這裡以 MinIO {@code putObject} 當 hook，
 * 在「縮圖處理期間」插入並行寫入，重現這個時間窗。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = TestFileApplication.class)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ThumbnailConsumer 整合測試")
class ThumbnailConsumerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static com.redis.testcontainers.RedisContainer redis =
            new com.redis.testcontainers.RedisContainer("redis:7-alpine");

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

    @Autowired
    private ThumbnailConsumer thumbnailConsumer;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 以 mock 取代真實 MinIO：下載回傳測試 JPEG，上傳由各測試決定行為（hook） */
    @MockitoBean
    private MinioClient minioClient;

    @MockitoBean
    private ConnectionFactory connectionFactory;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private UserAuthService userAuthService;

    @MockitoBean
    private ArticleLookupFacade articleLookupFacade;

    @AfterEach
    void cleanUp() {
        fileMetadataRepository.deleteAll();
    }

    /** 寫入一筆尚未產生縮圖、尚未綁定文章的檔案 */
    private UUID insertUnboundFile() {
        FileMetadata metadata = new FileMetadata();
        UUID fileId = UUID.randomUUID();
        metadata.setId(fileId);
        metadata.setNewEntity(true);
        metadata.setOriginalName("a.jpg");
        metadata.setStoragePath("articles/2026/01/01/" + fileId + ".jpg");
        metadata.setContentType("image/jpeg");
        metadata.setSize(100L);
        metadata.setUsageType(UsageType.ARTICLE_CONTENT);
        metadata.setUploaderId(UUID.randomUUID());
        metadata.setHasThumbnail(false);
        metadata.setCreatedAt(LocalDateTime.now());
        fileMetadataRepository.save(metadata);
        return fileId;
    }

    /** 讓 MinIO 下載回傳測試用 JPEG */
    private void stubDownload() throws Exception {
        byte[] jpegBytes = getClass().getResourceAsStream("/test.jpg").readAllBytes();
        when(minioClient.getObject(any(GetObjectArgs.class))).thenAnswer(inv -> jpegResponse(jpegBytes));
    }

    private static GetObjectResponse jpegResponse(byte[] bytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        GetObjectResponse response = mock(GetObjectResponse.class);
        when(response.read(any(byte[].class), any(int.class), any(int.class)))
                .thenAnswer(inv -> bais.read(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(response.read()).thenAnswer(inv -> bais.read());
        return response;
    }

    private ImageUploadedEvent eventOf(UUID fileId) {
        return new ImageUploadedEvent(UUID.randomUUID(), fileId, "articles/2026/01/01/" + fileId + ".jpg", "image/jpeg");
    }

    private UUID articleUuidOf(UUID fileId) {
        return jdbcTemplate.queryForObject(
                "SELECT article_uuid FROM file_metadata WHERE id = ?", UUID.class, fileId);
    }

    private boolean hasThumbnailOf(UUID fileId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT has_thumbnail FROM file_metadata WHERE id = ?", Boolean.class, fileId));
    }

    /** 縮圖處理期間 article_uuid 被 bindToArticle 寫入 → 處理完後仍是新值，且旗標為 true */
    @Test
    @DisplayName("handleImageUploaded_whenArticleBoundDuringProcessing_keepsNewArticleUuid")
    void handleImageUploaded_whenArticleBoundDuringProcessing_keepsNewArticleUuid() throws Exception {
        UUID fileId = insertUnboundFile();
        UUID articleUuid = UUID.randomUUID();
        stubDownload();
        when(minioClient.putObject(any(PutObjectArgs.class))).thenAnswer(inv -> {
            /* 模擬縮圖處理期間 bindToArticle 寫入綁定 */
            jdbcTemplate.update("UPDATE file_metadata SET article_uuid = ? WHERE id = ?", articleUuid, fileId);
            return null;
        });
        Channel channel = mock(Channel.class);

        thumbnailConsumer.handleImageUploaded(eventOf(fileId), channel, 1L);

        assertThat(articleUuidOf(fileId))
                .as("consumer 寫旗標不得把處理期間寫入的 article_uuid 蓋回舊值 null")
                .isEqualTo(articleUuid);
        assertThat(hasThumbnailOf(fileId)).isTrue();
        verify(channel).basicAck(1L, false);
    }

    /** 縮圖處理期間檔案被刪除 → 正常 ack，不拋例外、不 nack */
    @Test
    @DisplayName("handleImageUploaded_whenFileDeletedDuringProcessing_acksWithoutError")
    void handleImageUploaded_whenFileDeletedDuringProcessing_acksWithoutError() throws Exception {
        UUID fileId = insertUnboundFile();
        stubDownload();
        when(minioClient.putObject(any(PutObjectArgs.class))).thenAnswer(inv -> {
            /* 模擬縮圖處理期間檔案被刪除 */
            jdbcTemplate.update("DELETE FROM file_metadata WHERE id = ?", fileId);
            return null;
        });
        Channel channel = mock(Channel.class);

        thumbnailConsumer.handleImageUploaded(eventOf(fileId), channel, 2L);

        verify(channel).basicAck(2L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        assertThat(fileMetadataRepository.findById(fileId)).isEmpty();
    }
}
