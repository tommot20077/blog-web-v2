package dowob.xyz.blog.module.file.job;

import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
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
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * {@link FileBindingReconcileJob} 整合測試（B1 補綁定＋D6 未綁定檔清理）：真實 PostgreSQL、Redis（鎖與游標）與
 * 真實 S3 相容儲存（SeaweedFS）；article 模組的 facade 以 mock 取代（其 SQL 由 article 模組的 IT 驗證）。
 *
 * <p>驗證端到端結果：8 天前、未綁定、未被引用的文章圖被刪除（metadata 與物件）；同條件但被<b>草稿</b>引用的檔案
 * 先被補綁定、沒有被刪；頭像、未滿 7 天、只被封面引用的檔案不動；facade 失敗時零刪除。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = TestFileApplication.class)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("FileBindingReconcileJob 整合測試")
class FileBindingReconcileJobIT {

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

    @Autowired private FileBindingReconcileJob job;
    @Autowired private FileMetadataRepository fileMetadataRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate stringRedis;
    @MockitoSpyBean private MinioClient minioClient;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ArticleLookupFacade articleLookupFacade;

    private final UUID author = UUID.randomUUID();
    private final UUID draftArticle = UUID.randomUUID();

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

    private static String pathOf(UUID fileId) {
        return "articles/2026/01/01/" + fileId + ".jpg";
    }

    /** 寫入一筆檔案 metadata（建立於 {@code ageDays} 天前）並在 S3 放入原檔物件，回傳檔案 UUID。 */
    private UUID insertFile(UsageType usageType, int ageDays) throws Exception {
        UUID fileId = UUID.randomUUID();
        putObject(pathOf(fileId));
        FileMetadata metadata = new FileMetadata();
        metadata.setId(fileId);
        metadata.setNewEntity(true);
        metadata.setOriginalName("a.jpg");
        metadata.setStoragePath(pathOf(fileId));
        metadata.setContentType("image/jpeg");
        metadata.setSize(100L);
        metadata.setUsageType(usageType);
        metadata.setUploaderId(author);
        metadata.setHasThumbnail(false);
        metadata.setCreatedAt(LocalDateTime.now().minusDays(ageDays));
        metadata.setArticleUuid(null);
        fileMetadataRepository.save(metadata);
        return fileId;
    }

    private boolean metadataExists(UUID fileId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM file_metadata WHERE id = ?", Integer.class, fileId) == 1;
    }

    private UUID boundArticleOf(UUID fileId) {
        return jdbc.queryForObject("SELECT article_uuid FROM file_metadata WHERE id = ?", UUID.class, fileId);
    }

    /** 模擬 article 模組：近期更新的文章只有一篇草稿，內文引用 {@code referencedFiles}；作者名下所有文章引用 {@code allReferenced}。 */
    private void stubDraftReferencing(List<UUID> referencedFiles, Set<UUID> allReferenced) {
        when(articleLookupFacade.findUuidsUpdatedSinceAnyStatus(any(), any(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(1) == null ? List.of(draftArticle) : List.of());
        when(articleLookupFacade.findContentByUuid(draftArticle)).thenReturn(Optional.of(
                referencedFiles.stream().map(id -> "![](/api/v1/files/" + id + "/content)")
                        .reduce("草稿內文\n", (a, b) -> a + b + "\n")));
        when(articleLookupFacade.findByUuid(draftArticle))
                .thenReturn(Optional.of(new ArticleData(draftArticle, "DRAFT", null, author, null)));
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(author)).thenReturn(allReferenced);
    }

    @Test
    @DisplayName("8 天前、未綁定、未被引用的文章圖 → 被刪除（metadata 與物件都消失）")
    void run_deletesOldUnboundUnreferencedArticleImage() throws Exception {
        UUID orphan = insertFile(UsageType.ARTICLE_CONTENT, 8);
        when(articleLookupFacade.findUuidsUpdatedSinceAnyStatus(any(), any(), anyInt())).thenReturn(List.of());
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(author)).thenReturn(Set.of());

        FileBindingReconcileJob.Result result = job.run();

        assertThat(result).isEqualTo(FileBindingReconcileJob.Result.COMPLETED);
        assertThat(metadataExists(orphan)).isFalse();
        assertThat(objectExists(pathOf(orphan))).isFalse();
    }

    @Test
    @DisplayName("同條件但被草稿引用 → 先被補綁定到該草稿、沒有被刪（B1 擴大到不分狀態的理由）")
    void run_rebindsFileReferencedByDraftBeforeCleanup_andKeepsIt() throws Exception {
        UUID inDraft = insertFile(UsageType.ARTICLE_CONTENT, 8);
        UUID orphan = insertFile(UsageType.ARTICLE_CONTENT, 8);
        stubDraftReferencing(List.of(inDraft), Set.of(inDraft));

        FileBindingReconcileJob.Result result = job.run();

        assertThat(result).isEqualTo(FileBindingReconcileJob.Result.COMPLETED);
        assertThat(boundArticleOf(inDraft)).as("應已補綁定到草稿").isEqualTo(draftArticle);
        assertThat(metadataExists(inDraft)).isTrue();
        assertThat(objectExists(pathOf(inDraft))).isTrue();
        assertThat(metadataExists(orphan)).as("同批的無主檔仍被清理").isFalse();
    }

    @Test
    @DisplayName("頭像不動、未滿 7 天的不動、只被封面引用（內文綁定回填不到）的不動")
    void run_keepsAvatarRecentAndCoverReferencedFiles() throws Exception {
        UUID avatar = insertFile(UsageType.AVATAR, 30);
        UUID recent = insertFile(UsageType.ARTICLE_CONTENT, 2);
        UUID coverOnly = insertFile(UsageType.ARTICLE_COVER, 30);
        when(articleLookupFacade.findUuidsUpdatedSinceAnyStatus(any(), any(), anyInt())).thenReturn(List.of());
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(author)).thenReturn(Set.of(coverOnly));

        job.run();

        for (UUID kept : List.of(avatar, recent, coverOnly)) {
            assertThat(metadataExists(kept)).as("metadata %s 應保留", kept).isTrue();
            assertThat(objectExists(pathOf(kept))).as("物件 %s 應保留", kept).isTrue();
        }
    }

    @Test
    @DisplayName("fail-safe：facade 查詢失敗 → FAILED，零刪除（metadata 與物件都在）")
    void run_facadeFails_deletesNothing() throws Exception {
        UUID orphan = insertFile(UsageType.ARTICLE_CONTENT, 8);
        when(articleLookupFacade.findUuidsUpdatedSinceAnyStatus(any(), any(), anyInt())).thenReturn(List.of());
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(author)).thenThrow(new IllegalStateException("article db down"));

        FileBindingReconcileJob.Result result = job.run();

        assertThat(result).isEqualTo(FileBindingReconcileJob.Result.FAILED);
        assertThat(metadataExists(orphan)).isTrue();
        assertThat(objectExists(pathOf(orphan))).isTrue();
    }

    @Test
    @DisplayName("重跑冪等：第二次執行沒有東西可處理，不出錯；已綁定的檔案不會被再次處理")
    void run_twice_isIdempotent() throws Exception {
        UUID inDraft = insertFile(UsageType.ARTICLE_CONTENT, 8);
        UUID orphan = insertFile(UsageType.ARTICLE_CONTENT, 8);
        stubDraftReferencing(List.of(inDraft), Set.of(inDraft));

        assertThat(job.run()).isEqualTo(FileBindingReconcileJob.Result.COMPLETED);
        assertThat(job.run()).isEqualTo(FileBindingReconcileJob.Result.COMPLETED);

        assertThat(metadataExists(orphan)).isFalse();
        assertThat(boundArticleOf(inDraft)).isEqualTo(draftArticle);
    }

    @Test
    @DisplayName("TOCTOU：掃描之後、刪除之前該檔被綁定到文章（模擬綁定 consumer 在此時 commit）→ 刪除當下鎖列重驗發現已綁定，保留")
    void run_boundBetweenScanAndDelete_isKept() throws Exception {
        UUID racing = insertFile(UsageType.ARTICLE_CONTENT, 8);
        when(articleLookupFacade.findUuidsUpdatedSinceAnyStatus(any(), any(), anyInt())).thenReturn(List.of());
        /* 階段一查引用集合時（候選頁已讀出）模擬作者存檔：綁定在此刻 commit */
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(author)).thenAnswer(inv -> {
            jdbc.update("UPDATE file_metadata SET article_uuid = ? WHERE id = ?", draftArticle, racing);
            return Set.of();
        });

        job.run();

        assertThat(metadataExists(racing)).as("被綁定的檔案不得被刪").isTrue();
        assertThat(objectExists(pathOf(racing))).isTrue();
        assertThat(boundArticleOf(racing)).isEqualTo(draftArticle);
    }

    @Test
    @DisplayName("TOCTOU：掃描之後、刪除之前該檔被作者的文章內文引用（重查引用集合才看得到）→ 保留，物件仍在")
    void run_referencedBetweenScanAndDelete_isKept() throws Exception {
        UUID racing = insertFile(UsageType.ARTICLE_CONTENT, 8);
        when(articleLookupFacade.findUuidsUpdatedSinceAnyStatus(any(), any(), anyInt())).thenReturn(List.of());
        /* 第一次（階段一）看不到引用，第二次（刪除當下的重驗）已看到 */
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(author))
                .thenReturn(Set.of()).thenReturn(Set.of(racing));

        job.run();

        assertThat(metadataExists(racing)).isTrue();
        assertThat(objectExists(pathOf(racing))).isTrue();
    }

    @Test
    @DisplayName("持鎖：刪除 MinIO 物件期間，另一連線對該列的綁定 UPDATE 會被擋住，等刪除交易結束後才執行（影響 0 列）")
    void run_rowLockHeldWhileDeletingObjects_concurrentBindWaits() throws Exception {
        UUID target = insertFile(UsageType.ARTICLE_CONTENT, 8);
        when(articleLookupFacade.findUuidsUpdatedSinceAnyStatus(any(), any(), anyInt())).thenReturn(List.of());
        when(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(author)).thenReturn(Set.of());
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Integer>> bind =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean blockedWhileDeleting = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(inv -> {
            if (bind.get() == null) {
                bind.set(executor.submit(() ->
                        jdbc.update("UPDATE file_metadata SET article_uuid = ? WHERE id = ?", draftArticle, target)));
                Thread.sleep(500);
                blockedWhileDeleting.set(!bind.get().isDone());
            }
            return inv.callRealMethod();
        }).when(minioClient).removeObject(any(io.minio.RemoveObjectArgs.class));

        try {
            job.run();

            assertThat(blockedWhileDeleting.get()).as("持鎖期間綁定 UPDATE 應被擋住").isTrue();
            assertThat(bind.get().get(10, java.util.concurrent.TimeUnit.SECONDS)).as("鎖釋放後列已刪除，UPDATE 影響 0 列").isZero();
            assertThat(metadataExists(target)).isFalse();
            assertThat(objectExists(pathOf(target))).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }
}
