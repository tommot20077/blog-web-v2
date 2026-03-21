package dowob.xyz.blog.e2e.flow;

import com.fasterxml.jackson.databind.JsonNode;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.AuthHelper;
import dowob.xyz.blog.e2e.support.DataBuilder;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import dowob.xyz.blog.e2e.support.E2EAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 使用者生命週期 E2E 測試
 *
 * <p>
 * 測試使用者從註冊到刪除帳號的完整生命週期，
 * 包含信箱驗證、角色升級、密碼變更等跨模組行為。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("使用者生命週期 E2E")
class UserLifecycleFlowE2E extends AbstractE2ETest {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    @DisplayName("使用者生命週期 — 註冊→驗證→登入→發文→改密碼→刪帳號")
    void userFullLifecycle() throws Exception {

        String email = "lifecycle@test.com";
        String password = "OldPass123!";
        String username = "lifecycleuser";
        String nickname = "Lifecycle User";

        // ===== 1. 註冊 =====
        String registerBody = objectMapper.writeValueAsString(
                DataBuilder.register(email, password, username, nickname));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        // ===== 2. 從 DB 取得驗證 Token =====
        String verificationToken = jdbcTemplate.queryForObject(
                "SELECT vt.token FROM verification_tokens vt " +
                        "JOIN users u ON u.id = vt.user_id " +
                        "WHERE u.email = ? AND vt.type = 'EMAIL_VERIFICATION'",
                String.class, email);

        // ===== 3. 驗證信箱 =====
        mockMvc.perform(get("/api/v1/auth/verify-email")
                        .param("token", verificationToken))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        // ===== 4. 登入 =====
        String loginBody = objectMapper.writeValueAsString(
                DataBuilder.login(email, password));

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        JsonNode loginData = objectMapper.readTree(
                loginResult.getResponse().getContentAsString()).get("data");
        String accessToken = loginData.get("accessToken").asText();

        // ===== 5. 更新個人資料 =====
        Map<String, Object> profileBody = new LinkedHashMap<>();
        profileBody.put("nickname", "NewName");

        mockMvc.perform(patch("/api/v1/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(profileBody))
                        .with(AuthHelper.bearerToken(accessToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        // ===== 6. 透過 DB 升級為 AUTHOR =====
        jdbcTemplate.update("UPDATE users SET role = 'AUTHOR' WHERE email = ?", email);

        // ===== 7. 重新登入取得包含 AUTHOR 角色的 Token =====
        MvcResult reLoginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andReturn();

        String authorToken = objectMapper.readTree(
                reLoginResult.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();

        // ===== 8. 以 AUTHOR 身份建立文章 =====
        Map<String, Object> articleBody = new LinkedHashMap<>();
        articleBody.put("title", "My First Article");
        articleBody.put("content", "This is the content of my first article as an author.");

        mockMvc.perform(post("/api/v1/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(articleBody))
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.title").value("My First Article"));

        // ===== 9. 確認我的文章列表有資料 =====
        mockMvc.perform(get("/api/v1/articles/me")
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)));

        // ===== 10. 修改密碼 =====
        String newPassword = "NewPass456!";
        Map<String, Object> changePwBody = new LinkedHashMap<>();
        changePwBody.put("oldPassword", password);
        changePwBody.put("newPassword", newPassword);

        mockMvc.perform(post("/api/v1/users/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(changePwBody))
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        // ===== 11. 舊密碼登入失敗 =====
        String oldLoginBody = objectMapper.writeValueAsString(
                DataBuilder.login(email, password));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oldLoginBody))
                .andExpect(status().isBadRequest());

        // ===== 12. 新密碼登入成功 =====
        String newLoginBody = objectMapper.writeValueAsString(
                DataBuilder.login(email, newPassword));

        MvcResult newLoginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newLoginBody))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        String newToken = objectMapper.readTree(
                newLoginResult.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();

        // ===== 13. 刪除帳號 =====
        mockMvc.perform(delete("/api/v1/users/me")
                        .param("password", newPassword)
                        .with(AuthHelper.bearerToken(newToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        // ===== 14. 驗證舊 Token 已失效 =====
        Map<String, Object> profileBody2 = new LinkedHashMap<>();
        profileBody2.put("nickname", "ShouldFail");

        mockMvc.perform(patch("/api/v1/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(profileBody2))
                        .with(AuthHelper.bearerToken(newToken)))
                .andExpect(status().isUnauthorized());
    }
}
