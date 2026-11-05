package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.user.config.UserRabbitMqConfig;
import dowob.xyz.blog.module.user.consumer.RegistrationAttemptNoticeConsumer;
import dowob.xyz.blog.module.user.model.event.RegistrationAttemptOnExistingEmailEvent;
import dowob.xyz.blog.module.user.model.event.UserRegisteredEvent;
import dowob.xyz.blog.module.user.repository.UserRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SEC-14／Q8 整合測試：註冊時 email 已存在 → 一律回成功並通知原信箱主人（真實 PostgreSQL + Redis）。
 *
 * <p>驗證端到端行為：重複 email 的註冊回應與新註冊相同（不拋錯）、DB 沒有新帳號、事件在回應之前已發出、
 * consumer 實際寄出通知信（mock {@code JavaMailSender}）、通知有 per-email 限流、username／nickname
 * 重複仍回明確錯誤碼，且兩條路徑都做了 bcrypt（{@code @MockitoSpyBean} 驗證）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("註冊 email 重複（SEC-14／Q8）整合測試")
class AuthRegisterExistingEmailIntegrationTest extends AbstractIntegrationTest {

    /** 本測試建立的 email 前綴，供清理使用 */
    private static final String PREFIX = "q8-";

    /** 原信箱主人的 email */
    private static final String OWNER_EMAIL = PREFIX + "owner@example.com";

    /** 測試用密碼 */
    private static final String PASSWORD = "Password123!";

    @Autowired
    private AuthService authService;

    @Autowired
    private RegistrationAttemptNoticeConsumer consumer;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    /** 對真實 BCrypt 加密器加 spy：驗證兩條路徑都執行了雜湊 */
    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    /** 每個測試前後都清掉本測試建立的資料與限流 key */
    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", PREFIX + "%");
        java.util.Set<String> keys = redis.keys(RedisKeyConstant.REGISTER_DUP_NOTICE_PREFIX + PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private void registerOwner() {
        authService.register(OWNER_EMAIL, PASSWORD, PREFIX + "owner", "Q8Owner");
        clearInvocations(rabbitTemplate, passwordEncoder);
    }

    private long countUsersByEmailPrefix() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email LIKE ?", Long.class, PREFIX + "%");
    }

    private RegistrationAttemptOnExistingEmailEvent captureNotice() {
        ArgumentCaptor<RegistrationAttemptOnExistingEmailEvent> captor =
                ArgumentCaptor.forClass(RegistrationAttemptOnExistingEmailEvent.class);
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(UserRabbitMqConfig.EXCHANGE), eq(UserRabbitMqConfig.ROUTING_KEY_REGISTRATION_ATTEMPT),
                captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("email 重複 → 回應與新註冊相同（不拋錯）、DB 無新帳號、發出通知事件（內容為原信箱主人）")
    void register_existingEmail_succeedsSilently_noNewAccount_publishesNotice() {
        registerOwner();

        authService.register(OWNER_EMAIL, PASSWORD, PREFIX + "attacker", "Q8Attacker");

        assertThat(countUsersByEmailPrefix()).as("只有原帳號").isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Long.class,
                PREFIX + "attacker")).as("嘗試者的 username 沒有被建立").isZero();
        RegistrationAttemptOnExistingEmailEvent event = captureNotice();
        assertThat(event.userUuid()).isEqualTo(
                jdbc.queryForObject("SELECT uuid FROM users WHERE email = ?", java.util.UUID.class, OWNER_EMAIL));
        verify(rabbitTemplate, times(0)).convertAndSend(anyString(), eq(UserRabbitMqConfig.ROUTING_KEY_REGISTERED),
                any(UserRegisteredEvent.class));
    }

    @Test
    @DisplayName("通知事件經 consumer 處理後，實際寄出一封通知信給原信箱主人（mock mail sender）")
    void register_existingEmail_consumerSendsNoticeMailToOwner() {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);
        registerOwner();
        authService.register(OWNER_EMAIL, PASSWORD, PREFIX + "attacker", "Q8Attacker");
        RegistrationAttemptOnExistingEmailEvent event = captureNotice();

        consumer.handleRegistrationAttempt(event);

        verify(mailSender, times(1)).send(message);
        assertThat(message).satisfies(m -> {
            assertThat(m.getAllRecipients()[0].toString()).isEqualTo(OWNER_EMAIL);
            assertThat(m.getSubject()).isEqualTo("Someone tried to register with your email address");
        });
    }

    @Test
    @DisplayName("通知限流：同一信箱窗口內重複嘗試只通知一次，Redis key 有 24 小時內的 TTL，兩次都回成功")
    void register_existingEmail_noticeIsRateLimitedPerEmail() {
        registerOwner();

        authService.register(OWNER_EMAIL, PASSWORD, PREFIX + "attacker1", "Q8Attacker1");
        authService.register(OWNER_EMAIL, PASSWORD, PREFIX + "attacker2", "Q8Attacker2");

        captureNotice();
        Long ttl = redis.getExpire(RedisKeyConstant.getRegisterDuplicateNoticeKey(OWNER_EMAIL), TimeUnit.MINUTES);
        assertThat(ttl).isNotNull().isBetween(1L, RedisKeyConstant.REGISTER_DUP_NOTICE_TTL_MINUTES);
        assertThat(countUsersByEmailPrefix()).isEqualTo(1L);
    }

    @Test
    @DisplayName("窗口過期（key 消失）後再次嘗試可再通知一次")
    void register_existingEmail_noticeAllowedAgainAfterWindowExpires() {
        registerOwner();
        authService.register(OWNER_EMAIL, PASSWORD, PREFIX + "attacker1", "Q8Attacker1");
        redis.delete(RedisKeyConstant.getRegisterDuplicateNoticeKey(OWNER_EMAIL));
        clearInvocations(rabbitTemplate);

        authService.register(OWNER_EMAIL, PASSWORD, PREFIX + "attacker2", "Q8Attacker2");

        captureNotice();
    }

    @Test
    @DisplayName("username 重複（email 為新）→ USERNAME_DUPLICATED；nickname 重複 → NICKNAME_DUPLICATED（維持明確提示）")
    void register_duplicateUsernameOrNickname_stillExplicitErrors() {
        registerOwner();

        assertThatThrownBy(() -> authService.register(PREFIX + "other@example.com", PASSWORD, PREFIX + "owner", "Q8Other"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UserErrorCode.USERNAME_DUPLICATED.getCode()));
        assertThatThrownBy(() -> authService.register(PREFIX + "other@example.com", PASSWORD, PREFIX + "other", "Q8Owner"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UserErrorCode.NICKNAME_DUPLICATED.getCode()));
    }

    @Test
    @DisplayName("email 與 username 同時重複 → USERNAME_DUPLICATED 且不通知（先查 username，避免直接洩漏 email 存在性）")
    void register_duplicateEmailAndUsername_explicitUsernameError_noNotice() {
        registerOwner();

        assertThatThrownBy(() -> authService.register(OWNER_EMAIL, PASSWORD, PREFIX + "owner", "Q8Other"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UserErrorCode.USERNAME_DUPLICATED.getCode()));

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("時間差：新註冊與重複 email 兩條路徑都呼叫了 passwordEncoder.encode（spy 驗證）")
    void register_bothPathsEncodePassword() {
        authService.register(OWNER_EMAIL, PASSWORD, PREFIX + "owner", "Q8Owner");
        verify(passwordEncoder, times(1)).encode(PASSWORD);

        clearInvocations(passwordEncoder);
        authService.register(OWNER_EMAIL, PASSWORD, PREFIX + "attacker", "Q8Attacker");
        verify(passwordEncoder, times(1)).encode(PASSWORD);
    }
}
