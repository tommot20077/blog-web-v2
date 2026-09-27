package dowob.xyz.blog.module.user.service;

import com.redis.testcontainers.RedisContainer;
import dowob.xyz.blog.module.user.TestUserModuleApplication;
import dowob.xyz.blog.module.user.repository.VerificationTokenRepository;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
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
public abstract class AbstractIntegrationTest {

    /**
     * Mock RabbitMQ ConnectionFactory
     *
     * <p>
     * 整合測試排除了 RabbitAutoConfiguration，但 RabbitMqConfig 仍需要 ConnectionFactory。
     * 此 @MockitoBean 提供一個 Mockito mock，讓 Spring Context 可以正常啟動，
     * 而不需要真實的 RabbitMQ 連線。
     * </p>
     */
    @MockitoBean
    protected ConnectionFactory connectionFactory;

    /**
     * Mock RabbitTemplate
     *
     * <p>
     * AuthService 依賴 RabbitTemplate 發送用戶事件。
     * 整合測試不需要真實 RabbitMQ，以 MockBean 替代。
     * </p>
     */
    @MockitoBean
    protected RabbitTemplate rabbitTemplate;

    /**
     * Mock VerificationTokenRepository
     *
     * <p>
     * AuthService.register() 需要儲存 VerificationToken，
     * 整合測試不設置 verification_tokens 資料表，以 MockBean 替代。
     * </p>
     */
    @MockitoBean
    protected VerificationTokenRepository verificationTokenRepository;

    /**
     * Mock JavaMailSender
     *
     * <p>
     * UserMailService 依賴 JavaMailSender 發送郵件。
     * 整合測試不需要真實 SMTP 連線，以 MockBean 替代，
     * 同時避免 Spring Boot Mail Auto-Configuration 因未設定 host 而啟動失敗。
     * </p>
     */
    @MockitoBean
    protected JavaMailSender mailSender;

    /**
     * PostgreSQL 測試容器（singleton：整個測試 JVM 只啟動一次，所有子類別共用）
     *
     * <p><b>不可改回 {@code @Container}</b>：JUnit 的 Testcontainers extension 會在<b>每個測試類別結束時</b>
     * 停掉 static 容器、下一個類別再啟動一個新的（新的隨機 port）；但所有子類別的 Spring 設定相同，
     * context 會被快取重用，第二個子類別拿到的 DataSource 仍指向已停止容器的舊 port，
     * 每個測試都等滿連線逾時後失敗。2026-09-27 PR #72 的 CI 即為此：新增第二個子類別
     * {@code UserFacadeIntegrationTest} 後，{@code AuthServiceIntegrationTest} 11 個案例全數
     * {@code Connection to localhost:<舊 port> refused}。先前只有一個子類別，問題不會浮現。</p>
     *
     * <p>改由 static initializer 啟動、不手動停止，JVM 結束時由 Testcontainers 的 Ryuk 回收
     * （Testcontainers 官方的 singleton container 模式）。</p>
     */
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Redis 測試容器（singleton，理由同 {@link #POSTGRES}）
     */
    static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    static {
        POSTGRES.start();
        REDIS.start();
    }

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
