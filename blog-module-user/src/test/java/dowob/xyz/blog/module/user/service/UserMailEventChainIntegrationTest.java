package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.user.config.UserRabbitMqConfig;
import dowob.xyz.blog.module.user.consumer.EmailVerificationConsumer;
import dowob.xyz.blog.module.user.consumer.PasswordResetConsumer;
import dowob.xyz.blog.module.user.model.VerificationToken;
import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.model.event.UserRegisteredEvent;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ARCH-24 整合測試：寄信事件不帶 email／token／驗證碼，consumer 寄信時回 DB／Redis 查詢（真實 PostgreSQL + Redis）。
 *
 * <p>鏈路：{@code AuthService}（註冊／忘記密碼／重發）→ 捕捉發出的事件 → 呼叫真實 consumer（含真實冪等表）→
 * 攔截 {@code JavaMailSender.send} 的信件內容。{@code verification_tokens} 的 Repository 在此測試基底為
 * {@code @MockitoBean}（見 {@link AbstractIntegrationTest}），以記憶體 map 模擬「最新一筆 token」的儲存與查詢；
 * users 與 Redis 為真實。真實 verification_tokens 表的行為由 E2E 套件的 PostgreSQL 覆蓋。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("寄信事件不帶個資與 token（ARCH-24）整合測試")
class UserMailEventChainIntegrationTest extends AbstractIntegrationTest {

    /** 本測試建立的資料前綴，供清理使用 */
    private static final String PREFIX = "arch24-";

    /** 測試用 email */
    private static final String EMAIL = PREFIX + "user@example.com";

    /** 測試用密碼 */
    private static final String PASSWORD = "Password123!";

    @Autowired
    private AuthService authService;

    @Autowired
    private EmailVerificationConsumer verificationConsumer;

    @Autowired
    private PasswordResetConsumer resetConsumer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;

    /** 以 type 為鍵的「最新 token」記憶體儲存（模擬 verification_tokens） */
    private final Map<String, VerificationToken> latestTokenByType = new ConcurrentHashMap<>();

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM processed_events WHERE consumer_name IN (?, ?)",
                EmailVerificationConsumer.CONSUMER_NAME, PasswordResetConsumer.CONSUMER_NAME);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", PREFIX + "%");
        redis.delete(RedisKeyConstant.getEmailVerifyCodeKey(EMAIL));
        redis.delete(RedisKeyConstant.getEmailVerifyCodeFailKey(EMAIL));
        redis.delete(RedisKeyConstant.getForgotPwdMinKey(EMAIL));
        redis.delete(RedisKeyConstant.getForgotPwdDayKey(EMAIL));
        redis.delete(RedisKeyConstant.getResendVerifyMinKey(EMAIL));
        redis.delete(RedisKeyConstant.getResendVerifyDayKey(EMAIL));
        latestTokenByType.clear();
    }

    @BeforeEach
    void stubTokenRepository() {
        when(verificationTokenRepository.save(any(VerificationToken.class))).thenAnswer(inv -> {
            VerificationToken t = inv.getArgument(0);
            latestTokenByType.put(t.getType(), t);
            return t;
        });
        when(verificationTokenRepository.findFirstByUserIdAndTypeOrderByIdDesc(anyLong(), anyString()))
                .thenAnswer(inv -> java.util.Optional.ofNullable(latestTokenByType.get(inv.<String>getArgument(1))));
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
    }

    private UserRegisteredEvent captureRegistered(int expectedTimes) {
        ArgumentCaptor<UserRegisteredEvent> captor = ArgumentCaptor.forClass(UserRegisteredEvent.class);
        verify(rabbitTemplate, times(expectedTimes)).convertAndSend(
                eq(UserRabbitMqConfig.EXCHANGE), eq(UserRabbitMqConfig.ROUTING_KEY_REGISTERED), captor.capture());
        List<UserRegisteredEvent> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }

    private UserPasswordResetRequestedEvent captureReset() {
        ArgumentCaptor<UserPasswordResetRequestedEvent> captor =
                ArgumentCaptor.forClass(UserPasswordResetRequestedEvent.class);
        verify(rabbitTemplate).convertAndSend(
                eq(UserRabbitMqConfig.EXCHANGE), eq(UserRabbitMqConfig.ROUTING_KEY_PASSWORD_RESET), captor.capture());
        return captor.getValue();
    }

    private List<MimeMessage> sentMails(int expected) {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(expected)).send(captor.capture());
        return captor.getAllValues();
    }

    private static String bodyOf(MimeMessage m) throws Exception {
        return (String) m.getContent();
    }

    @Test
    @DisplayName("全鏈路：註冊 → 事件不含個資 → consumer 查 users／token／Redis 後寄出驗證信（連結 token 與驗證碼正確）")
    void register_thenConsumer_sendsVerificationMailBuiltFromLookups() throws Exception {
        authService.register(EMAIL, PASSWORD, PREFIX + "user", "Arch24User");
        UserRegisteredEvent event = captureRegistered(1);
        assertThat(event.toString()).doesNotContain(EMAIL).doesNotContain("Arch24User");
        String token = latestTokenByType.get("EMAIL_VERIFICATION").getToken();
        String code = redis.opsForValue().get(RedisKeyConstant.getEmailVerifyCodeKey(EMAIL));

        verificationConsumer.handleUserRegistered(event);

        MimeMessage mail = sentMails(1).get(0);
        assertThat(mail.getAllRecipients()[0].toString()).isEqualTo(EMAIL);
        assertThat(bodyOf(mail)).contains("/verify-email?token=" + token).contains(code).contains("Arch24User");
    }

    @Test
    @DisplayName("全鏈路：忘記密碼 → 事件不含 email 與 token → consumer 查詢後寄出重設信")
    void forgotPassword_thenConsumer_sendsResetMailBuiltFromLookups() throws Exception {
        authService.register(EMAIL, PASSWORD, PREFIX + "user", "Arch24User");
        clearInvocations(rabbitTemplate);
        authService.forgotPassword(EMAIL);
        UserPasswordResetRequestedEvent event = captureReset();
        assertThat(event.toString()).doesNotContain(EMAIL);
        String token = latestTokenByType.get("PASSWORD_RESET").getToken();

        resetConsumer.handlePasswordResetRequested(event);

        MimeMessage mail = sentMails(1).get(0);
        assertThat(mail.getAllRecipients()[0].toString()).isEqualTo(EMAIL);
        assertThat(bodyOf(mail)).contains("/reset-password?token=" + token);
    }

    @Test
    @DisplayName("同一事件重送（at-least-once）→ 只寄一封")
    void redeliveredEvent_isNotMailedTwice() {
        authService.register(EMAIL, PASSWORD, PREFIX + "user", "Arch24User");
        UserRegisteredEvent event = captureRegistered(1);

        verificationConsumer.handleUserRegistered(event);
        verificationConsumer.handleUserRegistered(event);

        sentMails(1);
    }

    @Test
    @DisplayName("使用者連按重寄：晚到的舊事件只會寄出目前有效的 token，不會寄出已被取代的舊 token")
    void staleEventAfterResend_neverMailsSupersededToken() throws Exception {
        authService.register(EMAIL, PASSWORD, PREFIX + "user", "Arch24User");
        UserRegisteredEvent oldEvent = captureRegistered(1);
        String oldToken = latestTokenByType.get("EMAIL_VERIFICATION").getToken();
        authService.resendVerification(EMAIL);
        String newToken = latestTokenByType.get("EMAIL_VERIFICATION").getToken();
        assertThat(newToken).isNotEqualTo(oldToken);

        verificationConsumer.handleUserRegistered(oldEvent);

        String body = bodyOf(sentMails(1).get(0));
        assertThat(body).contains("token=" + newToken).doesNotContain(oldToken);
    }

    @Test
    @DisplayName("token 已被使用／刪除後事件才到 → 不寄，正常返回（ack）")
    void tokenGoneBeforeConsume_doesNotSend() {
        authService.register(EMAIL, PASSWORD, PREFIX + "user", "Arch24User");
        UserRegisteredEvent event = captureRegistered(1);
        latestTokenByType.clear();

        verificationConsumer.handleUserRegistered(event);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("寄信前使用者已刪帳（DELETED、email 為墓碑值）→ 驗證信與重設信都不寄")
    void userDeletedBeforeConsume_doesNotSend() {
        authService.register(EMAIL, PASSWORD, PREFIX + "user", "Arch24User");
        UserRegisteredEvent registered = captureRegistered(1);
        clearInvocations(rabbitTemplate);
        authService.forgotPassword(EMAIL);
        UserPasswordResetRequestedEvent reset = captureReset();
        jdbc.update("UPDATE users SET status = 'DELETED' WHERE email = ?", EMAIL);

        verificationConsumer.handleUserRegistered(registered);
        resetConsumer.handlePasswordResetRequested(reset);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}
