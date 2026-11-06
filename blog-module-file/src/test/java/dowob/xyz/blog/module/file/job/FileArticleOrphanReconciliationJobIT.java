package dowob.xyz.blog.module.file.job;

import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob;
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
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FileArticleOrphanReconciliationJob} 整合測試（DATA-07 第二道保險）：真實 PostgreSQL ＋ Redis（分散式鎖與游標），
 * MinIO 以 mock 取代（物件刪除的真實行為由 {@code FileArticleDeletedConsumerIT} 以 SeaweedFS 驗證）。
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = TestFileApplication.class)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("FileArticleOrphanReconciliationJob 整合測試")
class FileArticleOrphanReconciliationJobIT {

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

    @Autowired private FileArticleOrphanReconciliationJob job;
    @Autowired private FileMetadataRepository fileMetadataRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate stringRedis;

    @MockitoBean private MinioClient minioClient;
    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ArticleLookupFacade articleLookupFacade;

    private final UUID author = UUID.randomUUID();

    /** 清空資料、鎖與游標，重置 mock。 */
    @AfterEach
    void cleanUp() {
        fileMetadataRepository.deleteAll();
        var keys = stringRedis.keys("*reconcil*");
        if (keys != null && !keys.isEmpty()) {
            stringRedis.delete(keys);
        }
        Mockito.reset(articleLookupFacade, minioClient);
    }

    private UUID insertFile(UsageType usageType, UUID articleUuid) {
        FileMetadata metadata = new FileMetadata();
        UUID fileId = UUID.randomUUID();
        metadata.setId(fileId);
        metadata.setNewEntity(true);
        metadata.setOriginalName("a.jpg");
        metadata.setStoragePath("articles/2026/01/01/" + fileId + ".jpg");
        metadata.setContentType("image/jpeg");
        metadata.setSize(100L);
        metadata.setUsageType(usageType);
        metadata.setUploaderId(author);
        metadata.setHasThumbnail(false);
        metadata.setCreatedAt(LocalDateTime.now());
        metadata.setArticleUuid(articleUuid);
        fileMetadataRepository.save(metadata);
        return fileId;
    }

    private boolean exists(UUID fileId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM file_metadata WHERE id = ?", Integer.class, fileId) == 1;
    }

    /** 讓 facade 的存在性查詢回傳「輸入 ∩ existing」，其餘文章視為已刪除。 */
    private void stubExistingArticles(Set<UUID> existing) {
        when(articleLookupFacade.filterExistingUuids(any())).thenAnswer(inv -> {
            Collection<UUID> input = inv.getArgument(0);
            Set<UUID> result = new HashSet<>(input);
            result.retainAll(existing);
            return result;
        });
    }

    @Test
    @DisplayName("文章已不存在：清理其綁定檔案（與事件路徑相同的去留邏輯）；仍存在文章的檔案、被其餘文章引用者、頭像、未綁定檔案都不動")
    void run_cleansFilesOfDeletedArticlesOnly() throws Exception {
        UUID liveA = UUID.randomUUID();
        UUID liveB = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        UUID keptLiveA = insertFile(UsageType.ARTICLE_CONTENT, liveA);
        UUID keptLiveB = insertFile(UsageType.ARTICLE_CONTENT, liveB);
        UUID orphan = insertFile(UsageType.ARTICLE_CONTENT, gone);
        UUID orphanCover = insertFile(UsageType.ARTICLE_COVER, gone);
        UUID sharedWithLiveArticle = insertFile(UsageType.ARTICLE_CONTENT, gone);
        UUID avatar = insertFile(UsageType.AVATAR, null);
        UUID unbound = insertFile(UsageType.ARTICLE_CONTENT, null);
        stubExistingArticles(Set.of(liveA, liveB));
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(author)).thenReturn(Set.of(sharedWithLiveArticle));

        OrphanReconciliationJob.Result result = job.run();

        assertThat(result).isEqualTo(OrphanReconciliationJob.Result.COMPLETED);
        assertThat(exists(orphan)).isFalse();
        assertThat(exists(orphanCover)).isFalse();
        for (UUID kept : List.of(keptLiveA, keptLiveB, sharedWithLiveArticle, avatar, unbound)) {
            assertThat(exists(kept)).as("應保留 %s", kept).isTrue();
        }
        verify(minioClient, Mockito.times(4)).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    @DisplayName("fail-safe：owner facade 拋例外 → 整次 FAILED，零修改（不刪任何檔案或物件）")
    void run_facadeThrows_deletesNothing() throws Exception {
        UUID liveA = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        UUID f1 = insertFile(UsageType.ARTICLE_CONTENT, liveA);
        UUID f2 = insertFile(UsageType.ARTICLE_CONTENT, gone);
        when(articleLookupFacade.filterExistingUuids(any())).thenThrow(new IllegalStateException("article db down"));

        OrphanReconciliationJob.Result result = job.run();

        assertThat(result).isEqualTo(OrphanReconciliationJob.Result.FAILED);
        assertThat(exists(f1)).isTrue();
        assertThat(exists(f2)).isTrue();
        Mockito.verifyNoInteractions(minioClient);
    }

    @Test
    @DisplayName("重跑冪等：第二次執行沒有孤兒可處理，不出錯")
    void run_twice_isIdempotent() throws Exception {
        UUID liveA = UUID.randomUUID();
        UUID liveB = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        insertFile(UsageType.ARTICLE_CONTENT, liveA);
        insertFile(UsageType.ARTICLE_CONTENT, liveB);
        UUID orphan = insertFile(UsageType.ARTICLE_CONTENT, gone);
        stubExistingArticles(Set.of(liveA, liveB));
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(author)).thenReturn(Set.of());

        assertThat(job.run()).isEqualTo(OrphanReconciliationJob.Result.COMPLETED);
        assertThat(job.run()).isEqualTo(OrphanReconciliationJob.Result.COMPLETED);

        assertThat(exists(orphan)).isFalse();
    }
}
