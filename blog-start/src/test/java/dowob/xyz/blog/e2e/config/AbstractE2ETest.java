package dowob.xyz.blog.e2e.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import dowob.xyz.blog.BlogWebV2Application;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.containers.MinIOContainer;
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
            new PostgreSQLContainer<>("postgres:16-alpine");

    static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:3-management-alpine");

    static final MinIOContainer MINIO =
            new MinIOContainer("minio/minio:latest");

    static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.11.0")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("discovery.type", "single-node");

    static {
        // 所有容器只啟動一次，JVM 結束時自動關閉（Ryuk）
        POSTGRES.start();
        REDIS.start();
        RABBITMQ.start();
        MINIO.start();
        ELASTICSEARCH.start();
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
        // MinIO
        registry.add("minio.endpoint", MINIO::getS3URL);
        registry.add("minio.access-key", MINIO::getUserName);
        registry.add("minio.secret-key", MINIO::getPassword);
        registry.add("minio.bucket-name", () -> "blog-e2e-test");
        // Elasticsearch
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
    }

    // ========== 共用的注入 ==========

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    // ========== 容器資源初始化（ES 索引 + MinIO bucket）==========

    @BeforeAll
    static void initContainerResources(
            @Autowired ElasticsearchOperations esOps,
            @Autowired MinioClient minioClient) throws Exception {
        // Elasticsearch 索引初始化
        var indexOps = esOps.indexOps(ArticleDocument.class);
        if (!indexOps.exists()) {
            indexOps.createWithMapping();
        }

        // MinIO bucket 初始化
        String bucket = "blog-e2e-test";
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
