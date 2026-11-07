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
 * management port 上的 Security 規則測試（ARCH-16／決策 C7）。
 *
 * <p>actuator 搬到獨立的 management port 後，同一條 {@link SecurityConfig} 過濾器鏈也會套用在該 port 的請求上
 * （E2E 實測：不加規則時 {@code /actuator/prometheus} 在 management port 回 401）。
 * Prometheus 抓取端不帶 JWT，所以<b>只有「從 management port 進來」的 {@code /actuator/**} 請求</b>匿名放行；
 * 主 port 上的同一路徑維持 fail-closed。management port 由 {@code local.management.port} 決定（啟動後才有值，
 * 故規則在請求時才讀取）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest
@ContextConfiguration(classes = {SecurityConfig.class, JwtAuthenticationFilter.class,
        SecurityConfigTest.StubController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"local.server.port=8080", "local.management.port=9091"})
@DisplayName("SecurityConfig management port 規則測試")
class SecurityConfigManagementPortTest {

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
    @DisplayName("從 management port 進來的 GET /actuator/prometheus，未認證也應放行（不是 401／403）")
    void anonymousPrometheusOnManagementPort_shouldBePermitted() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(onPort(9091)))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401 || status == 403) {
                        throw new AssertionError("management port 上的 /actuator/prometheus 不應需要 JWT，實得 " + status);
                    }
                });
    }

    @Test
    @DisplayName("從主 port 進來的 GET /actuator/prometheus，未認證仍回 401（fail-closed）")
    void anonymousPrometheusOnMainPort_shouldReturn401() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(onPort(8080)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("management port 不會順帶放行 API：未認證 GET /api/v1/articles/me 在 management port 仍回 401")
    void anonymousApiOnManagementPort_shouldStillReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/articles/me").with(onPort(9091)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("從 management port 進來的 /actuator/health 與 /actuator/info，未認證也應放行（由 management port 規則涵蓋，不再靠獨立 permitAll）")
    void anonymousHealthAndInfoOnManagementPort_shouldBePermitted() throws Exception {
        for (String path : new String[]{"/actuator/health", "/actuator/info"}) {
            mockMvc.perform(get(path).with(onPort(9091)))
                    .andExpect(result -> {
                        int st = result.getResponse().getStatus();
                        if (st == 401 || st == 403) {
                            throw new AssertionError(path + " 在 management port 不應需要 JWT，實得 " + st);
                        }
                    });
        }
    }
}
