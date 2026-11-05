package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.VerificationToken;
import dowob.xyz.blog.module.user.model.event.RegistrationAttemptOnExistingEmailEvent;
import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.model.event.UserRegisteredEvent;
import dowob.xyz.blog.module.user.repository.UserRepository;
import dowob.xyz.blog.module.user.repository.VerificationTokenRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link UserMailService} 單元測試
 *
 * <p>ARCH-24 起事件只帶 {@code eventId}／{@code userUuid}，本服務於寄信當下查 users、verification_tokens 與 Redis。
 * 這裡驗證：寄出的內容取自查詢結果（而非事件）；token 已過期／已被取代刪除／使用者已刪帳時不寄。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@DisplayName("UserMailService 單元測試")
class UserMailServiceTest {

    /** 測試用使用者 uuid（與 Long 主鍵無數值關聯） */
    private static final UUID USER_UUID = UUID.fromString("cccccccc-0000-4000-8000-000000000003");

    /** 測試用使用者 Long 主鍵 */
    private static final long USER_ID = 7L;

    /** 資料庫儲存的信箱 */
    private static final String DB_EMAIL = "db-user@example.com";

    private JavaMailSender mailSender;
    private UserRepository userRepository;
    private VerificationTokenRepository tokenRepository;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private MimeMessage message;
    private UserMailService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        userRepository = mock(UserRepository.class);
        tokenRepository = mock(VerificationTokenRepository.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);
        service = new UserMailService(mailSender, userRepository, tokenRepository, redisTemplate);
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "https://90030.xyz");
        ReflectionTestUtils.setField(service, "securityFromAddress", "no-reply@90030.xyz");
        ReflectionTestUtils.setField(service, "securityFromName", "Blog Security Bot");
    }

    private User user(UserStatus status) {
        User u = new User();
        u.setId(USER_ID);
        u.setUuid(USER_UUID);
        u.setEmail(DB_EMAIL);
        u.setNickname("Yuan");
        u.setStatus(status);
        return u;
    }

    private void givenUser(UserStatus status) {
        when(userRepository.findByUuid(USER_UUID)).thenReturn(Optional.of(user(status)));
    }

    private void givenToken(String type, String token, LocalDateTime expiresAt) {
        VerificationToken t = new VerificationToken();
        t.setId(100L);
        t.setUserId(USER_ID);
        t.setType(type);
        t.setToken(token);
        t.setExpiresAt(expiresAt);
        when(tokenRepository.findFirstByUserIdAndTypeOrderByIdDesc(USER_ID, type)).thenReturn(Optional.of(t));
    }

    private static UserRegisteredEvent registered() {
        return new UserRegisteredEvent(UUID.randomUUID(), USER_UUID);
    }

    private static UserPasswordResetRequestedEvent resetRequested() {
        return new UserPasswordResetRequestedEvent(UUID.randomUUID(), USER_UUID);
    }

    @Test
    @DisplayName("sendVerificationEmail → 收件人／暱稱取自 users，連結 token 取自 verification_tokens，驗證碼取自 Redis")
    void sendVerificationEmail_shouldSendMailBuiltFromLookups() throws Exception {
        givenUser(UserStatus.PENDING_VERIFICATION);
        givenToken("EMAIL_VERIFICATION", "verify-token-abc", LocalDateTime.now().plusHours(1));
        when(valueOps.get(RedisKeyConstant.getEmailVerifyCodeKey(DB_EMAIL))).thenReturn("123456");

        boolean sent = service.sendVerificationEmail(registered());

        assertThat(sent).isTrue();
        verify(mailSender).send(any(MimeMessage.class));
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(DB_EMAIL);
        assertThat(message.getFrom()[0].toString())
                .contains("Blog Security Bot")
                .contains("no-reply@90030.xyz");
        assertThat(message.getSubject()).isEqualTo("Verify your email address");
        assertThat((String) message.getContent())
                .contains("https://90030.xyz/verify-email?token=verify-token-abc")
                .contains("123456")
                .contains("Yuan");
    }

    @Test
    @DisplayName("sendVerificationEmail：Redis 已無驗證碼但連結仍有效 → 照寄，只含連結、不含驗證碼段落")
    void sendVerificationEmail_codeExpiredInRedis_sendsLinkOnly() throws Exception {
        givenUser(UserStatus.PENDING_VERIFICATION);
        givenToken("EMAIL_VERIFICATION", "verify-token-abc", LocalDateTime.now().plusHours(1));
        when(valueOps.get(RedisKeyConstant.getEmailVerifyCodeKey(DB_EMAIL))).thenReturn(null);

        boolean sent = service.sendVerificationEmail(registered());

        assertThat(sent).isTrue();
        String body = (String) message.getContent();
        assertThat(body).contains("https://90030.xyz/verify-email?token=verify-token-abc");
        assertThat(body).doesNotContain("verification code").doesNotContain("null");
    }

    @Test
    @DisplayName("sendVerificationEmail：Redis 讀取拋例外 → 降級只寄連結（不重試進 DLQ），WARN 不含 email")
    void sendVerificationEmail_redisFailure_degradesToLinkOnly() throws Exception {
        givenUser(UserStatus.PENDING_VERIFICATION);
        givenToken("EMAIL_VERIFICATION", "verify-token-abc", LocalDateTime.now().plusHours(1));
        when(valueOps.get(RedisKeyConstant.getEmailVerifyCodeKey(DB_EMAIL)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));

        boolean sent = service.sendVerificationEmail(registered());

        assertThat(sent).isTrue();
        assertThat((String) message.getContent())
                .contains("https://90030.xyz/verify-email?token=verify-token-abc")
                .doesNotContain("verification code");
    }

    @Test
    @DisplayName("sendVerificationEmail：token 已過期 → 不寄（也不查驗證碼）")
    void sendVerificationEmail_expiredToken_doesNotSend() {
        givenUser(UserStatus.PENDING_VERIFICATION);
        givenToken("EMAIL_VERIFICATION", "verify-token-old", LocalDateTime.now().minusSeconds(1));

        boolean sent = service.sendVerificationEmail(registered());

        assertThat(sent).isFalse();
        verify(mailSender, never()).send(any(MimeMessage.class));
        verifyNoInteractions(valueOps);
    }

    @Test
    @DisplayName("sendVerificationEmail：已無 token 列（已驗證刪除或已被刪除）→ 不寄")
    void sendVerificationEmail_noTokenRow_doesNotSend() {
        givenUser(UserStatus.PENDING_VERIFICATION);
        when(tokenRepository.findFirstByUserIdAndTypeOrderByIdDesc(USER_ID, "EMAIL_VERIFICATION"))
                .thenReturn(Optional.empty());

        assertThat(service.sendVerificationEmail(registered())).isFalse();

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendVerificationEmail：使用者已刪帳（DELETED，email 為墓碑值）→ 不寄，連 token 都不查")
    void sendVerificationEmail_deletedUser_doesNotSend() {
        givenUser(UserStatus.DELETED);

        assertThat(service.sendVerificationEmail(registered())).isFalse();

        verify(mailSender, never()).send(any(MimeMessage.class));
        verifyNoInteractions(tokenRepository);
    }

    @Test
    @DisplayName("sendVerificationEmail：查無使用者 → 不寄")
    void sendVerificationEmail_unknownUser_doesNotSend() {
        when(userRepository.findByUuid(USER_UUID)).thenReturn(Optional.empty());

        assertThat(service.sendVerificationEmail(registered())).isFalse();

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendPasswordResetEmail → 收件人取自 users，重設連結 token 取自 verification_tokens")
    void sendPasswordResetEmail_shouldSendMailBuiltFromLookups() throws Exception {
        givenUser(UserStatus.ACTIVE);
        givenToken("PASSWORD_RESET", "reset-token-abc", LocalDateTime.now().plusMinutes(5));

        boolean sent = service.sendPasswordResetEmail(resetRequested());

        assertThat(sent).isTrue();
        verify(mailSender).send(any(MimeMessage.class));
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(DB_EMAIL);
        assertThat(message.getFrom()[0].toString())
                .contains("Blog Security Bot")
                .contains("no-reply@90030.xyz");
        assertThat(message.getSubject()).isEqualTo("Reset your password");
        assertThat((String) message.getContent())
                .contains("https://90030.xyz/reset-password?token=reset-token-abc");
    }

    @Test
    @DisplayName("sendPasswordResetEmail：token 已過期 → 不寄")
    void sendPasswordResetEmail_expiredToken_doesNotSend() {
        givenUser(UserStatus.ACTIVE);
        givenToken("PASSWORD_RESET", "reset-token-old", LocalDateTime.now().minusMinutes(1));

        assertThat(service.sendPasswordResetEmail(resetRequested())).isFalse();

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendPasswordResetEmail：token 已被使用（列已刪）→ 不寄")
    void sendPasswordResetEmail_noTokenRow_doesNotSend() {
        givenUser(UserStatus.ACTIVE);
        when(tokenRepository.findFirstByUserIdAndTypeOrderByIdDesc(USER_ID, "PASSWORD_RESET"))
                .thenReturn(Optional.empty());

        assertThat(service.sendPasswordResetEmail(resetRequested())).isFalse();

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendPasswordResetEmail：使用者已刪帳 → 不寄")
    void sendPasswordResetEmail_deletedUser_doesNotSend() {
        givenUser(UserStatus.DELETED);

        assertThat(service.sendPasswordResetEmail(resetRequested())).isFalse();

        verify(mailSender, never()).send(any(MimeMessage.class));
        verifyNoInteractions(tokenRepository);
    }

    @Test
    @DisplayName("sendRegistrationAttemptNotice → 寄給 users 查到的原信箱主人，引導登入／忘記密碼，且不含任何 token")
    void sendRegistrationAttemptNotice_shouldNotifyOwnerWithLoginAndForgotPasswordLinks() throws Exception {
        givenUser(UserStatus.ACTIVE);

        boolean sent = service.sendRegistrationAttemptNotice(
                new RegistrationAttemptOnExistingEmailEvent(UUID.randomUUID(), USER_UUID));

        assertThat(sent).isTrue();
        verify(mailSender).send(any(MimeMessage.class));
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(DB_EMAIL);
        assertThat(message.getFrom()[0].toString())
                .contains("Blog Security Bot")
                .contains("no-reply@90030.xyz");
        assertThat(message.getSubject()).isEqualTo("Someone tried to register with your email address");
        assertThat((String) message.getContent())
                .contains("Yuan")
                .contains("https://90030.xyz/login")
                .contains("https://90030.xyz/forgot-password")
                .doesNotContain("token=");
        verifyNoInteractions(tokenRepository);
    }

    @Test
    @DisplayName("sendRegistrationAttemptNotice：使用者已刪帳 → 不寄")
    void sendRegistrationAttemptNotice_deletedUser_doesNotSend() {
        givenUser(UserStatus.DELETED);

        boolean sent = service.sendRegistrationAttemptNotice(
                new RegistrationAttemptOnExistingEmailEvent(UUID.randomUUID(), USER_UUID));

        assertThat(sent).isFalse();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("略過不寄時以 INFO 記一行（含 userUuid 與原因），且不記 email 與 token")
    void skippedMail_logsOneInfoLineWithoutPii() {
        givenUser(UserStatus.DELETED);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        Logger logger = (Logger) LogManager.getLogger(UserMailService.class);
        logger.addAppender(appender);
        Level original = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            service.sendPasswordResetEmail(resetRequested());
        } finally {
            logger.setLevel(original);
            logger.removeAppender(appender);
            appender.stop();
        }

        assertThat(appender.events).hasSize(1).allSatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getMessage().getFormattedMessage())
                    .contains("略過")
                    .contains("userUuid=" + USER_UUID)
                    .doesNotContain(DB_EMAIL);
        });
    }

    /** 捕捉日誌事件的測試用 appender（專案日誌實作為 log4j2） */
    private static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("user-mail-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
