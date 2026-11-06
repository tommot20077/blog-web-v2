package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.file.TestFileApplication;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import dowob.xyz.blog.module.file.support.SeaweedFsContainer;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 檔案配額並發保護整合測試（RACE-12；真實 PostgreSQL + 真實 S3 相容儲存）。
 *
 * <p>驗證：同一使用者剩餘額度只夠一個檔案時，兩個並發上傳只成功一個，失敗者上傳的物件被刪除；
 * 不同使用者的上傳互不阻塞（advisory lock 以上傳者為鍵）。另驗證 S3 對不存在物件的 DeleteObject 不報錯
 * （{@code deleteFile} 一律嘗試刪縮圖路徑所依賴的行為）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = TestFileApplication.class)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("uploadFile 配額並發保護整合測試 (RACE-12)")
class FileUploadQuotaRaceIT {

    private static final String BUCKET = "blog-files";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static com.redis.testcontainers.RedisContainer redis =
            new com.redis.testcontainers.RedisContainer("redis:7-alpine");

    @Container
    static SeaweedFsContainer s3Storage = new SeaweedFsContainer();

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
        registry.add("minio.bucket-name", () -> BUCKET);
    }

    @Autowired private FileServiceImpl fileService;
    @Autowired private FileMetadataRepository fileMetadataRepository;
    @Autowired private MinioClient minioClient;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ArticleLookupFacade articleLookupFacade;

    @BeforeAll
    static void setupBucket(@Autowired MinioClient minioClient) throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        fileMetadataRepository.deleteAll();
        for (String key : listKeys()) {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(key).build());
        }
    }

    /** 產生 4MB 級別、不可壓縮的真實 PNG（未超過 5MB 單檔上限） */
    private static byte[] noisePng() throws Exception {
        BufferedImage img = new BufferedImage(1100, 1100, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(42);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setRGB(x, y, rnd.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private List<String> listKeys() throws Exception {
        List<String> keys = new ArrayList<>();
        for (Result<Item> r : minioClient.listObjects(
                ListObjectsArgs.builder().bucket(BUCKET).prefix("articles/").recursive(true).build())) {
            keys.add(r.get().objectName());
        }
        return keys;
    }

    /** 預先寫入一筆佔用 {@code size} 位元組的檔案，使該使用者的 USER 配額（10MB）只剩 {@code remaining} */
    private void occupyQuotaLeaving(UUID uploader, long remaining) {
        long quota = 10L * 1024 * 1024;
        FileMetadata m = new FileMetadata();
        UUID id = UUID.randomUUID();
        m.setId(id);
        m.setNewEntity(true);
        m.setOriginalName("existing.png");
        m.setStoragePath("existing/" + id + ".png");
        m.setContentType("image/png");
        m.setSize(quota - remaining);
        m.setUsageType(UsageType.ARTICLE_CONTENT);
        m.setUploaderId(uploader);
        m.setHasThumbnail(false);
        m.setCreatedAt(LocalDateTime.now());
        fileMetadataRepository.save(m);
    }

    @Test
    @DisplayName("剩餘額度只夠一個檔案時，兩個並發上傳只成功一個，失敗者的 S3 物件被刪除")
    void concurrentUploads_onlyOneFits_otherRejectedAndObjectRemoved() throws Exception {
        byte[] png = noisePng();
        assertThat(png.length).isBetween(3_000_000, 5_000_000);
        UUID uploader = UUID.randomUUID();
        occupyQuotaLeaving(uploader, png.length + png.length / 2L);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String name = "n" + i + ".png";
                futures.add(pool.submit((Callable<Object>) () -> {
                    start.await();
                    return fileService.uploadFile(new MockMultipartFile("file", name, "image/png", png),
                            UsageType.ARTICLE_CONTENT, uploader, "USER");
                }));
            }
            start.countDown();

            int ok = 0;
            int quotaRejected = 0;
            for (Future<Object> f : futures) {
                try {
                    f.get(60, TimeUnit.SECONDS);
                    ok++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) e.getCause()).getCode()).isEqualTo("A0403");
                    quotaRejected++;
                }
            }
            assertThat(ok).isEqualTo(1);
            assertThat(quotaRejected).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM file_metadata WHERE uploader_id = ? AND storage_path LIKE 'articles/%'",
                Integer.class, uploader);
        assertThat(rows).isEqualTo(1);
        assertThat(listKeys()).as("失敗者上傳的 S3 物件必須被刪除，不留孤兒").hasSize(1);
    }

    @Test
    @DisplayName("不同使用者互不阻塞：A 的配額鎖被占住時，B 仍可上傳；A 要等鎖釋放才完成")
    void differentUploaders_doNotBlockEachOther() throws Exception {
        byte[] png = noisePng();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            /* 另一個交易占住 A 的配額鎖 */
            Future<?> holder = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                fileMetadataRepository.acquireQuotaLock(FileMetadataRepository.QUOTA_LOCK_KEY_PREFIX + userA);
                lockHeld.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(lockHeld.await(30, TimeUnit.SECONDS)).isTrue();

            Future<Object> uploadA = pool.submit((Callable<Object>) () -> fileService.uploadFile(
                    new MockMultipartFile("file", "a.png", "image/png", png), UsageType.ARTICLE_CONTENT, userA, "USER"));
            Future<Object> uploadB = pool.submit((Callable<Object>) () -> fileService.uploadFile(
                    new MockMultipartFile("file", "b.png", "image/png", png), UsageType.ARTICLE_CONTENT, userB, "USER"));

            assertThat(uploadB.get(30, TimeUnit.SECONDS)).as("B 不受 A 的鎖影響").isNotNull();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> uploadA.get(2, TimeUnit.SECONDS))
                    .as("A 仍被鎖阻塞").isInstanceOf(TimeoutException.class);

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            assertThat(uploadA.get(30, TimeUnit.SECONDS)).as("鎖釋放後 A 完成").isNotNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("S3 對不存在物件的 removeObject 不報錯（deleteFile 一律嘗試刪縮圖路徑的前提）")
    void removeObject_ofNonExistingKey_doesNotThrow() {
        org.assertj.core.api.Assertions.assertThatCode(() -> minioClient.removeObject(
                RemoveObjectArgs.builder().bucket(BUCKET).object("articles/none/does-not-exist_thumb.jpg").build()))
                .doesNotThrowAnyException();
    }
}
