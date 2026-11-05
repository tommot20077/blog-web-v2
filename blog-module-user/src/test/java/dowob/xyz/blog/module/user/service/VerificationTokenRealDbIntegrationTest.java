package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.user.TestUserModuleApplication;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.VerificationToken;
import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.repository.UserRepository;
import dowob.xyz.blog.module.user.repository.VerificationTokenRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 以<b>真實</b> PostgreSQL 與真實 {@link VerificationTokenRepository} 驗證 ARCH-24 的 token 查詢與清理。
 *
 * <p>不繼承 {@link AbstractIntegrationTest}（它把 token repository 換成 mock），DB／Redis 容器沿用其 singleton；
 * Flyway 會建立 {@code verification_tokens} 表。涵蓋：衍生查詢 {@code findFirstByUserIdAndTypeOrderByIdDesc}、
 * {@link UserMailService} 只寄最新 token、忘記密碼後只剩一筆、重設成功後不留任何 PASSWORD_RESET token。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = TestUserModuleApplication.class)
@ActiveProfiles("test")
@DisplayName("verification_tokens 真 DB 整合測試（ARCH-24）")
class VerificationTokenRealDbIntegrationTest {

    private static final String PREFIX = "arch24r-";
    private static final String EMAIL = PREFIX + "user@example.com";

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private JavaMailSender mailSender;
    @MockitoBean private ArticleFacade articleFacade;

    @Autowired private VerificationTokenRepository tokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserMailService mailService;
    @Autowired private AuthService authService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractIntegrationTest.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractIntegrationTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", AbstractIntegrationTest.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", AbstractIntegrationTest.REDIS::getHost);
        registry.add("spring.data.redis.port", AbstractIntegrationTest.REDIS::getFirstMappedPort);
    }

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM verification_tokens WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                PREFIX + "%");
        jdbc.update("DELETE FROM users WHERE email LIKE ?", PREFIX + "%");
        redis.delete(RedisKeyConstant.getForgotPwdMinKey(EMAIL));
        redis.delete(RedisKeyConstant.getForgotPwdDayKey(EMAIL));
        redis.delete(RedisKeyConstant.getEmailVerifyCodeKey(EMAIL));
    }

    private User createUser(String email, UserStatus status) {
        User u = new User();
        u.setUuid(UUID.randomUUID());
        u.setEmail(email);
        u.setUsername(email.substring(0, email.indexOf('@')));
        u.setNickname("N" + email.substring(PREFIX.length(), email.indexOf('@')));
        u.setPasswordHash("hash");
        u.setRole(dowob.xyz.blog.common.api.enums.Role.USER);
        u.setStatus(status);
        u.setTokenVersion("v1");
        return userRepository.save(u);
    }

    private void insertToken(User user, String type, String token, LocalDateTime expiresAt) {
        VerificationToken t = new VerificationToken();
        t.setUserId(user.getId());
        t.setType(type);
        t.setToken(token);
        t.setExpiresAt(expiresAt);
        t.setCreatedAt(LocalDateTime.now());
        tokenRepository.save(t);
    }

    private long countTokens(User user, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM verification_tokens WHERE user_id = ? AND type = ?",
                Long.class, user.getId(), type);
    }

    @Test
    @DisplayName("findFirstByUserIdAndTypeOrderByIdDesc：多筆時只回該 type 最新（id 最大）一筆，查無回 empty")
    void findLatest_filtersByTypeAndReturnsNewest() {
        User user = createUser(EMAIL, UserStatus.ACTIVE);
        User other = createUser(PREFIX + "other@example.com", UserStatus.ACTIVE);
        LocalDateTime exp = LocalDateTime.now().plusHours(1);
        insertToken(user, "PASSWORD_RESET", "reset-1", exp);
        insertToken(user, "PASSWORD_RESET", "reset-2", exp);
        insertToken(user, "EMAIL_VERIFICATION", "verify-1", exp);
        insertToken(other, "PASSWORD_RESET", "other-reset", exp);

        assertThat(tokenRepository.findFirstByUserIdAndTypeOrderByIdDesc(user.getId(), "PASSWORD_RESET"))
                .get().extracting(VerificationToken::getToken).isEqualTo("reset-2");
        assertThat(tokenRepository.findFirstByUserIdAndTypeOrderByIdDesc(user.getId(), "EMAIL_VERIFICATION"))
                .get().extracting(VerificationToken::getToken).isEqualTo("verify-1");
        assertThat(tokenRepository.findFirstByUserIdAndTypeOrderByIdDesc(other.getId(), "EMAIL_VERIFICATION"))
                .isEmpty();
    }

    @Test
    @DisplayName("UserMailService 用真 repository：同一使用者有兩筆重設 token 時，寄出的只有最新一筆")
    void mailService_withRealRepo_sendsOnlyNewestToken() throws Exception {
        User user = createUser(EMAIL, UserStatus.ACTIVE);
        LocalDateTime exp = LocalDateTime.now().plusMinutes(10);
        insertToken(user, "PASSWORD_RESET", "reset-old", exp);
        insertToken(user, "PASSWORD_RESET", "reset-new", exp);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        boolean sent = mailService.sendPasswordResetEmail(
                new UserPasswordResetRequestedEvent(UUID.randomUUID(), user.getUuid()));

        assertThat(sent).isTrue();
        ArgumentCaptor<MimeMessage> mail = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat((String) mail.getValue().getContent()).contains("token=reset-new").doesNotContain("reset-old");
    }

    @Test
    @DisplayName("forgotPassword 連按兩次：建新 token 前先刪舊的，最後只剩一筆（最新＝唯一）")
    void forgotPasswordTwice_leavesSingleResetToken() {
        User user = createUser(EMAIL, UserStatus.ACTIVE);

        authService.forgotPassword(EMAIL);
        redis.delete(RedisKeyConstant.getForgotPwdMinKey(EMAIL));
        authService.forgotPassword(EMAIL);

        assertThat(countTokens(user, "PASSWORD_RESET")).isEqualTo(1L);
        verify(rabbitTemplate, times(2)).convertAndSend(any(String.class), any(String.class), any(Object.class));
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
