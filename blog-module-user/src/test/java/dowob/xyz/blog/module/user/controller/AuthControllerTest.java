package dowob.xyz.blog.module.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.user.model.dto.request.ForgotPasswordRequest;
import dowob.xyz.blog.module.user.model.dto.request.LoginRequest;
import dowob.xyz.blog.module.user.model.dto.request.RegisterRequest;
import dowob.xyz.blog.module.user.model.dto.request.ResetPasswordRequest;
import dowob.xyz.blog.module.user.model.dto.response.LoginResult;
import dowob.xyz.blog.module.user.service.AuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import dowob.xyz.blog.infrastructure.config.SecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthController 單元測試（Web 層）
 *
 * <p>
 * 使用 {@code @WebMvcTest} 僅載入 Web 層，透過 MockMvc 驗證各端點的
 * HTTP 請求處理行為，包含請求驗證、回應結構、Cookie 設定與例外映射。
 * 所有服務依賴均以 {@code @MockitoBean} 替代，不觸及業務邏輯。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    /** MockMvc，用於模擬 HTTP 請求 */
    @Autowired
    private MockMvc mockMvc;

    /** Jackson 物件序列化工具 */
    @Autowired
    private ObjectMapper objectMapper;

    /** Mock：認證業務服務 */
    @MockitoBean
    private AuthService authService;

    /** Mock：JWT 服務（AuthController + JwtAuthenticationFilter 所需） */
    @MockitoBean
    private JwtService jwtService;

    /** Mock：Redis 模板（AuthController refresh + JwtAuthenticationFilter 所需） */
    @MockitoBean
    private StringRedisTemplate redisTemplate;

    /** Mock：用戶認證服務（JwtAuthenticationFilter 所需） */
    @MockitoBean
    private UserAuthService userAuthService;

    /** 測試用電子信箱 */
    private static final String TEST_EMAIL = "test@example.com";

    /** 測試用密碼（符合密碼複雜度規則：小寫+大寫+數字+特殊字元） */
    private static final String TEST_PASSWORD = "Password123!";

    /** 測試用用戶名 */
    private static final String TEST_USERNAME = "testuser";

    /** 測試用暱稱 */
    private static final String TEST_NICKNAME = "testUser";

    // =========================================================================
    // POST /api/v1/auth/register 測試
    // =========================================================================

    /**
     * 驗證：合法請求應呼叫 authService.register() 並回傳 200 成功回應。
     */
    @Test
    @DisplayName("POST /register → 合法請求 → 應回傳 200 成功回應")
    void register_validRequest_shouldReturn200() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(TEST_EMAIL);
        request.setPassword(TEST_PASSWORD);
        request.setUsername(TEST_USERNAME);
        request.setNickname(TEST_NICKNAME);

        doNothing().when(authService).register(anyString(), anyString(), anyString(), anyString(), nullable(String.class));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    /**
     * 驗證：缺少必填欄位時應回傳驗證錯誤回應。
     */
    @Test
    @DisplayName("POST /register → 缺少 email → 應回傳驗證錯誤")
    void register_missingEmail_shouldReturnValidationError() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setPassword(TEST_PASSWORD);
        request.setUsername(TEST_USERNAME);
        request.setNickname(TEST_NICKNAME);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    /**
     * 驗證：缺少 username 欄位時應回傳驗證錯誤回應。
     */
    @Test
    @DisplayName("POST /register → 缺少 username → 應回傳驗證錯誤")
    void register_missingUsername_shouldReturnValidationError() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(TEST_EMAIL);
        request.setPassword(TEST_PASSWORD);
        request.setNickname(TEST_NICKNAME);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    /**
     * 驗證：重複 email 時業務例外應映射為對應的錯誤碼。
     */
    @Test
    @DisplayName("POST /register → 重複 email → 應回傳 EMAIL_DUPLICATED 錯誤碼")
    void register_duplicateEmail_shouldReturnEmailDuplicatedError() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(TEST_EMAIL);
        request.setPassword(TEST_PASSWORD);
        request.setUsername(TEST_USERNAME);
        request.setNickname(TEST_NICKNAME);

        doThrow(new BusinessException(UserErrorCode.EMAIL_DUPLICATED))
                .when(authService).register(anyString(), anyString(), anyString(), anyString(), nullable(String.class));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.EMAIL_DUPLICATED.getCode()));
    }

    // =========================================================================
    // POST /api/v1/auth/login 測試
    // =========================================================================

    /**
     * 驗證：登入成功應回傳 accessToken 且回應中設定 refreshToken Cookie。
     */
    @Test
    @DisplayName("POST /login → 正確帳密 → 應回傳 accessToken 且設定 refreshToken Cookie")
    void login_validCredentials_shouldReturnAccessTokenAndSetCookie() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setIdentifier(TEST_EMAIL);
        request.setPassword(TEST_PASSWORD);

        when(authService.login(anyString(), anyString(), nullable(String.class)))
                .thenReturn(new LoginResult("mock.access.token", "mock.refresh.token"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.accessToken").value("mock.access.token"))
                .andExpect(header().exists("Set-Cookie"));
    }

    /**
     * 驗證：帳號鎖定時應回傳 ACCOUNT_LOCKED 錯誤碼。
     */
    @Test
    @DisplayName("POST /login → 帳號鎖定 → 應回傳 ACCOUNT_LOCKED 錯誤碼")
    void login_accountLocked_shouldReturnAccountLockedError() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setIdentifier(TEST_EMAIL);
        request.setPassword(TEST_PASSWORD);

        when(authService.login(anyString(), anyString(), nullable(String.class)))
                .thenThrow(new BusinessException(UserErrorCode.ACCOUNT_LOCKED));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.ACCOUNT_LOCKED.getCode()));
    }

    /**
     * 驗證：密碼錯誤時應回傳 USER_PASSWORD_ERROR 錯誤碼。
     */
    @Test
    @DisplayName("POST /login → 密碼錯誤 → 應回傳 USER_PASSWORD_ERROR 錯誤碼")
    void login_wrongPassword_shouldReturnUserPasswordError() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setIdentifier(TEST_EMAIL);
        request.setPassword("wrongPassword");

        when(authService.login(anyString(), anyString(), nullable(String.class)))
                .thenThrow(new BusinessException(UserErrorCode.USER_PASSWORD_ERROR));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.USER_PASSWORD_ERROR.getCode()));
    }

    // =========================================================================
    // POST /api/v1/auth/refresh 測試
    // =========================================================================

    /**
     * 驗證：缺少 refreshToken Cookie 時應回傳 401 未認證錯誤。
     */
    @Test
    @DisplayName("POST /refresh → 缺少 refreshToken Cookie → 應回傳 401 未認證錯誤")
    void refresh_missingCookie_shouldReturnUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0005"));
    }

    /**
     * 驗證：refreshToken 驗證失敗時應回傳 401 未認證錯誤。
     */
    @Test
    @DisplayName("POST /refresh → Token 驗證失敗 → 應回傳 401 未認證錯誤")
    void refresh_invalidToken_shouldReturnUnauthenticated() throws Exception {
        when(jwtService.validateRefreshToken("invalid.token")).thenReturn(false);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refreshToken", "invalid.token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0005"));
    }

    // =========================================================================
    // POST /api/v1/auth/logout 測試
    // =========================================================================

    /**
     * 驗證：已登入用戶登出應成功，並清除 Refresh Token Cookie。
     */
    @Test
    @DisplayName("POST /logout → 已登入用戶 → 應回傳 200 並清除 Cookie")
    void logout_authenticatedUser_shouldReturn200AndClearCookie() throws Exception {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                1L, null, List.of(new SimpleGrantedAuthority(Role.USER.getSpringSecurityRole())));

        doNothing().when(authService).logout(anyLong(), any());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(header().exists("Set-Cookie"));
    }

    // =========================================================================
    // POST /api/v1/auth/verify-email 測試
    // =========================================================================

    /**
     * 驗證：有效的驗證 Token 應回傳 200 成功回應。
     */
    @Test
    @DisplayName("POST /verify-email → 有效 Token → 應回傳 200 成功回應")
    void verifyEmail_validToken_shouldReturn200() throws Exception {
        doNothing().when(authService).verifyEmail("valid-token");

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "valid-token"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    /**
     * 驗證：無效的驗證 Token 應回傳 TOKEN_INVALID 錯誤碼。
     */
    @Test
    @DisplayName("POST /verify-email → 無效 Token → 應回傳 TOKEN_INVALID 錯誤碼")
    void verifyEmail_invalidToken_shouldReturnTokenInvalid() throws Exception {
        doThrow(new BusinessException(UserErrorCode.TOKEN_INVALID))
                .when(authService).verifyEmail("invalid-token");

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "invalid-token"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.TOKEN_INVALID.getCode()));
    }

    /**
     * 驗證：憑證不得經由 query string 傳遞（security.md 原則 8）。
     *
     * <p>
     * 舊契約為 {@code GET /verify-email?token=...}，token 會進入 access log 與
     * 瀏覽器歷史。此測試作為端點形狀的迴歸守衛，避免日後被改回 GET。
     * </p>
     */
    @Test
    @DisplayName("GET /verify-email?token= → 舊的 query string 形狀應不再被接受")
    void verifyEmail_getWithTokenInQueryString_shouldNotBeAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/auth/verify-email")
                        .param("token", "valid-token"))
                .andExpect(status().isMethodNotAllowed());

        verify(authService, never()).verifyEmail(any());
    }

    /**
     * 驗證：正確的信箱驗證碼應回傳 200 成功回應。
     */
    @Test
    @DisplayName("POST /verify-email-code → 正確驗證碼 → 應回傳 200 成功回應")
    void verifyEmailCode_validCode_shouldReturn200() throws Exception {
        doNothing().when(authService).verifyEmailCode(TEST_EMAIL, "123456");

        mockMvc.perform(post("/api/v1/auth/verify-email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "code": "123456"
                                }
                                """.formatted(TEST_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    /**
     * 驗證：驗證碼格式不正確時應回傳驗證錯誤。
     */
    @Test
    @DisplayName("POST /verify-email-code → 非 6 位數驗證碼 → 應回傳驗證錯誤")
    void verifyEmailCode_invalidCodeFormat_shouldReturnValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/verify-email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "code": "abc"
                                }
                                """.formatted(TEST_EMAIL)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    // =========================================================================
    // POST /api/v1/auth/forgot-password 測試
    // =========================================================================

    /**
     * 驗證：合法請求應回傳 200（無論信箱是否存在，一律成功以防資訊洩漏）。
     */
    @Test
    @DisplayName("POST /forgot-password → 合法請求 → 應回傳 200（不洩露信箱是否存在）")
    void forgotPassword_validRequest_shouldReturn200() throws Exception {
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        request.setEmail(TEST_EMAIL);

        doNothing().when(authService).forgotPassword(anyString());

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    /**
     * 驗證：信箱格式不正確時應回傳驗證錯誤。
     */
    @Test
    @DisplayName("POST /forgot-password → 無效信箱格式 → 應回傳驗證錯誤")
    void forgotPassword_invalidEmailFormat_shouldReturnValidationError() throws Exception {
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        request.setEmail("not-an-email");

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    // =========================================================================
    // POST /api/v1/auth/resend-verification 測試
    // =========================================================================

    /**
     * 驗證：合法請求應回傳 200（不洩露信箱或帳號狀態）。
     */
    @Test
    @DisplayName("POST /resend-verification → 合法請求 → 應回傳 200")
    void resendVerification_validRequest_shouldReturn200() throws Exception {
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        request.setEmail(TEST_EMAIL);

        doNothing().when(authService).resendVerification(anyString());

        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    // =========================================================================
    // POST /api/v1/auth/reset-password 測試
    // =========================================================================

    /**
     * 驗證：合法的重設密碼請求應回傳 200 成功回應。
     */
    @Test
    @DisplayName("POST /reset-password → 合法請求 → 應回傳 200 成功回應")
    void resetPassword_validRequest_shouldReturn200() throws Exception {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setToken("valid-reset-token");
        request.setNewPassword("NewPassword123!");

        doNothing().when(authService).resetPassword(anyString(), anyString());

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    /**
     * 驗證：Token 不存在時應回傳 TOKEN_INVALID 錯誤碼。
     */
    @Test
    @DisplayName("POST /reset-password → Token 不存在 → 應回傳 TOKEN_INVALID 錯誤碼")
    void resetPassword_invalidToken_shouldReturnTokenInvalid() throws Exception {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setToken("bad-token");
        request.setNewPassword("NewPassword123!");

        doThrow(new BusinessException(UserErrorCode.TOKEN_INVALID))
                .when(authService).resetPassword(anyString(), anyString());

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.TOKEN_INVALID.getCode()));
    }

    /**
     * 驗證：新密碼長度不足時應回傳驗證錯誤。
     */
    @Test
    @DisplayName("POST /reset-password → 新密碼太短 → 應回傳驗證錯誤")
    void resetPassword_passwordTooShort_shouldReturnValidationError() throws Exception {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setToken("valid-token");
        request.setNewPassword("abc");

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    // =========================================================================
    // POST /api/v1/auth/register 補充測試
    // =========================================================================

    /**
     * 驗證：信箱格式不正確時應回傳驗證錯誤。
     */
    @Test
    @DisplayName("POST /register → email 格式錯誤 → 應回傳驗證錯誤")
    void register_invalidEmailFormat_shouldReturnValidationError() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("not-an-email");
        request.setPassword(TEST_PASSWORD);
        request.setUsername(TEST_USERNAME);
        request.setNickname(TEST_NICKNAME);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    /**
     * 驗證：暱稱重複時業務例外應映射為對應的錯誤碼。
     */
    @Test
    @DisplayName("POST /register → 暱稱重複 → 應回傳 NICKNAME_DUPLICATED 錯誤碼")
    void register_duplicateNickname_shouldReturnNicknameDuplicatedError() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(TEST_EMAIL);
        request.setPassword(TEST_PASSWORD);
        request.setUsername(TEST_USERNAME);
        request.setNickname(TEST_NICKNAME);

        doThrow(new BusinessException(UserErrorCode.NICKNAME_DUPLICATED))
                .when(authService).register(anyString(), anyString(), anyString(), anyString(), nullable(String.class));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.NICKNAME_DUPLICATED.getCode()));
    }

    /**
     * 驗證：用戶名重複時業務例外應映射為對應的錯誤碼。
     */
    @Test
    @DisplayName("POST /register → 用戶名重複 → 應回傳 USERNAME_DUPLICATED 錯誤碼")
    void register_duplicateUsername_shouldReturnUsernameDuplicatedError() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(TEST_EMAIL);
        request.setPassword(TEST_PASSWORD);
        request.setUsername(TEST_USERNAME);
        request.setNickname(TEST_NICKNAME);

        doThrow(new BusinessException(UserErrorCode.USERNAME_DUPLICATED))
                .when(authService).register(anyString(), anyString(), anyString(), anyString(), nullable(String.class));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.USERNAME_DUPLICATED.getCode()));
    }

    // =========================================================================
    // POST /api/v1/auth/login 補充測試
    // =========================================================================

    /**
     * 驗證：identifier 為空時應回傳驗證錯誤。
     */
    @Test
    @DisplayName("POST /login → 缺少 identifier → 應回傳驗證錯誤")
    void login_missingIdentifier_shouldReturnValidationError() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setPassword(TEST_PASSWORD);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    /**
     * 驗證：用戶不存在時應回傳 USER_NOT_FOUND 錯誤碼。
     */
    @Test
    @DisplayName("POST /login → 用戶不存在 → 應回傳 USER_NOT_FOUND 錯誤碼")
    void login_userNotFound_shouldReturnUserNotFoundError() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setIdentifier("unknown@example.com");
        request.setPassword(TEST_PASSWORD);

        when(authService.login(anyString(), anyString(), nullable(String.class)))
                .thenThrow(new BusinessException(UserErrorCode.USER_NOT_FOUND));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.USER_NOT_FOUND.getCode()));
    }

    /**
     * 驗證：電子信箱尚未驗證時應回傳 EMAIL_NOT_VERIFIED 錯誤碼。
     */
    @Test
    @DisplayName("POST /login → 電子信箱未驗證 → 應回傳 EMAIL_NOT_VERIFIED 錯誤碼")
    void login_emailNotVerified_shouldReturnEmailNotVerifiedError() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setIdentifier(TEST_EMAIL);
        request.setPassword(TEST_PASSWORD);

        when(authService.login(anyString(), anyString(), nullable(String.class)))
                .thenThrow(new BusinessException(UserErrorCode.EMAIL_NOT_VERIFIED));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.EMAIL_NOT_VERIFIED.getCode()));
    }

    // =========================================================================
    // POST /api/v1/auth/refresh 補充測試
    // =========================================================================

    /**
     * 驗證：Refresh Token 存在但不在 Redis ZSet 中（已撤銷）應回傳 401 未認證錯誤。
     */
    @Test
    @DisplayName("POST /refresh → Token 不在 Redis ZSet 中（已撤銷）→ 應回傳 401 未認證錯誤")
    void refresh_tokenNotInRedis_shouldReturnUnauthenticated() throws Exception {
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(jwtService.validateRefreshToken("valid.refresh.token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid.refresh.token")).thenReturn("1");
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.score(anyString(), anyString())).thenReturn(null);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refreshToken", "valid.refresh.token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0005"));
    }

    /**
     * 驗證：帳號已被停權時 refresh 應回傳 ACCOUNT_SUSPENDED 錯誤碼。
     */
    @Test
    @DisplayName("POST /refresh → 帳號已停權 → 應回傳 ACCOUNT_SUSPENDED 錯誤碼")
    void refresh_accountSuspended_shouldReturnAccountSuspendedError() throws Exception {
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);

        when(jwtService.validateRefreshToken("valid.refresh.token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid.refresh.token")).thenReturn("1");
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.score(anyString(), anyString())).thenReturn(1.0);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.get(anyString(), eq("version"))).thenReturn("v1");
        when(hashOps.get(anyString(), eq("status"))).thenReturn("SUSPENDED");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refreshToken", "valid.refresh.token")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.ACCOUNT_SUSPENDED.getCode()));
    }

    /**
     * 驗證：Token 有效且帳號正常時 refresh 應回傳新的 Access Token（role 與 version 皆有值）。
     */
    @Test
    @DisplayName("POST /refresh → Token 有效且帳號正常（role/version 皆存在）→ 應回傳新 Access Token")
    void refresh_validToken_shouldReturnNewAccessToken() throws Exception {
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);

        when(jwtService.validateRefreshToken("valid.refresh.token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid.refresh.token")).thenReturn("1");
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.score(anyString(), anyString())).thenReturn(1.0);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.get(anyString(), eq("version"))).thenReturn("v1");
        when(hashOps.get(anyString(), eq("status"))).thenReturn("ACTIVE");
        when(hashOps.get(anyString(), eq("role"))).thenReturn("USER");
        when(jwtService.generateAccessToken(anyLong(), anyString(), anyString()))
                .thenReturn("new.access.token");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refreshToken", "valid.refresh.token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.accessToken").value("new.access.token"));
    }

    /**
     * 驗證：Token 有效但 Redis 中 role 與 version 皆為 null 時應使用預設值（"USER"、"v1"）並正常回傳。
     */
    @Test
    @DisplayName("POST /refresh → role/version 皆為 null → 應使用預設值並回傳新 Access Token")
    void refresh_nullRoleAndVersion_shouldUseDefaultsAndReturnNewAccessToken() throws Exception {
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);

        when(jwtService.validateRefreshToken("valid.refresh.token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid.refresh.token")).thenReturn("1");
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.score(anyString(), anyString())).thenReturn(1.0);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.get(anyString(), eq("version"))).thenReturn(null);
        when(hashOps.get(anyString(), eq("status"))).thenReturn("ACTIVE");
        when(hashOps.get(anyString(), eq("role"))).thenReturn(null);
        when(jwtService.generateAccessToken(eq(1L), eq("USER"), eq("v1")))
                .thenReturn("new.access.token.defaults");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refreshToken", "valid.refresh.token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.accessToken").value("new.access.token.defaults"));
    }

    // =========================================================================
    // POST /api/v1/auth/logout 補充測試
    // =========================================================================

    /**
     * 驗證：未認證用戶呼叫登出時，因 logout 已收窄為 authenticated，
     * 應回傳 401 Unauthorized。
     */
    @Test
    @DisplayName("POST /logout → 未認證用戶 → 應回傳 401（logout 需認證）")
    void logout_unauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // POST /api/v1/auth/verify-email 補充測試
    // =========================================================================

    /**
     * 驗證：token 為空白時應由 {@code @Valid} 攔下並回傳 HTTP 400。
     */
    @Test
    @DisplayName("POST /verify-email → token 空白 → 應回傳驗證錯誤")
    void verifyEmail_blankToken_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));

        verify(authService, never()).verifyEmail(any());
    }

    // =========================================================================
    // POST /api/v1/auth/resend-verification 補充測試
    // =========================================================================

    /**
     * 驗證：信箱格式不正確時應回傳驗證錯誤。
     */
    @Test
    @DisplayName("POST /resend-verification → 無效信箱格式 → 應回傳驗證錯誤")
    void resendVerification_invalidEmailFormat_shouldReturnValidationError() throws Exception {
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        request.setEmail("not-valid-email");

        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    /**
     * 驗證：缺少 email 欄位時應回傳驗證錯誤。
     */
    @Test
    @DisplayName("POST /resend-verification → 缺少 email → 應回傳驗證錯誤")
    void resendVerification_missingEmail_shouldReturnValidationError() throws Exception {
        ForgotPasswordRequest request = new ForgotPasswordRequest();

        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    // =========================================================================
    // POST /api/v1/auth/reset-password 補充測試
    // =========================================================================

    /**
     * 驗證：缺少 token 欄位時應回傳驗證錯誤。
     */
    @Test
    @DisplayName("POST /reset-password → 缺少 token → 應回傳驗證錯誤")
    void resetPassword_missingToken_shouldReturnValidationError() throws Exception {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setNewPassword("NewPassword123!");

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    /**
     * 驗證：缺少 forgot-password email 欄位時應回傳驗證錯誤。
     */
    @Test
    @DisplayName("POST /forgot-password → 缺少 email → 應回傳驗證錯誤")
    void forgotPassword_missingEmail_shouldReturnValidationError() throws Exception {
        ForgotPasswordRequest request = new ForgotPasswordRequest();

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    // =========================================================================
    // 密碼複雜度驗證測試
    // =========================================================================

    @Test
    @DisplayName("POST /register → 密碼只含英文字母（無數字）→ 應回傳 400 驗證錯誤")
    void register_passwordWithoutNumber_shouldReturn400() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(TEST_EMAIL);
        request.setPassword("Abcdefghij");
        request.setUsername(TEST_USERNAME);
        request.setNickname(TEST_NICKNAME);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    @DisplayName("POST /register → 密碼有字母和數字但僅 7 字元 → 應回傳 400 驗證錯誤")
    void register_passwordTooShort_shouldReturn400() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(TEST_EMAIL);
        request.setPassword("Test123");
        request.setUsername(TEST_USERNAME);
        request.setNickname(TEST_NICKNAME);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    // =========================================================================
    // Client IP 解析與 IP 層級限流（Task 14）
    // =========================================================================

    /**
     * 驗證：login 帶 X-Forwarded-For 時，應取第一個 IP 傳入 authService.login。
     */
    @Test
    @DisplayName("POST /login → 帶 X-Forwarded-For → 應將第一個 IP 傳入 authService.login")
    void login_withXForwardedFor_shouldPassFirstIpToService() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setIdentifier(TEST_EMAIL);
        request.setPassword(TEST_PASSWORD);

        when(authService.login(anyString(), anyString(), anyString()))
                .thenReturn(new LoginResult("mock.access.token", "mock.refresh.token"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Forwarded-For", "203.0.113.7, 70.41.3.18, 150.172.238.178")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(authService).login(eq(TEST_EMAIL), eq(TEST_PASSWORD), eq("203.0.113.7"));
    }

    /**
     * 驗證：login 無 X-Forwarded-For 時，應退回 request.getRemoteAddr() 傳入 service。
     */
    @Test
    @DisplayName("POST /login → 無 X-Forwarded-For → 應將 RemoteAddr 傳入 authService.login")
    void login_withoutXForwardedFor_shouldPassRemoteAddrToService() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setIdentifier(TEST_EMAIL);
        request.setPassword(TEST_PASSWORD);

        when(authService.login(anyString(), anyString(), anyString()))
                .thenReturn(new LoginResult("mock.access.token", "mock.refresh.token"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> { req.setRemoteAddr("198.51.100.23"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(authService).login(eq(TEST_EMAIL), eq(TEST_PASSWORD), eq("198.51.100.23"));
    }

    /**
     * 驗證：login IP 限流超標時 service 拋出 RATE_LIMIT_EXCEEDED，應映射為對應錯誤碼。
     */
    @Test
    @DisplayName("POST /login → IP 限流超標 → 應回傳 RATE_LIMIT_EXCEEDED 錯誤碼")
    void login_ipRateLimitExceeded_shouldReturnRateLimitError() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setIdentifier(TEST_EMAIL);
        request.setPassword(TEST_PASSWORD);

        when(authService.login(anyString(), anyString(), anyString()))
                .thenThrow(new BusinessException(UserErrorCode.RATE_LIMIT_EXCEEDED));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.RATE_LIMIT_EXCEEDED.getCode()));
    }

    /**
     * 驗證：register 帶 X-Forwarded-For 時，應取第一個 IP 傳入 authService.register。
     */
    @Test
    @DisplayName("POST /register → 帶 X-Forwarded-For → 應將第一個 IP 傳入 authService.register")
    void register_withXForwardedFor_shouldPassFirstIpToService() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(TEST_EMAIL);
        request.setPassword(TEST_PASSWORD);
        request.setUsername(TEST_USERNAME);
        request.setNickname(TEST_NICKNAME);

        doNothing().when(authService).register(anyString(), anyString(), anyString(), anyString(), anyString());

        mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Forwarded-For", "203.0.113.9, 10.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(authService).register(eq(TEST_EMAIL), eq(TEST_PASSWORD), eq(TEST_USERNAME),
                eq(TEST_NICKNAME), eq("203.0.113.9"));
    }

    /**
     * 驗證：register IP 限流超標時 service 拋出 RATE_LIMIT_EXCEEDED，應映射為對應錯誤碼。
     */
    @Test
    @DisplayName("POST /register → IP 限流超標 → 應回傳 RATE_LIMIT_EXCEEDED 錯誤碼")
    void register_ipRateLimitExceeded_shouldReturnRateLimitError() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(TEST_EMAIL);
        request.setPassword(TEST_PASSWORD);
        request.setUsername(TEST_USERNAME);
        request.setNickname(TEST_NICKNAME);

        doThrow(new BusinessException(UserErrorCode.RATE_LIMIT_EXCEEDED))
                .when(authService).register(anyString(), anyString(), anyString(), anyString(), anyString());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.RATE_LIMIT_EXCEEDED.getCode()));
    }
}
