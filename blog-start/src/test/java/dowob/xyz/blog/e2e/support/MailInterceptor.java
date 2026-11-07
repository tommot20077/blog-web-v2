package dowob.xyz.blog.e2e.support;

import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.user.consumer.EmailVerificationConsumer;
import dowob.xyz.blog.module.user.consumer.PasswordResetConsumer;
import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.model.event.UserRegisteredEvent;
import dowob.xyz.blog.module.user.service.UserMailService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E2E 的寄信攔截器（A1）。
 *
 * <p>E2E 設定 {@code app.mail.consumer-enabled=false}，不會有真的 RabbitMQ consumer 在背景寄信；而 A1 之後
 * 驗證／重設 token 只在寄信當下由 consumer 產生、DB 只存 SHA-256，測試無法再直接從 DB 讀到可用的 token。
 * 本類以與正式 consumer 相同的 bean（{@link EmailVerificationConsumer}、{@link PasswordResetConsumer}，含真實冪等交易）
 * 處理一個指向該使用者的事件，並攔截 {@code JavaMailSender.send} 的信件，從信中連結取出明文 token——
 * 與真實使用者從信箱點連結的路徑一致。{@code JavaMailSenderImpl} 在 {@code AbstractE2ETest} 中為 mock。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
public class MailInterceptor {

    /** 明文 token 在信件連結中的格式（UUID） */
    private static final String TOKEN_REGEX = "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})";

    @Autowired
    private JavaMailSenderImpl mailSender;

    @Autowired
    private UserMailService userMailService;

    @Autowired
    private ConsumerExecutor consumerExecutor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 模擬「註冊／重寄」事件被 consumer 消費，攔截驗證信並回傳信中的明文 token。
     *
     * @param email 使用者 email
     * @return 驗證信連結中的明文 token
     * @throws Exception 讀取信件失敗
     */
    public String interceptVerificationToken(String email) throws Exception {
        prepare();
        new EmailVerificationConsumer(userMailService, consumerExecutor)
                .handleUserRegistered(new UserRegisteredEvent(UUID.randomUUID(), userUuidOf(email)));
        return tokenInLastMail("/verify-email");
    }

    /**
     * 模擬「忘記密碼」事件被 consumer 消費，攔截重設信並回傳信中的明文 token。
     *
     * @param email 使用者 email
     * @return 重設信連結中的明文 token
     * @throws Exception 讀取信件失敗
     */
    public String interceptPasswordResetToken(String email) throws Exception {
        prepare();
        new PasswordResetConsumer(userMailService, consumerExecutor)
                .handlePasswordResetRequested(new UserPasswordResetRequestedEvent(UUID.randomUUID(), userUuidOf(email)));
        return tokenInLastMail("/reset-password");
    }

    private void prepare() {
        clearInvocations(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
    }

    private UUID userUuidOf(String email) {
        return jdbcTemplate.queryForObject("SELECT uuid FROM users WHERE email = ?", UUID.class, email);
    }

    private String tokenInLastMail(String path) throws Exception {
        ArgumentCaptor<MimeMessage> mail = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(mail.capture());
        Matcher m = Pattern.compile(Pattern.quote(path) + "\\?token=" + TOKEN_REGEX)
                .matcher((String) mail.getValue().getContent());
        assertThat(m.find()).as("信件應含 %s?token=<uuid>", path).isTrue();
        return m.group(1);
    }
}
