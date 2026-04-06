package dowob.xyz.blog.e2e.security;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.AuthHelper;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static dowob.xyz.blog.e2e.support.AuthHelper.bearerToken;
import static dowob.xyz.blog.e2e.support.DataBuilder.article;
import static dowob.xyz.blog.e2e.support.DataBuilder.category;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security E2E 測試 — 驗證認證/授權矩陣
 *
 * <p>
 * 使用真實 JWT Token（非 SecurityMockMvcRequestPostProcessors）驗證：
 * <ul>
 *   <li>匿名存取公開/受保護端點</li>
 *   <li>角色權限控制（USER / AUTHOR / ADMIN）</li>
 *   <li>Token 失效與格式異常處理</li>
 * </ul>
 * </p>
 */
@DisplayName("Security E2E — 認證/授權矩陣")
class SecurityE2E extends AbstractE2ETest {

    @Autowired
    private AuthHelper authHelper;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    // ========== Anonymous Access ==========

    @Nested
    @DisplayName("匿名存取")
    class AnonymousAccess {

        @Test
        @DisplayName("匿名可存取公開端點 — GET /api/v1/categories, /api/v1/articles, /api/v1/tags/hot 皆回傳 200")
        void anonymousCanAccessPublicEndpoints() throws Exception {
            mockMvc.perform(get("/api/v1/categories"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/v1/articles"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/v1/tags/hot"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("匿名無法存取需認證端點 — GET /api/v1/articles/me, PATCH /api/v1/users/me/profile 回傳 401")
        void anonymousCannotAccessAuthenticatedEndpoints() throws Exception {
            mockMvc.perform(get("/api/v1/articles/me"))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(patch("/api/v1/users/me/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nickname\":\"test\"}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ========== Role-Based Access ==========

    @Nested
    @DisplayName("角色權限控制")
    class RoleBasedAccess {

        @Test
        @DisplayName("USER 無法建立文章 — POST /api/v1/articles 回傳 403（無 ARTICLE_CREATE 權限）")
        void userCannotCreateArticle() throws Exception {
            String userToken = authHelper.createUserWithRole(
                    "user@test.com", "Password1!", "user1", "User", Role.USER);

            Map<String, Object> body = article("Test", "Content");

            mockMvc.perform(post("/api/v1/articles")
                            .with(bearerToken(userToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("USER 無法上傳檔案 — POST /api/v1/files/upload 回傳 403（無 FILE_UPLOAD 權限）")
        void userCannotUploadFile() throws Exception {
            String userToken = authHelper.createUserWithRole(
                    "user@test.com", "Password1!", "user1", "User", Role.USER);

            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.jpg", "image/jpeg", new byte[]{1, 2, 3});

            mockMvc.perform(multipart("/api/v1/files/upload")
                            .file(file)
                            .param("usageType", "ARTICLE_COVER")
                            .with(bearerToken(userToken)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("USER 無法存取 Admin 端點 — POST /api/admin/categories 回傳 403")
        void userCannotAccessAdminEndpoints() throws Exception {
            String userToken = authHelper.createUserWithRole(
                    "user@test.com", "Password1!", "user1", "User", Role.USER);

            Map<String, Object> body = category("TestCat", "test-cat");

            mockMvc.perform(post("/api/v1/admin/categories")
                            .with(bearerToken(userToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("AUTHOR 可建立文章 — POST /api/v1/articles 回傳 200")
        void authorCanCreateArticle() throws Exception {
            String authorToken = authHelper.createUserWithRole(
                    "author@test.com", "Password1!", "author1", "Author", Role.AUTHOR);

            Map<String, Object> body = article("Test", "Content");

            mockMvc.perform(post("/api/v1/articles")
                            .with(bearerToken(authorToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("AUTHOR 無法存取 Admin 端點 — POST /api/admin/categories 回傳 403")
        void authorCannotAccessAdminEndpoints() throws Exception {
            String authorToken = authHelper.createUserWithRole(
                    "author@test.com", "Password1!", "author1", "Author", Role.AUTHOR);

            Map<String, Object> body = category("TestCat", "test-cat");

            mockMvc.perform(post("/api/v1/admin/categories")
                            .with(bearerToken(authorToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("ADMIN 可存取 Admin 端點 — POST /api/admin/categories 回傳 200")
        void adminCanAccessAdminEndpoints() throws Exception {
            String adminToken = authHelper.createUserWithRole(
                    "admin@test.com", "Password1!", "admin1", "Admin", Role.ADMIN);

            Map<String, Object> body = category("TestCat", "test-cat");

            mockMvc.perform(post("/api/v1/admin/categories")
                            .with(bearerToken(adminToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }
    }

    // ========== Token Validation ==========

    @Nested
    @DisplayName("Token 驗證")
    class TokenValidation {

        @Test
        @DisplayName("Token 版本不符回傳 401 — 修改 DB token_version 使舊 Token 失效")
        void expiredTokenReturnsUnauthorized() throws Exception {
            // 註冊並登入取得有效 Token
            String token = authHelper.registerAndLogin(
                    "expire@test.com", "Password1!", "expireuser", "ExpireUser");

            // 驗證 Token 目前有效（可存取需認證端點）
            mockMvc.perform(get("/api/v1/articles/me")
                            .with(bearerToken(token)))
                    .andExpect(status().isOk());

            // 修改 DB 中的 token_version，模擬 Token 失效
            jdbcTemplate.update(
                    "UPDATE users SET token_version = 'v99' WHERE email = ?",
                    "expire@test.com");

            // 清除 Redis 中該用戶的認證快取，強制 JwtAuthenticationFilter 重新查 DB
            Long userId = jdbcTemplate.queryForObject(
                    "SELECT id FROM users WHERE email = ?",
                    Long.class,
                    "expire@test.com");
            String redisKey = RedisKeyConstant.getUserAuthKey(userId);
            redisTemplate.delete(redisKey);

            // 使用舊 Token 存取需認證端點，版本不符應回傳 401
            mockMvc.perform(get("/api/v1/articles/me")
                            .with(bearerToken(token)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("格式錯誤的 Token 回傳 401 — 傳送 'Bearer invalid.token.here'")
        void malformedTokenReturnsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/v1/articles/me")
                            .with(bearerToken("invalid.token.here")))
                    .andExpect(status().isUnauthorized());
        }
    }
}
