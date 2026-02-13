package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.VerificationToken;
import dowob.xyz.blog.module.user.model.dto.response.LoginResult;
import dowob.xyz.blog.module.user.repository.UserRepository;
import dowob.xyz.blog.module.user.repository.VerificationTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AuthService 單元測試
 *
 * <p>
 * 使用 Mockito 隔離所有外部依賴（Repository、JwtService、PasswordEncoder、Redis、RabbitMQ），
 * 驗證 AuthService 的核心業務邏輯：註冊、登入、登出、電子信箱驗證、忘記密碼與重設密碼。
 * </p>
 *
 * @author Yuan
 * @version 3.0
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    /** Mock：用戶資料存取 */
    @Mock
    private UserRepository userRepository;

    /** Mock：JWT 服務 */
    @Mock
    private JwtService jwtService;

    /** Mock：密碼加密器 */
    @Mock
    private PasswordEncoder passwordEncoder;

    /** Mock：Redis 操作模板 */
    @Mock
    private StringRedisTemplate redisTemplate;

    /** Mock：Redis Hash 操作 */
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    /** Mock：Redis Value 操作 */
    @Mock
    private ValueOperations<String, String> valueOperations;

    /** Mock：RabbitMQ 發送模板 */
    @Mock
    private RabbitTemplate rabbitTemplate;

    /** Mock：驗證 Token 資料存取 */
    @Mock
    private VerificationTokenRepository verificationTokenRepository;

    /** 受測物件，由 Mockito 自動注入所有 @Mock */
    @InjectMocks
    private AuthService authService;

    /** 測試用電子信箱 */
    private static final String TEST_EMAIL = "test@example.com";

    /** 測試用密碼 */
    private static final String TEST_PASSWORD = "password123";

    /** 測試用暱稱 */
    private static final String TEST_NICKNAME = "testUser";

    /** 測試用 Access Token 字串 */
    private static final String MOCK_ACCESS_TOKEN = "mock.access.token";

    /** 測試用 Refresh Token 字串 */
    private static final String MOCK_REFRESH_TOKEN = "mock.refresh.token";

    // =========================================================================
    // register 測試
    // =========================================================================

    /**
     * 驗證：使用全新 email 與 nickname 註冊，應成功儲存用戶並發布 RabbitMQ 事件。
     */
    @Test
    @DisplayName("register → 新 email 與 nickname → 應呼叫 save 儲存用戶")
    void register_withNewEmailAndNickname_shouldSaveUser() {
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(false);
        when(userRepository.existsByNickname(TEST_NICKNAME)).thenReturn(false);
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        User savedUser = buildActiveUser();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(verificationTokenRepository.save(any(VerificationToken.class))).thenReturn(new VerificationToken());

        authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_NICKNAME);

        verify(userRepository).save(any(User.class));
    }

    /**
     * 驗證：使用已存在的 email 註冊，應拋出 BusinessException。
     */
    @Test
    @DisplayName("register → 重複 email → 應拋出 BusinessException")
    void register_withDuplicateEmail_shouldThrowBusinessException() {
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_NICKNAME))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
    }

    /**
     * 驗證：使用已存在的 nickname 註冊，應拋出 BusinessException。
     */
    @Test
    @DisplayName("register → 重複 nickname → 應拋出 BusinessException")
    void register_withDuplicateNickname_shouldThrowBusinessException() {
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(false);
        when(userRepository.existsByNickname(TEST_NICKNAME)).thenReturn(true);

        assertThatThrownBy(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_NICKNAME))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
    }

    /**
     * 驗證：成功註冊後應發布 UserRegisteredEvent 至 RabbitMQ。
     */
    @Test
    @DisplayName("register → 成功後應發布 UserRegisteredEvent 至 RabbitMQ")
    void register_shouldPublishUserRegisteredEvent() {
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(false);
        when(userRepository.existsByNickname(TEST_NICKNAME)).thenReturn(false);
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        User savedUser = buildActiveUser();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(verificationTokenRepository.save(any(VerificationToken.class))).thenReturn(new VerificationToken());

        authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_NICKNAME);

        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), (Object) any());
    }

    /**
     * 驗證：register 儲存的 User 應具有正確的初始欄位：
     * status=PENDING_VERIFICATION、emailVerified=false、tokenVersion="v1"。
     */
    @Test
    @DisplayName("register → 儲存的 User 應為 PENDING_VERIFICATION 狀態，emailVerified=false，tokenVersion=v1")
    void register_savedUser_shouldHaveCorrectInitialFields() {
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(false);
        when(userRepository.existsByNickname(TEST_NICKNAME)).thenReturn(false);
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        User savedUser = buildActiveUser();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(verificationTokenRepository.save(any(VerificationToken.class))).thenReturn(new VerificationToken());

        authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_NICKNAME);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User captured = captor.getValue();
        assertThat(captured.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
        assertThat(captured.isEmailVerified()).isFalse();
        assertThat(captured.getTokenVersion()).isEqualTo("v1");
    }

    // =========================================================================
    // login 測試
    // =========================================================================

    /**
     * 驗證：使用正確帳密登入，應回傳包含 accessToken 與 refreshToken 的 LoginResult，
     * 並將版本資訊寫入 Redis Hash 以及 Refresh Token 寫入 Redis String。
     */
    @Test
    @DisplayName("login → 正確帳密 → 應回傳 LoginResult 且 Redis 有版本與 Refresh Token 快取")
    void login_withValidCredentials_shouldReturnLoginResult_andCacheInRedis() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateAccessToken(anyLong(), anyString(), anyString())).thenReturn(MOCK_ACCESS_TOKEN);
        when(jwtService.generateRefreshToken(anyLong())).thenReturn(MOCK_REFRESH_TOKEN);

        LoginResult result = authService.login(TEST_EMAIL, TEST_PASSWORD);

        assertThat(result.accessToken()).isEqualTo(MOCK_ACCESS_TOKEN);
        assertThat(result.refreshToken()).isEqualTo(MOCK_REFRESH_TOKEN);
        verify(hashOperations, times(2)).put(anyString(), anyString(), anyString());
        verify(valueOperations).set(anyString(), anyString(), anyLong(), any());
    }

    /**
     * 驗證：login 應回傳包含 accessToken 與 refreshToken 的 LoginResult。
     */
    @Test
    @DisplayName("login → 應回傳包含 accessToken 與 refreshToken 的 LoginResult")
    void login_shouldReturnLoginResultWithBothTokens() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateAccessToken(anyLong(), anyString(), anyString())).thenReturn(MOCK_ACCESS_TOKEN);
        when(jwtService.generateRefreshToken(anyLong())).thenReturn(MOCK_REFRESH_TOKEN);

        LoginResult result = authService.login(TEST_EMAIL, TEST_PASSWORD);

        assertThat(result).isNotNull();
        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();
    }

    /**
     * 驗證：密碼錯誤時登入，應拋出 BusinessException。
     */
    @Test
    @DisplayName("login → 密碼錯誤 → 應拋出 BusinessException")
    void login_withWrongPassword_shouldThrowBusinessException() {
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(TEST_EMAIL, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 驗證：帳號已停權時登入，應拋出 BusinessException。
     */
    @Test
    @DisplayName("login → 帳號停權 → 應拋出 BusinessException")
    void login_withSuspendedAccount_shouldThrowBusinessException() {
        User mockUser = buildActiveUser();
        mockUser.setStatus(UserStatus.SUSPENDED);
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(TEST_EMAIL, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 驗證：帳號為 PENDING_VERIFICATION 時登入，應拋出 EMAIL_NOT_VERIFIED BusinessException。
     */
    @Test
    @DisplayName("login → PENDING_VERIFICATION 帳號 → 應拋出 EMAIL_NOT_VERIFIED BusinessException")
    void login_withPendingVerificationAccount_shouldThrowEmailNotVerified() {
        User mockUser = buildActiveUser();
        mockUser.setStatus(UserStatus.PENDING_VERIFICATION);
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(TEST_EMAIL, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(UserErrorCode.EMAIL_NOT_VERIFIED.getCode());
                });
    }

    /**
     * 驗證：DELETED 帳號登入，應拋出 ACCOUNT_SUSPENDED BusinessException。
     */
    @Test
    @DisplayName("login → DELETED 帳號 → 應拋出 ACCOUNT_SUSPENDED BusinessException")
    void login_withDeletedAccount_shouldThrowAccountSuspended() {
        User mockUser = buildActiveUser();
        mockUser.setStatus(UserStatus.DELETED);
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(TEST_EMAIL, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.ACCOUNT_SUSPENDED.getCode()));
    }

    /**
     * 驗證：email 與 nickname 均不存在時登入，應拋出 USER_PASSWORD_ERROR BusinessException。
     */
    @Test
    @DisplayName("login → email 與 nickname 均不存在 → 應拋出 USER_PASSWORD_ERROR BusinessException")
    void login_withNonExistentUser_shouldThrowUserPasswordError() {
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.empty());
        when(userRepository.findByNickname(TEST_EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(TEST_EMAIL, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.USER_PASSWORD_ERROR.getCode()));
    }

    /**
     * 驗證：使用暱稱登入，應能找到用戶並成功回傳 Token。
     */
    @Test
    @DisplayName("login → 使用 Username 登入 → 應成功回傳 Token")
    void login_withUsername_shouldReturnToken() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_NICKNAME)).thenReturn(Optional.empty());
        when(userRepository.findByNickname(TEST_NICKNAME)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateAccessToken(anyLong(), anyString(), anyString())).thenReturn(MOCK_ACCESS_TOKEN);
        when(jwtService.generateRefreshToken(anyLong())).thenReturn(MOCK_REFRESH_TOKEN);

        LoginResult result = authService.login(TEST_NICKNAME, TEST_PASSWORD);

        assertThat(result.accessToken()).isEqualTo(MOCK_ACCESS_TOKEN);
    }

    // =========================================================================
    // logout 測試
    // =========================================================================

    /**
     * 驗證：logout 應刪除 Redis 中的 Refresh Token。
     */
    @Test
    @DisplayName("logout → 應清除 Redis 中的 Refresh Token")
    void logout_shouldClearRefreshTokenInRedis() {
        authService.logout(1L);

        verify(redisTemplate).delete(anyString());
    }

    // =========================================================================
    // verifyEmail 測試
    // =========================================================================

    /**
     * 驗證：使用有效 Token 驗證信箱，應將用戶狀態設為 ACTIVE、emailVerified=true 並刪除 Token。
     */
    @Test
    @DisplayName("verifyEmail → 有效 Token → 應啟用帳號並刪除 Token")
    void verifyEmail_withValidToken_shouldActivateUserAndDeleteToken() {
        String tokenStr = "valid-email-token";
        VerificationToken emailToken = buildEmailVerificationToken(tokenStr, LocalDateTime.now().plusHours(24));
        User mockUser = buildActiveUser();
        mockUser.setStatus(UserStatus.PENDING_VERIFICATION);
        mockUser.setEmailVerified(false);

        when(verificationTokenRepository.findByTokenAndType(tokenStr, "EMAIL_VERIFICATION"))
                .thenReturn(Optional.of(emailToken));
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockUser));

        authService.verifyEmail(tokenStr);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(captor.getValue().isEmailVerified()).isTrue();
        verify(verificationTokenRepository).delete(emailToken);
    }

    /**
     * 驗證：使用不存在的 Token 驗證信箱，應拋出 TOKEN_INVALID BusinessException。
     */
    @Test
    @DisplayName("verifyEmail → Token 不存在 → 應拋出 TOKEN_INVALID BusinessException")
    void verifyEmail_withNonExistentToken_shouldThrowTokenInvalid() {
        String tokenStr = "non-existent-token";
        when(verificationTokenRepository.findByTokenAndType(tokenStr, "EMAIL_VERIFICATION"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verifyEmail(tokenStr))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.TOKEN_INVALID.getCode()));
    }

    /**
     * 驗證：使用已過期的 Token 驗證信箱，應拋出 TOKEN_INVALID BusinessException。
     */
    @Test
    @DisplayName("verifyEmail → Token 已過期 → 應拋出 TOKEN_INVALID BusinessException")
    void verifyEmail_withExpiredToken_shouldThrowTokenInvalid() {
        String tokenStr = "expired-email-token";
        VerificationToken expiredToken = buildEmailVerificationToken(tokenStr, LocalDateTime.now().minusHours(1));
        when(verificationTokenRepository.findByTokenAndType(tokenStr, "EMAIL_VERIFICATION"))
                .thenReturn(Optional.of(expiredToken));

        assertThatThrownBy(() -> authService.verifyEmail(tokenStr))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.TOKEN_INVALID.getCode()));
    }

    // =========================================================================
    // forgotPassword 測試
    // =========================================================================

    /**
     * 驗證：forgotPassword 應儲存驗證 Token 並發布事件至 RabbitMQ。
     */
    @Test
    @DisplayName("forgotPassword → 信箱存在 → 應儲存驗證 Token 並發布事件")
    void forgotPassword_shouldSaveVerificationTokenAndPublishEvent() {
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(verificationTokenRepository.save(any(VerificationToken.class))).thenReturn(new VerificationToken());

        authService.forgotPassword(TEST_EMAIL);

        verify(verificationTokenRepository).save(any(VerificationToken.class));
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), (Object) any());
    }

    /**
     * 驗證：forgotPassword 對不存在的信箱應靜默忽略，不拋出例外。
     */
    @Test
    @DisplayName("forgotPassword → 信箱不存在 → 應靜默忽略，不發布任何事件")
    void forgotPassword_withUnknownEmail_shouldDoNothing() {
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.empty());

        authService.forgotPassword(TEST_EMAIL);

        verify(verificationTokenRepository, never()).save(any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), (Object) any());
    }

    // =========================================================================
    // resetPassword 測試
    // =========================================================================

    /**
     * 驗證：resetPassword 使用有效 Token 應更新密碼並遞增 tokenVersion。
     */
    @Test
    @DisplayName("resetPassword → 有效 Token → 應更新密碼並遞增 tokenVersion")
    void resetPassword_withValidToken_shouldUpdatePassword() {
        String tokenStr = "valid-reset-token";
        VerificationToken resetToken = buildResetToken(tokenStr, LocalDateTime.now().plusMinutes(10));
        User mockUser = buildActiveUser();

        when(verificationTokenRepository.findByTokenAndType(tokenStr, "PASSWORD_RESET"))
                .thenReturn(Optional.of(resetToken));
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.encode(anyString())).thenReturn("newEncodedPassword");

        authService.resetPassword(tokenStr, "newPassword123");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getTokenVersion()).isEqualTo("v2");
        verify(verificationTokenRepository).delete(resetToken);
    }

    /**
     * 驗證：resetPassword 使用已過期的 Token 應拋出 BusinessException。
     */
    @Test
    @DisplayName("resetPassword → 過期 Token → 應拋出 BusinessException")
    void resetPassword_withExpiredToken_shouldThrowBusinessException() {
        String tokenStr = "expired-token";
        VerificationToken expiredToken = buildResetToken(tokenStr, LocalDateTime.now().minusMinutes(1));

        when(verificationTokenRepository.findByTokenAndType(tokenStr, "PASSWORD_RESET"))
                .thenReturn(Optional.of(expiredToken));

        assertThatThrownBy(() -> authService.resetPassword(tokenStr, "newPassword"))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 驗證：resetPassword 使用不存在的 Token 應拋出 TOKEN_INVALID BusinessException。
     */
    @Test
    @DisplayName("resetPassword → Token 不存在 → 應拋出 TOKEN_INVALID BusinessException")
    void resetPassword_withNonExistentToken_shouldThrowTokenInvalid() {
        String tokenStr = "non-existent-reset-token";
        when(verificationTokenRepository.findByTokenAndType(tokenStr, "PASSWORD_RESET"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resetPassword(tokenStr, "newPassword"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.TOKEN_INVALID.getCode()));
    }

    // =========================================================================
    // incrementVersion 測試
    // =========================================================================

    /**
     * 驗證：incrementVersion 應正確處理多位數進位（v9 → v10）。
     */
    @Test
    @DisplayName("incrementVersion → v9 → 應回傳 v10（多位數進位）")
    void incrementVersion_v9_shouldReturnV10() {
        assertThat(AuthService.incrementVersion("v9")).isEqualTo("v10");
    }

    /**
     * 驗證：incrementVersion 面對格式異常輸入時應防禦性地回傳 v1。
     */
    @Test
    @DisplayName("incrementVersion → 格式異常（null / 非v前綴 / 非數字） → 應回傳 v1（防禦性處理）")
    void incrementVersion_invalidFormat_shouldReturnV1() {
        assertThat(AuthService.incrementVersion(null)).isEqualTo("v1");
        assertThat(AuthService.incrementVersion("invalid")).isEqualTo("v1");
        assertThat(AuthService.incrementVersion("vX")).isEqualTo("v1");
    }

    // =========================================================================
    // 測試輔助方法
    // =========================================================================

    /**
     * 建立一個 ACTIVE 狀態的測試用 User 物件。
     *
     * @return 已設定基本欄位的 User 實體
     */
    private User buildActiveUser() {
        User user = new User();
        user.setId(1L);
        user.setEmail(TEST_EMAIL);
        user.setPasswordHash("encodedPassword");
        user.setNickname(TEST_NICKNAME);
        user.setRole(Role.USER);
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion("v1");
        return user;
    }

    /**
     * 建立一個密碼重設 Token 物件。
     *
     * @param tokenStr  Token 字串
     * @param expiresAt 過期時間
     * @return VerificationToken 實體
     */
    private VerificationToken buildResetToken(String tokenStr, LocalDateTime expiresAt) {
        VerificationToken token = new VerificationToken();
        token.setId(1L);
        token.setUserId(1L);
        token.setToken(tokenStr);
        token.setType("PASSWORD_RESET");
        token.setExpiresAt(expiresAt);
        return token;
    }

    /**
     * 建立一個電子信箱驗證 Token 物件。
     *
     * @param tokenStr  Token 字串
     * @param expiresAt 過期時間
     * @return VerificationToken 實體
     */
    private VerificationToken buildEmailVerificationToken(String tokenStr, LocalDateTime expiresAt) {
        VerificationToken token = new VerificationToken();
        token.setId(2L);
        token.setUserId(1L);
        token.setToken(tokenStr);
        token.setType("EMAIL_VERIFICATION");
        token.setExpiresAt(expiresAt);
        return token;
    }
}
