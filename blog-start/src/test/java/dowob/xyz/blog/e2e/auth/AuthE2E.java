package dowob.xyz.blog.e2e.auth;

import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.DataBuilder;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import dowob.xyz.blog.e2e.support.E2EAssertions;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auth 模組 E2E 測試
 *
 * <p>涵蓋註冊、登入、Token 刷新、登出、信箱驗證等完整認證流程。</p>
 */
@DisplayName("Auth E2E 測試")
class AuthE2E extends AbstractE2ETest {

    private static final String BASE_URL = "/api/v1/auth";

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    @DisplayName("註冊成功 — 用戶建立於 DB 且狀態為 PENDING_VERIFICATION")
    void register_success() throws Exception {
        // Arrange
        String body = objectMapper.writeValueAsString(
                DataBuilder.register("auth-reg@test.com", "Password123!", "authreguser", "AuthRegUser"));

        // Act
        mockMvc.perform(post(BASE_URL + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        // Assert — 驗證 DB 中的用戶狀態
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM users WHERE email = ?", String.class, "auth-reg@test.com");
        assertThat(status).isEqualTo("PENDING_VERIFICATION");

        Boolean emailVerified = jdbcTemplate.queryForObject(
                "SELECT email_verified FROM users WHERE email = ?", Boolean.class, "auth-reg@test.com");
        assertThat(emailVerified).isFalse();
    }

    @Test
    @DisplayName("重複信箱註冊 — 回傳錯誤碼 A0106")
    void register_duplicateEmail_returnsError() throws Exception {
        // Arrange — 先註冊一次
        String body = objectMapper.writeValueAsString(
                DataBuilder.register("dup@test.com", "Password123!", "dupuser1", "DupUser1"));
        mockMvc.perform(post(BASE_URL + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Act — 以相同信箱再次註冊
        String duplicateBody = objectMapper.writeValueAsString(
                DataBuilder.register("dup@test.com", "Password123!", "dupuser2", "DupUser2"));
        mockMvc.perform(post(BASE_URL + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateBody))
                .andExpect(status().isBadRequest())
                .andExpect(E2EAssertions.apiError("A0106"));
    }

    @Test
    @DisplayName("激活後登入 — 回傳 accessToken")
    void login_afterActivation_returnsTokens() throws Exception {
        // Arrange — 註冊並透過 DB 激活
        String registerBody = objectMapper.writeValueAsString(
                DataBuilder.register("login@test.com", "Password123!", "loginuser", "LoginUser"));
        mockMvc.perform(post(BASE_URL + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isOk());

        jdbcTemplate.update(
                "UPDATE users SET status = 'ACTIVE', email_verified = true WHERE email = ?",
                "login@test.com");

        // Act
        String loginBody = objectMapper.writeValueAsString(
                DataBuilder.login("login@test.com", "Password123!"));
        MvcResult result = mockMvc.perform(post(BASE_URL + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        // Assert — 回應中包含 refreshToken Cookie
        Cookie refreshCookie = result.getResponse().getCookie("refreshToken");
        assertThat(refreshCookie).isNotNull();
        assertThat(refreshCookie.isHttpOnly()).isTrue();
    }

    @Test
    @DisplayName("密碼錯誤登入 — 回傳錯誤碼 A0102")
    void login_wrongPassword_returnsError() throws Exception {
        // Arrange — 註冊並激活
        String registerBody = objectMapper.writeValueAsString(
                DataBuilder.register("wrongpw@test.com", "Password123!", "wrongpwuser", "WrongPwUser"));
        mockMvc.perform(post(BASE_URL + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isOk());

        jdbcTemplate.update(
                "UPDATE users SET status = 'ACTIVE', email_verified = true WHERE email = ?",
                "wrongpw@test.com");

        // Act — 使用錯誤密碼登入
        String loginBody = objectMapper.writeValueAsString(
                DataBuilder.login("wrongpw@test.com", "wrongpassword"));
        mockMvc.perform(post(BASE_URL + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isBadRequest())
                .andExpect(E2EAssertions.apiError("A0102"));
    }

    @Test
    @DisplayName("未驗證信箱登入 — 回傳錯誤碼 A0111")
    void login_unverifiedEmail_returnsError() throws Exception {
        // Arrange — 僅註冊，不激活
        String registerBody = objectMapper.writeValueAsString(
                DataBuilder.register("unverified@test.com", "Password123!", "unverifieduser", "UnverifiedUser"));
        mockMvc.perform(post(BASE_URL + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isOk());

        // Act — 未驗證直接登入
        String loginBody = objectMapper.writeValueAsString(
                DataBuilder.login("unverified@test.com", "Password123!"));
        mockMvc.perform(post(BASE_URL + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isBadRequest())
                .andExpect(E2EAssertions.apiError("A0111"));
    }

    @Test
    @DisplayName("使用有效 refreshToken Cookie 刷新 — 回傳新 accessToken")
    void refresh_withValidCookie_returnsNewAccessToken() throws Exception {
        // Arrange — 註冊、激活、登入取得 refreshToken Cookie
        String registerBody = objectMapper.writeValueAsString(
                DataBuilder.register("refresh@test.com", "Password123!", "refreshuser", "RefreshUser"));
        mockMvc.perform(post(BASE_URL + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isOk());

        jdbcTemplate.update(
                "UPDATE users SET status = 'ACTIVE', email_verified = true WHERE email = ?",
                "refresh@test.com");

        String loginBody = objectMapper.writeValueAsString(
                DataBuilder.login("refresh@test.com", "Password123!"));
        MvcResult loginResult = mockMvc.perform(post(BASE_URL + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn();

        Cookie refreshCookie = loginResult.getResponse().getCookie("refreshToken");
        assertThat(refreshCookie).isNotNull();

        // Act — 使用 refreshToken Cookie 刷新
        mockMvc.perform(post(BASE_URL + "/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("登出後 refreshToken 失效")
    void logout_invalidatesRefreshToken() throws Exception {
        // Arrange — 註冊、激活、登入
        String registerBody = objectMapper.writeValueAsString(
                DataBuilder.register("logout@test.com", "Password123!", "logoutuser", "LogoutUser"));
        mockMvc.perform(post(BASE_URL + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isOk());

        jdbcTemplate.update(
                "UPDATE users SET status = 'ACTIVE', email_verified = true WHERE email = ?",
                "logout@test.com");

        String loginBody = objectMapper.writeValueAsString(
                DataBuilder.login("logout@test.com", "Password123!"));
        MvcResult loginResult = mockMvc.perform(post(BASE_URL + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn();

        String accessToken = objectMapper.readTree(
                loginResult.getResponse().getContentAsString()).get("data").get("accessToken").asText();
        Cookie refreshCookie = loginResult.getResponse().getCookie("refreshToken");
        assertThat(refreshCookie).isNotNull();

        // Act — 登出
        mockMvc.perform(post(BASE_URL + "/logout")
                        .with(request -> {
                            request.addHeader("Authorization", "Bearer " + accessToken);
                            return request;
                        })
                        .cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        // Assert — 使用已失效的 refreshToken 嘗試刷新，應失敗
        mockMvc.perform(post(BASE_URL + "/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(E2EAssertions.apiError("A0104"));
    }

    @Test
    @DisplayName("使用有效驗證 Token 激活帳號")
    void verifyEmail_withValidToken_activatesAccount() throws Exception {
        // Arrange — 註冊（會在 verification_tokens 建立記錄）
        String registerBody = objectMapper.writeValueAsString(
                DataBuilder.register("verify@test.com", "Password123!", "verifyuser", "VerifyUser"));
        mockMvc.perform(post(BASE_URL + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isOk());

        // 從 DB 取得驗證 Token
        String verificationToken = jdbcTemplate.queryForObject(
                "SELECT vt.token FROM verification_tokens vt " +
                        "JOIN users u ON u.id = vt.user_id " +
                        "WHERE u.email = ? AND vt.type = 'EMAIL_VERIFICATION'",
                String.class, "verify@test.com");
        assertThat(verificationToken).isNotNull();

        // Act — 呼叫信箱驗證 API
        mockMvc.perform(get(BASE_URL + "/verify-email")
                        .param("token", verificationToken))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        // Assert — 帳號應已啟用
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM users WHERE email = ?", String.class, "verify@test.com");
        assertThat(status).isEqualTo("ACTIVE");

        Boolean emailVerified = jdbcTemplate.queryForObject(
                "SELECT email_verified FROM users WHERE email = ?", Boolean.class, "verify@test.com");
        assertThat(emailVerified).isTrue();

        // 驗證啟用後可正常登入
        String loginBody = objectMapper.writeValueAsString(
                DataBuilder.login("verify@test.com", "Password123!"));
        mockMvc.perform(post(BASE_URL + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }
}
