package dowob.xyz.blog.module.user.service;

import com.redis.testcontainers.RedisContainer;
import dowob.xyz.blog.module.user.TestUserModuleApplication;
import dowob.xyz.blog.module.user.repository.VerificationTokenRepository;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 整合測試基底類別
 *
 * <p>
 * 使用 Testcontainers 在 Docker 中自動啟動 PostgreSQL 與 Redis 容器，
 * 並透過 {@link DynamicPropertySource} 在 Spring Context 啟動前動態覆蓋連線設定。
 * 所有整合測試類別應繼承此類別。
 * </p>
 *
 * <p>
 * 使用 {@code @ActiveProfiles("test")} 啟用 {@code application-test.properties}，
 * 以關閉 Elasticsearch、RabbitMQ、MinIO 等非測試必要的 Auto-Configuration。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = TestUserModuleApplication.class)
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {

    /**
     * Mock RabbitMQ ConnectionFactory
     *
     * <p>
     * 整合測試排除了 RabbitAutoConfiguration，但 RabbitMqConfig 仍需要 ConnectionFactory。
     * 此 @MockBean 提供一個 Mockito mock，讓 Spring Context 可以正常啟動，
     * 而不需要真實的 RabbitMQ 連線。
     * </p>
     */
    @MockBean
    protected ConnectionFactory connectionFactory;

    /**
     * Mock RabbitTemplate
     *
     * <p>
     * AuthService 依賴 RabbitTemplate 發送用戶事件。
     * 整合測試不需要真實 RabbitMQ，以 MockBean 替代。
     * </p>
     */
    @MockBean
    protected RabbitTemplate rabbitTemplate;

    /**
     * Mock VerificationTokenRepository
     *
     * <p>
     * AuthService.register() 需要儲存 VerificationToken，
     * 整合測試不設置 verification_tokens 資料表，以 MockBean 替代。
     * </p>
     */
    @MockBean
    protected VerificationTokenRepository verificationTokenRepository;

    /**
     * PostgreSQL 測試容器（所有子測試類別共用同一實例以提升效率）
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Redis 測試容器
     */
    @Container
    static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    /**
     * 動態注入容器的連線屬性到 Spring Context。
     *
     * <p>
     * 此方法在 Spring Context 建立前執行，確保 DataSource 與 Redis
     * 指向 Testcontainers 隨機分配的連接埠，而非固定的本機設定。
     * </p>
     *
     * @param registry Spring 動態屬性註冊器
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }
}
