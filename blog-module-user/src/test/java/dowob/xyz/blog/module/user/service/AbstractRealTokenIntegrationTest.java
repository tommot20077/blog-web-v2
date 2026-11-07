package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.user.TestUserModuleApplication;
import jakarta.mail.internet.MimeMessage;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用<b>真實</b> {@code VerificationTokenRepository}（真 PostgreSQL + 真 Redis）的整合測試基底（A1）。
 *
 * <p>與 {@link AbstractIntegrationTest} 的差別：不把 token repository 換成 mock，因此「DB 裡存的是什麼」可以直接用
 * SQL 斷言。只 mock 對外的 RabbitMQ 與 SMTP；DB／Redis 容器沿用 {@link AbstractIntegrationTest} 的 singleton。
 * 子類別共用同一組 {@code @MockitoBean}，因此 Spring context 只建立一次。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = TestUserModuleApplication.class)
@ActiveProfiles("test")
abstract class AbstractRealTokenIntegrationTest {

    /** 明文 token 在信件連結中的格式（UUID） */
    private static final String TOKEN_REGEX = "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})";

    @MockitoBean
    protected ConnectionFactory connectionFactory;

    @MockitoBean
    protected RabbitTemplate rabbitTemplate;

    @MockitoBean
    protected JavaMailSender mailSender;

    @MockitoBean
    protected ArticleFacade articleFacade;

    /**
     * 注入容器連線屬性（沿用 {@link AbstractIntegrationTest} 的 singleton 容器）。
     *
     * @param registry Spring 動態屬性註冊器
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractIntegrationTest.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractIntegrationTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", AbstractIntegrationTest.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", AbstractIntegrationTest.REDIS::getHost);
        registry.add("spring.data.redis.port", AbstractIntegrationTest.REDIS::getFirstMappedPort);
    }

    /**
     * 取出信件本文中 {@code <path>?token=<uuid>} 的明文 token。
     *
     * @param mail 被攔截的信件
     * @param path 連結路徑，例如 {@code /verify-email}
     * @return 明文 token
     * @throws Exception 讀取信件失敗
     */
    protected static String tokenIn(MimeMessage mail, String path) throws Exception {
        Matcher m = Pattern.compile(Pattern.quote(path) + "\\?token=" + TOKEN_REGEX).matcher((String) mail.getContent());
        assertThat(m.find()).as("信件應含 %s?token=<uuid>", path).isTrue();
        return m.group(1);
    }
}
