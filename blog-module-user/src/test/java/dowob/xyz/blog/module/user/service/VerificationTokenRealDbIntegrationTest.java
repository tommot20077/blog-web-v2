package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.VerificationToken;
import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.model.event.UserRegisteredEvent;
import dowob.xyz.blog.module.user.repository.UserRepository;
import dowob.xyz.blog.module.user.repository.VerificationTokenRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 以<b>真實</b> PostgreSQL 與真實 {@link VerificationTokenRepository} 驗證 verification_tokens「只存 SHA-256」（A1）。
 *
 * <p>涵蓋：V35 後的欄位形狀（{@code token_hash}、無 {@code token}）；{@link UserMailService} 寄出明文、DB 只存雜湊；
 * 重寄後舊 token 失效；{@code verifyEmail}／{@code resetPassword} 用明文成功、用雜湊本身失敗；
 * {@code forgotPassword}／{@code resendVerification} 不再建立 token；重設成功後不留任何 PASSWORD_RESET token。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@DisplayName("verification_tokens 只存 SHA-256 真 DB 整合測試（A1）")
class VerificationTokenRealDbIntegrationTest extends AbstractRealTokenIntegrationTest {

    private static final String PREFIX = "arch24r-";
    private static final String EMAIL = PREFIX + "user@example.com";

    @Autowired private VerificationTokenRepository tokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserMailService mailService;
    @Autowired private AuthService authService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM verification_tokens WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                PREFIX + "%");
        jdbc.update("DELETE FROM users WHERE email LIKE ?", PREFIX + "%");
        redis.delete(RedisKeyConstant.getForgotPwdMinKey(EMAIL));
        redis.delete(RedisKeyConstant.getForgotPwdDayKey(EMAIL));
        redis.delete(RedisKeyConstant.getResendVerifyMinKey(EMAIL));
        redis.delete(RedisKeyConstant.getResendVerifyDayKey(EMAIL));
        redis.delete(RedisKeyConstant.getEmailVerifyCodeKey(EMAIL));
    }

    @BeforeEach
    void stubMimeMessage() {
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
    }

    private User createUser(String email, UserStatus status) {
        User u = new User();
        u.setUuid(UUID.randomUUID());
        u.setEmail(email);
        u.setUsername(email.substring(0, email.indexOf('@')));
        u.setNickname("N" + email.substring(PREFIX.length(), email.indexOf('@')));
        u.setPasswordHash("hash");
        u.setRole(Role.USER);
        u.setStatus(status);
        u.setTokenVersion("v1");
        return userRepository.save(u);
    }

    /** 以明文 token 寫入一列（寫入的是它的 SHA-256），模擬寄信 consumer 已存入的狀態 */
    private void insertToken(User user, String type, String plainToken, LocalDateTime expiresAt) {
        VerificationToken t = new VerificationToken();
        t.setUserId(user.getId());
        t.setType(type);
        t.setTokenHash(TokenHasher.sha256Hex(plainToken));
        t.setExpiresAt(expiresAt);
        t.setCreatedAt(LocalDateTime.now());
        tokenRepository.save(t);
    }

    private long countTokens(User user, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM verification_tokens WHERE user_id = ? AND type = ?",
                Long.class, user.getId(), type);
    }

    private List<String> storedHashes(User user, String type) {
        return jdbc.queryForList("SELECT token_hash FROM verification_tokens WHERE user_id = ? AND type = ?",
                String.class, user.getId(), type);
    }

    private MimeMessage lastMail(int expectedTimes) {
        ArgumentCaptor<MimeMessage> mail = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(expectedTimes)).send(mail.capture());
        return mail.getAllValues().get(expectedTimes - 1);
    }

    @Test
    @DisplayName("V35：verification_tokens 欄位為 token_hash，舊的 token 欄位已不存在")
    void schema_hasTokenHashColumnAndNoPlainTokenColumn() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'verification_tokens'",
                String.class);

        assertThat(columns).contains("token_hash").doesNotContain("token");
    }

    @Test
    @DisplayName("寄重設信：信中是明文 token；DB 只存其 SHA-256（64 hex），且 DB 任何欄位都不含明文")
    void sendResetMail_storesOnlySha256OfMailedToken() throws Exception {
        User user = createUser(EMAIL, UserStatus.ACTIVE);

        boolean sent = mailService.sendPasswordResetEmail(
                new UserPasswordResetRequestedEvent(UUID.randomUUID(), user.getUuid()));

        assertThat(sent).isTrue();
        String plain = tokenIn(lastMail(1), "/reset-password");
        assertThat(storedHashes(user, "PASSWORD_RESET")).containsExactly(TokenHasher.sha256Hex(plain));
        assertThat(storedHashes(user, "PASSWORD_RESET").get(0)).isNotEqualTo(plain).matches("[0-9a-f]{64}");
        Long rowsContainingPlain = jdbc.queryForObject(
                "SELECT COUNT(*) FROM verification_tokens WHERE token_hash = ? OR type = ?", Long.class, plain, plain);
        assertThat(rowsContainingPlain).isZero();
    }

    @Test
    @DisplayName("重寄重設信：舊 token 失效（同類只剩最新一筆），舊明文不能再重設密碼")
    void sendResetMailTwice_invalidatesFirstToken() throws Exception {
        User user = createUser(EMAIL, UserStatus.ACTIVE);

        mailService.sendPasswordResetEmail(new UserPasswordResetRequestedEvent(UUID.randomUUID(), user.getUuid()));
        mailService.sendPasswordResetEmail(new UserPasswordResetRequestedEvent(UUID.randomUUID(), user.getUuid()));

        ArgumentCaptor<MimeMessage> mails = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(2)).send(mails.capture());
        String first = tokenIn(mails.getAllValues().get(0), "/reset-password");
        String second = tokenIn(mails.getAllValues().get(1), "/reset-password");
        assertThat(second).isNotEqualTo(first);
        assertThat(countTokens(user, "PASSWORD_RESET")).isEqualTo(1L);
        assertThatThrownBy(() -> authService.resetPassword(first, "NewPassword123!"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.TOKEN_INVALID.getCode()));
        authService.resetPassword(second, "NewPassword123!");
        assertThat(countTokens(user, "PASSWORD_RESET")).isZero();
    }

    @Test
    @DisplayName("寄驗證信：DB 只存雜湊；用明文 verifyEmail 成功並啟用帳號，用 DB 的雜湊值本身驗證失敗")
    void verifyEmail_acceptsPlainTokenButNotStoredHash() throws Exception {
        User user = createUser(EMAIL, UserStatus.PENDING_VERIFICATION);
        mailService.sendVerificationEmail(new UserRegisteredEvent(UUID.randomUUID(), user.getUuid()));
        String plain = tokenIn(lastMail(1), "/verify-email");
        String storedHash = storedHashes(user, "EMAIL_VERIFICATION").get(0);

        assertThat(storedHash).isEqualTo(TokenHasher.sha256Hex(plain)).isNotEqualTo(plain);
        assertThatThrownBy(() -> authService.verifyEmail(storedHash))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.TOKEN_INVALID.getCode()));
        assertThat(userRepository.findById(user.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.PENDING_VERIFICATION);

        authService.verifyEmail(plain);

        assertThat(userRepository.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(countTokens(user, "EMAIL_VERIFICATION")).isZero();
    }

    @Test
    @DisplayName("resetPassword：明文成功並更新密碼；DB 的雜湊值本身無法重設")
    void resetPassword_acceptsPlainTokenButNotStoredHash() throws Exception {
        User user = createUser(EMAIL, UserStatus.ACTIVE);
        mailService.sendPasswordResetEmail(new UserPasswordResetRequestedEvent(UUID.randomUUID(), user.getUuid()));
        String plain = tokenIn(lastMail(1), "/reset-password");
        String storedHash = storedHashes(user, "PASSWORD_RESET").get(0);

        assertThatThrownBy(() -> authService.resetPassword(storedHash, "NewPassword123!"))
                .isInstanceOf(BusinessException.class);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getPasswordHash()).isEqualTo("hash");

        authService.resetPassword(plain, "NewPassword123!");

        assertThat(userRepository.findById(user.getId()).orElseThrow().getPasswordHash()).isNotEqualTo("hash");
        assertThat(countTokens(user, "PASSWORD_RESET")).isZero();
    }

    @Test
    @DisplayName("過期的 token：即使明文正確，verifyEmail／resetPassword 也拒絕")
    void expiredToken_isRejectedEvenWithCorrectPlaintext() {
        User user = createUser(EMAIL, UserStatus.PENDING_VERIFICATION);
        insertToken(user, "EMAIL_VERIFICATION", "expired-verify-plain", LocalDateTime.now().minusMinutes(1));
        insertToken(user, "PASSWORD_RESET", "expired-reset-plain", LocalDateTime.now().minusMinutes(1));

        assertThatThrownBy(() -> authService.verifyEmail("expired-verify-plain")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> authService.resetPassword("expired-reset-plain", "NewPassword123!"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("forgotPassword 連按兩次：只發事件，不建立任何 token 列（token 由 consumer 於寄信當下產生）")
    void forgotPasswordTwice_createsNoTokenRowsButPublishesEvents() {
        User user = createUser(EMAIL, UserStatus.ACTIVE);

        authService.forgotPassword(EMAIL);
        redis.delete(RedisKeyConstant.getForgotPwdMinKey(EMAIL));
        authService.forgotPassword(EMAIL);

        assertThat(countTokens(user, "PASSWORD_RESET")).isZero();
        verify(rabbitTemplate, times(2)).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    @DisplayName("resendVerification：只發事件與寫 Redis 驗證碼，不建立 token 列")
    void resendVerification_createsNoTokenRow() {
        User user = createUser(EMAIL, UserStatus.PENDING_VERIFICATION);

        authService.resendVerification(EMAIL);

        assertThat(countTokens(user, "EMAIL_VERIFICATION")).isZero();
        verify(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    @DisplayName("resetPassword 成功後：該使用者不留任何 PASSWORD_RESET token（含較舊、仍未過期的）")
    void resetPassword_success_removesAllResetTokensOfUser() {
        User user = createUser(EMAIL, UserStatus.ACTIVE);
        LocalDateTime exp = LocalDateTime.now().plusMinutes(10);
        insertToken(user, "PASSWORD_RESET", "reset-t1", exp);
        insertToken(user, "PASSWORD_RESET", "reset-t2", exp);

        authService.resetPassword("reset-t2", "NewPassword123!");

        assertThat(countTokens(user, "PASSWORD_RESET")).isZero();
    }
}
