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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * management port 設成與主 port 相同時的 Security 規則測試（ARCH-16／決策 C7）。
 *
 * <p>誤設 {@code MANAGEMENT_SERVER_PORT} 等於 {@code server.port} 時，actuator 與 API 共用同一個 port，
 * 這個 port 是對外的。此時「從 management port 進來」不能被當成內網信號，{@code /actuator/prometheus}
 * 必須維持需要 ADMIN（fail-closed），否則等於把指標匿名公開到公網。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest
@ContextConfiguration(classes = {SecurityConfig.class, JwtAuthenticationFilter.class,
        SecurityConfigTest.StubController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"local.server.port=8080", "local.management.port=8080"})
@DisplayName("SecurityConfig management port 與主 port 相同時的規則測試")
class SecurityConfigManagementPortSamePortTest {

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

    /** 模擬請求是從指定的本機 port 進來（Tomcat 的 connector port） */
    private static RequestPostProcessor onPort(int port) {
        return request -> {
            request.setLocalPort(port);
            return request;
        };
    }

    @Test
    @DisplayName("management port == 主 port：未認證 GET /actuator/prometheus 仍回 401")
    void anonymousPrometheusWhenPortsEqual_shouldReturn401() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(onPort(8080)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("management port == 主 port：未認證 GET /actuator/health 與 /actuator/info 也回 401（不因舊 permitAll 而匿名可讀）")
    void anonymousHealthAndInfoWhenPortsEqual_shouldReturn401() throws Exception {
        mockMvc.perform(get("/actuator/health").with(onPort(8080))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/info").with(onPort(8080))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("management port == 主 port：/livez、/readyz 仍匿名放行（探針不受影響）")
    void probesWhenPortsEqual_shouldStillBePermitted() throws Exception {
        for (String path : new String[]{"/livez", "/readyz"}) {
            mockMvc.perform(get(path).with(onPort(8080)))
                    .andExpect(result -> {
                        int st = result.getResponse().getStatus();
                        if (st == 401 || st == 403) {
                            throw new AssertionError(path + " 不得需要認證，實得 " + st);
                        }
                    });
        }
    }
}
