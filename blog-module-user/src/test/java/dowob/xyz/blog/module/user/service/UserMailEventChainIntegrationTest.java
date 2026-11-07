package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.user.config.UserRabbitMqConfig;
import dowob.xyz.blog.module.user.consumer.EmailVerificationConsumer;
import dowob.xyz.blog.module.user.consumer.PasswordResetConsumer;
import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.model.event.UserRegisteredEvent;
import dowob.xyz.blog.module.user.util.TokenHasher;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ARCH-24／A1 全鏈路整合測試（真實 PostgreSQL + Redis + 真實 verification_tokens）。
 *
 * <p>鏈路：{@code AuthService}（註冊／忘記密碼／重發）→ 捕捉發出的事件（只含 eventId 與 userUuid）→
 * 呼叫真實 consumer（含真實冪等表與交易）→ 攔截 {@code JavaMailSender.send} 的信件 → 用信中的明文 token
 * 呼叫 {@code verifyEmail}／{@code resetPassword}。驗證 token 由 consumer 於寄信當下產生、DB 只存 SHA-256，
 * 以及「寄信失敗後重試會寄出新的有效 token」。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@DisplayName("寄信事件全鏈路：token 由 consumer 產生、DB 只存雜湊（ARCH-24／A1）")
class UserMailEventChainIntegrationTest extends AbstractRealTokenIntegrationTest {

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
    private StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM processed_events WHERE consumer_name IN (?, ?)",
                EmailVerificationConsumer.CONSUMER_NAME, PasswordResetConsumer.CONSUMER_NAME);
        jdbc.update("DELETE FROM verification_tokens WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                PREFIX + "%");
        jdbc.update("DELETE FROM users WHERE email LIKE ?", PREFIX + "%");
        redis.delete(RedisKeyConstant.getEmailVerifyCodeKey(EMAIL));
        redis.delete(RedisKeyConstant.getEmailVerifyCodeFailKey(EMAIL));
        redis.delete(RedisKeyConstant.getForgotPwdMinKey(EMAIL));
        redis.delete(RedisKeyConstant.getForgotPwdDayKey(EMAIL));
        redis.delete(RedisKeyConstant.getResendVerifyMinKey(EMAIL));
        redis.delete(RedisKeyConstant.getResendVerifyDayKey(EMAIL));
    }

    @BeforeEach
    void stubMimeMessage() {
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

    private long tokenRows(String type) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM verification_tokens vt JOIN users u ON u.id = vt.user_id
                WHERE u.email = ? AND vt.type = ?""", Long.class, EMAIL, type);
    }

    private String userStatus() {
        return jdbc.queryForObject("SELECT status FROM users WHERE email = ?", String.class, EMAIL);
    }

    private void assertTokenInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.TOKEN_INVALID.getCode()));
    }

    @Test
    @DisplayName("全鏈路：註冊 → 事件不含個資 → consumer 產生 token 並寄信 → 用信中 token 驗證成功；DB 只存其 SHA-256")
    void register_thenConsumer_mailsPlainTokenAndStoresHash_thenVerifyEmailSucceeds() throws Exception {
        authService.register(EMAIL, PASSWORD, PREFIX + "user", "Arch24User");
        UserRegisteredEvent event = captureRegistered(1);
        assertThat(event.toString()).doesNotContain(EMAIL).doesNotContain("Arch24User");
        assertThat(tokenRows("EMAIL_VERIFICATION")).as("註冊時 AuthService 不建立 token").isZero();
        String code = redis.opsForValue().get(RedisKeyConstant.getEmailVerifyCodeKey(EMAIL));

        verificationConsumer.handleUserRegistered(event);

        MimeMessage mail = sentMails(1).get(0);
        assertThat(mail.getAllRecipients()[0].toString()).isEqualTo(EMAIL);
        assertThat((String) mail.getContent()).contains(code).contains("Arch24User");
        String plain = tokenIn(mail, "/verify-email");
        String stored = jdbc.queryForObject("""
                SELECT vt.token_hash FROM verification_tokens vt JOIN users u ON u.id = vt.user_id
                WHERE u.email = ? AND vt.type = 'EMAIL_VERIFICATION'""", String.class, EMAIL);
        assertThat(stored).isEqualTo(TokenHasher.sha256Hex(plain)).isNotEqualTo(plain);

        assertTokenInvalid(() -> authService.verifyEmail(stored));
        assertThat(userStatus()).isEqualTo("PENDING_VERIFICATION");
        authService.verifyEmail(plain);
        assertThat(userStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("全鏈路：忘記密碼 → 事件不含 email 與 token → consumer 寄出重設信 → 用信中 token 重設成功")
    void forgotPassword_thenConsumer_mailsToken_thenResetPasswordSucceeds() throws Exception {
        authService.register(EMAIL, PASSWORD, PREFIX + "user", "Arch24User");
        clearInvocations(rabbitTemplate);
        authService.forgotPassword(EMAIL);
        UserPasswordResetRequestedEvent event = captureReset();
        assertThat(event.toString()).doesNotContain(EMAIL);
        assertThat(tokenRows("PASSWORD_RESET")).isZero();

        resetConsumer.handlePasswordResetRequested(event);

        MimeMessage mail = sentMails(1).get(0);
        assertThat(mail.getAllRecipients()[0].toString()).isEqualTo(EMAIL);
        String plain = tokenIn(mail, "/reset-password");
        String oldHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, EMAIL);

        authService.resetPassword(plain, "BrandNewPass123!");

        String newHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, EMAIL);
        assertThat(newHash).isNotEqualTo(oldHash);
        assertThat(tokenRows("PASSWORD_RESET")).isZero();
        assertTokenInvalid(() -> authService.resetPassword(plain, "AnotherPass123!"));
    }

    @Test
    @DisplayName("同一事件重送（at-least-once）→ 只寄一封，且 DB 仍只有一列 token")
    void redeliveredEvent_isNotMailedTwice() {
        authService.register(EMAIL, PASSWORD, PREFIX + "user", "Arch24User");
        UserRegisteredEvent event = captureRegistered(1);

        verificationConsumer.handleUserRegistered(event);
        verificationConsumer.handleUserRegistered(event);

        sentMails(1);
        assertThat(tokenRows("EMAIL_VERIFICATION")).isEqualTo(1L);
    }

    @Test
    @DisplayName("使用者連按重寄：每個事件各寄新 token，最後只有最新的有效，先前寄出的舊 token 失效")
    void resend_supersedesPreviousToken() throws Exception {
        authService.register(EMAIL, PASSWORD, PREFIX + "user", "Arch24User");
        UserRegisteredEvent first = captureRegistered(1);
        verificationConsumer.handleUserRegistered(first);
        redis.delete(RedisKeyConstant.getResendVerifyMinKey(EMAIL));
        authService.resendVerification(EMAIL);
        UserRegisteredEvent second = captureRegistered(2);
        assertThat(second.eventId()).isNotEqualTo(first.eventId());

        verificationConsumer.handleUserRegistered(second);

        List<MimeMessage> mails = sentMails(2);
        String oldToken = tokenIn(mails.get(0), "/verify-email");
        String newToken = tokenIn(mails.get(1), "/verify-email");
        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(tokenRows("EMAIL_VERIFICATION")).isEqualTo(1L);
        assertTokenInvalid(() -> authService.verifyEmail(oldToken));
        authService.verifyEmail(newToken);
        assertThat(userStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("寄信失敗 → 交易 rollback（不留 token、不標記已處理、舊 token 不被刪）；重試重新產生並寄出新的有效 token")
    void mailFailure_rollsBackThenRetrySendsFreshValidToken() throws Exception {
        authService.register(EMAIL, PASSWORD, PREFIX + "user", "Arch24User");
        UserRegisteredEvent event = captureRegistered(1);
        verificationConsumer.handleUserRegistered(event);
        String firstToken = tokenIn(sentMails(1).get(0), "/verify-email");
        redis.delete(RedisKeyConstant.getResendVerifyMinKey(EMAIL));
        authService.resendVerification(EMAIL);
        UserRegisteredEvent resend = captureRegistered(2);

        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));
        assertThatThrownBy(() -> verificationConsumer.handleUserRegistered(resend))
                .isInstanceOf(MailSendException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?::uuid",
                Long.class, resend.eventId().toString())).as("失敗不得標記已處理").isZero();
        assertThat(tokenRows("EMAIL_VERIFICATION")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT vt.token_hash FROM verification_tokens vt JOIN users u ON u.id = vt.user_id
                WHERE u.email = ? AND vt.type = 'EMAIL_VERIFICATION'""", String.class, EMAIL))
                .as("rollback 後仍是第一封信的 token 雜湊，沒有殘留失敗那次產生的雜湊")
                .isEqualTo(TokenHasher.sha256Hex(firstToken));

        clearInvocations(mailSender);
        doNothing().when(mailSender).send(any(MimeMessage.class));
        verificationConsumer.handleUserRegistered(resend);

        String retryToken = tokenIn(sentMails(1).get(0), "/verify-email");
        assertThat(retryToken).isNotEqualTo(firstToken);
        assertThat(tokenRows("EMAIL_VERIFICATION")).isEqualTo(1L);
        assertTokenInvalid(() -> authService.verifyEmail(firstToken));
        authService.verifyEmail(retryToken);
        assertThat(userStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("重設信寄信失敗後重試：寄出新的有效 token，可成功重設密碼")
    void resetMailFailure_thenRetry_sendsValidToken() throws Exception {
        authService.register(EMAIL, PASSWORD, PREFIX + "user", "Arch24User");
        clearInvocations(rabbitTemplate);
        authService.forgotPassword(EMAIL);
        UserPasswordResetRequestedEvent event = captureReset();

        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));
        assertThatThrownBy(() -> resetConsumer.handlePasswordResetRequested(event))
                .isInstanceOf(MailSendException.class);
        assertThat(tokenRows("PASSWORD_RESET")).isZero();

        clearInvocations(mailSender);
        doNothing().when(mailSender).send(any(MimeMessage.class));
        resetConsumer.handlePasswordResetRequested(event);

        String plain = tokenIn(sentMails(1).get(0), "/reset-password");
        authService.resetPassword(plain, "BrandNewPass123!");
        assertThat(tokenRows("PASSWORD_RESET")).isZero();
    }

    @Test
    @DisplayName("帳號已驗證後才消費到舊事件 → 不寄、不產生 token，正常返回（ack）")
    void staleVerificationEventAfterVerified_doesNotSendOrCreateToken() {
        authService.register(EMAIL, PASSWORD, PREFIX + "user", "Arch24User");
        UserRegisteredEvent event = captureRegistered(1);
        jdbc.update("UPDATE users SET status = 'ACTIVE', email_verified = TRUE WHERE email = ?", EMAIL);

        verificationConsumer.handleUserRegistered(event);

        verify(mailSender, never()).send(any(MimeMessage.class));
        assertThat(tokenRows("EMAIL_VERIFICATION")).isZero();
    }

    @Test
    @DisplayName("寄信前使用者已刪帳（DELETED、email 為墓碑值）→ 驗證信與重設信都不寄、不產生 token")
    void userDeletedBeforeConsume_doesNotSendOrCreateToken() {
        authService.register(EMAIL, PASSWORD, PREFIX + "user", "Arch24User");
        UserRegisteredEvent registered = captureRegistered(1);
        clearInvocations(rabbitTemplate);
        authService.forgotPassword(EMAIL);
        UserPasswordResetRequestedEvent reset = captureReset();
        jdbc.update("UPDATE users SET status = 'DELETED' WHERE email = ?", EMAIL);

        verificationConsumer.handleUserRegistered(registered);
        resetConsumer.handlePasswordResetRequested(reset);

        verify(mailSender, never()).send(any(MimeMessage.class));
        assertThat(tokenRows("EMAIL_VERIFICATION") + tokenRows("PASSWORD_RESET")).isZero();
    }
}
