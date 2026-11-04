package dowob.xyz.blog.infrastructure.config;

import dowob.xyz.blog.infrastructure.security.AuthStateLoader;
import dowob.xyz.blog.infrastructure.security.JwtAuthenticationFilter;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * springdoc 啟用時的 SecurityConfig 放行規則測試（SEC-21）。
 *
 * <p>
 * {@code springdoc.api-docs.enabled=true}（dev／demo／{@code SPRINGDOC_ENABLED=true}）時，
 * Swagger UI 與 api-docs 路徑須匿名可讀；關閉時的行為見 {@link SecurityConfigTest}。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest
@ContextConfiguration(classes = {SecurityConfig.class, JwtAuthenticationFilter.class,
        SecurityConfigTest.StubController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
@DisplayName("SecurityConfig springdoc 啟用時放行測試")
class SecurityConfigSpringdocEnabledTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private UserAuthService userAuthService;

    @MockitoBean
    private AuthStateLoader authStateLoader;

    @Test
    @DisplayName("springdoc 啟用時，未認證訪問 /swagger-ui/** 應回傳 200")
    void unauthenticatedAccessSwagger_whenSpringdocEnabled_shouldReturn200() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("springdoc 啟用時，未認證訪問 /v3/api-docs 應回傳 200")
    void unauthenticatedAccessApiDocs_whenSpringdocEnabled_shouldReturn200() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }
}
