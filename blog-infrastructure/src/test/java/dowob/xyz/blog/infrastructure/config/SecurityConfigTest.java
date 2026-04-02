package dowob.xyz.blog.infrastructure.config;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.security.JwtAuthenticationFilter;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SecurityConfig 授權規則整合測試
 *
 * <p>
 * 使用 {@code @WebMvcTest} 搭配測試用 Controller，驗證 {@link SecurityConfig}
 * 定義的 URL 授權規則、CSRF 設定與自訂 401 入口點。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest
@ContextConfiguration(classes = {SecurityConfig.class, JwtAuthenticationFilter.class, SecurityConfigTest.StubController.class})
@Import(SecurityConfig.class)
@DisplayName("SecurityConfig 授權規則測試")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private UserAuthService userAuthService;

    /**
     * 測試用 Controller，提供各路徑端點以驗證安全規則
     */
    @RestController
    static class StubController {

        @GetMapping("/api/v1/auth/login")
        public String authLogin() {
            return "login";
        }

        @PostMapping("/api/v1/auth/register")
        public String authRegister() {
            return "register";
        }

        @GetMapping("/api/v1/articles/123")
        public String getArticle() {
            return "article";
        }

        @PostMapping("/api/v1/articles")
        public String createArticle() {
            return "created";
        }

        @GetMapping("/api/v1/tags/java")
        public String getTag() {
            return "tag";
        }

        @GetMapping("/api/v1/users/1")
        public String getUser() {
            return "user";
        }

        @GetMapping("/api/v1/files/abc")
        public String getFile() {
            return "file";
        }

        @GetMapping("/api/v1/categories/1")
        public String getCategory() {
            return "category";
        }

        @PostMapping("/api/v1/auth/logout")
        public String authLogout() {
            return "logout";
        }

        @PostMapping("/api/v1/auth/refresh")
        public String authRefresh() {
            return "refresh";
        }

        @GetMapping("/api/v1/recommend/trending")
        public String getRecommend() {
            return "recommend";
        }

        @GetMapping("/api/v1/search")
        public String search() {
            return "search";
        }

        @GetMapping("/api/v1/search/suggest")
        public String searchSuggest() {
            return "suggest";
        }

        @GetMapping("/api/admin/dashboard")
        public String adminDashboard() {
            return "dashboard";
        }

        @PostMapping("/api/admin/settings")
        public String adminSettings() {
            return "settings";
        }

        @DeleteMapping("/api/admin/users/1")
        public String adminDeleteUser() {
            return "deleted";
        }

        @GetMapping("/api/v1/profile")
        public String profile() {
            return "profile";
        }

        @PostMapping("/api/v1/comments")
        public String postComment() {
            return "comment";
        }

        @GetMapping("/swagger-ui/index.html")
        public String swagger() {
            return "swagger";
        }

        @GetMapping("/v3/api-docs")
        public String apiDocs() {
            return "docs";
        }
    }

    // ── Auth 端點（permitAll）──

    @Test
    @DisplayName("未認證訪問 /api/v1/auth/** GET 應回傳 200")
    void unauthenticatedGetAuth_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未認證訪問 /api/v1/auth/** POST 應回傳 200（CSRF 已禁用）")
    void unauthenticatedPostAuth_shouldReturn200() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    // ── Auth 端點（logout 需認證，refresh 靠 cookie 驗證保持公開）──

    @Test
    @DisplayName("未認證 POST /api/v1/auth/logout 應回傳 401")
    void logout_withoutAuth_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("已認證 POST /api/v1/auth/logout 應回傳 200")
    void logout_withAuth_shouldReturn200() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未認證 POST /api/v1/auth/refresh 應回傳 200（permitAll，靠 cookie 驗證）")
    void refresh_withoutAuth_shouldReturn200() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // ── 公開 GET 端點 ──

    @Test
    @DisplayName("未認證 GET /api/v1/articles/** 應回傳 200")
    void unauthenticatedGetArticles_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/articles/123"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未認證 GET /api/v1/tags/** 應回傳 200")
    void unauthenticatedGetTags_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/tags/java"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未認證 GET /api/v1/users/** 應回傳 401（已收窄 permitAll）")
    void unauthenticatedGetUsers_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/users/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("未認證 GET /api/v1/files/** 應回傳 200")
    void unauthenticatedGetFiles_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/files/abc"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未認證 GET /api/v1/categories/** 應回傳 200")
    void unauthenticatedGetCategories_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/categories/1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未認證 GET /api/v1/recommend/** 應回傳 200")
    void unauthenticatedGetRecommend_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/recommend/trending"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未認證 GET /api/v1/search 應回傳 200")
    void unauthenticatedGetSearch_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/search"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未認證 GET /api/v1/search/suggest 應回傳 200")
    void unauthenticatedGetSearchSuggest_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/search/suggest"))
                .andExpect(status().isOk());
    }

    // ── 非公開的寫入端點需要認證 ──

    @Test
    @DisplayName("未認證 POST /api/v1/articles 應回傳 401")
    void unauthenticatedPostArticles_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/v1/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("未認證 POST /api/v1/comments 應回傳 401")
    void unauthenticatedPostComments_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/v1/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ── Admin 端點 ──

    @Test
    @DisplayName("未認證訪問 /api/admin/** 應回傳 401")
    void unauthenticatedAccessAdmin_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("一般用戶訪問 /api/admin/** 應回傳 403")
    void userAccessAdmin_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard").with(asUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin GET /api/admin/** 應通過安全層")
    void adminGetAdmin_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard").with(asAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Admin POST /api/admin/** 應通過安全層")
    void adminPostAdmin_shouldReturn200() throws Exception {
        mockMvc.perform(post("/api/admin/settings").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Admin DELETE /api/admin/** 應通過安全層")
    void adminDeleteAdmin_shouldReturn200() throws Exception {
        mockMvc.perform(delete("/api/admin/users/1").with(asAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AUTHOR 訪問 /api/admin/** 應回傳 403")
    void authorAccessAdmin_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard").with(asAuthor()))
                .andExpect(status().isForbidden());
    }

    // ── 認證用戶存取一般端點 ──

    @Test
    @DisplayName("認證用戶 GET 一般端點應回傳 200")
    void authenticatedUserGetProfile_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/profile").with(asUser()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("認證用戶 POST 一般端點應回傳 200")
    void authenticatedUserPostComment_shouldReturn200() throws Exception {
        mockMvc.perform(post("/api/v1/comments").with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未認證 GET 一般端點應回傳 401")
    void unauthenticatedGetProfile_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/profile"))
                .andExpect(status().isUnauthorized());
    }

    // ── CSRF 禁用驗證 ──

    @Test
    @DisplayName("POST 請求無 CSRF token 不應被拒絕")
    void postWithoutCsrf_shouldNotBeRejected() throws Exception {
        // 若 CSRF 啟用，此 POST 會因缺少 token 回傳 403
        // auth 端點本身 permitAll，所以成功代表 CSRF 已禁用
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    // ── 靜態資源與 Swagger ──

    @Test
    @DisplayName("未認證訪問 /swagger-ui/** 應回傳 200")
    void unauthenticatedAccessSwagger_shouldReturn200() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未認證訪問 /v3/api-docs/** 應回傳 200")
    void unauthenticatedAccessApiDocs_shouldReturn200() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    // ── 自訂 401 入口點 ──

    @Test
    @DisplayName("未認證請求應回傳 401 而非預設 403")
    void unauthenticatedRequest_shouldReturn401NotDefault403() throws Exception {
        mockMvc.perform(get("/api/v1/profile"))
                .andExpect(status().isUnauthorized());
    }

    // ── CORS 設定驗證 ──

    @Nested
    @DisplayName("CORS 設定")
    class CorsConfigTests {

        @Test
        @DisplayName("OPTIONS preflight 請求帶 http://localhost:5500 Origin 應回傳 200 且含 CORS 回應標頭")
        void preflightRequest_fromLocalhostPort5500_shouldReturn200() throws Exception {
            mockMvc.perform(options("/api/v1/articles/123")
                            .header("Origin", "http://localhost:5500")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5500"));
        }

        @Test
        @DisplayName("OPTIONS preflight 請求帶 http://127.0.0.1:5500 Origin 應回傳 200 且含 CORS 回應標頭")
        void preflightRequest_from127Port5500_shouldReturn200() throws Exception {
            mockMvc.perform(options("/api/v1/articles/123")
                            .header("Origin", "http://127.0.0.1:5500")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:5500"));
        }

        @Test
        @DisplayName("OPTIONS preflight 帶不在白名單的 Origin → 不應回傳 Access-Control-Allow-Origin 標頭")
        void preflightRequest_fromUnknownOrigin_shouldNotReturnAllowOriginHeader() throws Exception {
            mockMvc.perform(options("/api/v1/articles/123")
                            .header("Origin", "http://evil.example.com")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }

        @Test
        @DisplayName("CORS 回應應包含 Access-Control-Allow-Credentials: true")
        void corsResponse_shouldIncludeAllowCredentialsHeader() throws Exception {
            mockMvc.perform(options("/api/v1/articles/123")
                            .header("Origin", "http://localhost:5500")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        }
    }

    // ── 工具方法 ──

    private static RequestPostProcessor asAdmin() {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(Role.ADMIN.getSpringSecurityRole()));
        Role.ADMIN.getPermissions().forEach(p ->
                authorities.add(new SimpleGrantedAuthority(p.name())));
        return authentication(
                new UsernamePasswordAuthenticationToken(1L, null, authorities));
    }

    private static RequestPostProcessor asUser() {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(Role.USER.getSpringSecurityRole()));
        Role.USER.getPermissions().forEach(p ->
                authorities.add(new SimpleGrantedAuthority(p.name())));
        return authentication(
                new UsernamePasswordAuthenticationToken(2L, null, authorities));
    }

    private static RequestPostProcessor asAuthor() {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(Role.AUTHOR.getSpringSecurityRole()));
        Role.AUTHOR.getPermissions().forEach(p ->
                authorities.add(new SimpleGrantedAuthority(p.name())));
        return authentication(
                new UsernamePasswordAuthenticationToken(3L, null, authorities));
    }
}
