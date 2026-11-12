package dowob.xyz.blog.e2e.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import dowob.xyz.blog.BlogWebV2Application;
import dowob.xyz.blog.module.search.init.SearchIndexInitializer;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * E2E 測試基底類別（Singleton Container Pattern）
 * <p>
 * 啟動完整的 BlogWebV2Application + 5 個 Testcontainers 容器。
 * 容器在 static initializer 中啟動，所有測試類共用同一組容器（只啟動一次）。
 * 所有外部服務連線由 @DynamicPropertySource 注入，不影響任何正式環境。
 */
@SpringBootTest(
        classes = BlogWebV2Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureMockMvc
@ActiveProfiles("e2e")
public abstract class AbstractE2ETest {

    // ========== Singleton Containers（只啟動一次，所有測試類共用）==========

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine");

    static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:8.8.0-alpine"));

    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:4.2.2-management-alpine");

    /** S3 相容儲存（SeaweedFS；應用程式仍以 MinIO Java SDK 連線，見 {@link SeaweedFsContainer}） */
    static final SeaweedFsContainer S3_STORAGE = new SeaweedFsContainer();

    static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.2.4")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("discovery.type", "single-node");

    static {
        // 所有容器只啟動一次，JVM 結束時自動關閉（Ryuk）
        POSTGRES.start();
        REDIS.start();
        RABBITMQ.start();
        createRabbitVhost("blog");
        S3_STORAGE.start();
        ELASTICSEARCH.start();
    }

    /**
     * 建立 RabbitMQ vhost 並授權給預設的 guest 使用者。
     *
     * <p>不使用 Testcontainers 的 {@code withVhost}：它內部呼叫 rabbitmqadmin v1 語法
     * （{@code declare vhost name=blog}），RabbitMQ 4.x image 內附的是 rabbitmqadmin v2
     * （參數改為 {@code --name}），會回 "unexpected argument 'name=blog'" 導致 vhost 沒建起來。
     * 改用 rabbitmqctl，跨 3.x／4.x 皆可用。
     */
    private static void createRabbitVhost(String vhost) {
        try {
            var added = RABBITMQ.execInContainer("rabbitmqctl", "add_vhost", vhost);
            var permitted = RABBITMQ.execInContainer(
                    "rabbitmqctl", "set_permissions", "-p", vhost, "guest", ".*", ".*", ".*");
            if (added.getExitCode() != 0 || permitted.getExitCode() != 0) {
                throw new IllegalStateException("建立 RabbitMQ vhost 失敗: " + added.getStderr() + permitted.getStderr());
            }
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException("建立 RabbitMQ vhost 失敗", e);
        }
    }

    // ========== 動態屬性注入（在 Spring Context 啟動前執行）==========

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Redis
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        // RabbitMQ
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "blog");
        // S3 相容儲存（屬性鍵沿用 minio.*：描述的是 MinIO Java SDK 的連線設定）
        registry.add("minio.endpoint", S3_STORAGE::getS3Url);
        registry.add("minio.access-key", S3_STORAGE::getAccessKey);
        registry.add("minio.secret-key", S3_STORAGE::getSecretKey);
        registry.add("minio.bucket-name", () -> "blog-e2e-test");
        // Elasticsearch
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
    }

    // ========== 共用的注入 ==========

    /**
     * 寄信一律 mock（E2E 不連真實 SMTP）。A1 起驗證／重設 token 只在寄信時產生、DB 只存雜湊，
     * 測試改由 {@link dowob.xyz.blog.e2e.support.MailInterceptor} 從攔截到的信件取得明文 token。
     */
    @MockitoBean
    protected JavaMailSenderImpl mailSender;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    // ========== 容器資源初始化（ES 索引 + MinIO bucket）==========

    @BeforeAll
    static void initContainerResources(
            @Autowired SearchIndexInitializer searchIndexInitializer,
            @Autowired MinioClient minioClient) throws Exception {
        /* Elasticsearch 索引初始化：與正式啟動同一套流程（alias ＋ 版本化實體索引，冪等）；
           不可再用 createWithMapping()，它會建出同名的實體索引而拆掉 alias */
        searchIndexInitializer.ensureCorrectMapping();

        // MinIO bucket 初始化
        String bucket = "blog-e2e-test";
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
