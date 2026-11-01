package dowob.xyz.blog.infrastructure.web;

import dowob.xyz.blog.infrastructure.config.SecurityConfig;
import dowob.xyz.blog.infrastructure.security.AuthStateLoader;
import dowob.xyz.blog.infrastructure.security.JwtAuthenticationFilter;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TLS 終結 proxy 後方的 CORS 判定測試共用基底（F1）。
 *
 * <p>載入真實的 {@link SecurityConfig}（含 Spring Security 的 {@code CorsFilter}）與
 * {@link TrustedForwardedRequestFilter}，其餘依賴以 mock 取代。
 * 允許來源刻意只列 {@code http://localhost:5500}：prod 同域部署時 {@code APP_CORS_ALLOWED_ORIGINS}
 * 不會列出 {@code https://90030.xyz}，這正是 F1 的觸發條件。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest
@ContextConfiguration(classes = {SecurityConfig.class, JwtAuthenticationFilter.class,
        TrustedForwardedRequestFilter.class, AbstractForwardedCorsTest.StubController.class})
@Import(SecurityConfig.class)
abstract class AbstractForwardedCorsTest {

    /** 對外網域（同域部署：前端與 API 皆在此網域） */
    protected static final String PUBLIC_HOST = "90030.xyz";

    /** 登入端點（公開、POST） */
    protected static final String LOGIN_PATH = "/api/v1/auth/login";

    @Autowired
    protected MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private UserAuthService userAuthService;

    @MockitoBean
    private AuthStateLoader authStateLoader;

    /** 測試用 Controller */
    @RestController
    static class StubController {

        @PostMapping(LOGIN_PATH)
        public String login() {
            return "login";
        }

        @GetMapping("/api/v1/articles")
        public String articles() {
            return "articles";
        }
    }
}
