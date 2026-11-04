package dowob.xyz.blog.module.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.infrastructure.config.SecurityConfig;
import dowob.xyz.blog.infrastructure.security.AuthStateLoader;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.infrastructure.web.ClientIpResolver;
import dowob.xyz.blog.module.user.model.dto.request.LoginRequest;
import dowob.xyz.blog.module.user.model.dto.request.RegisterRequest;
import dowob.xyz.blog.module.user.model.dto.response.LoginResult;
import dowob.xyz.blog.module.user.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthController 在 {@code app.http.trusted-proxy-count=1}（prod：nginx 已覆寫 XFF）下的 client IP 行為。
 *
 * <p>證明 controller 確實透過 {@link ClientIpResolver} 取 IP（而非直讀 header 或 remoteAddr），
 * 且攻擊者偽造的 XFF 左側節點不會成為限流 key（SEC-01）。預設值 0 的行為見 {@code AuthControllerTest}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, ClientIpResolver.class})
@TestPropertySource(properties = "app.http.trusted-proxy-count=1")
class AuthControllerTrustedProxyTest {

    /** MockMvc，用於模擬 HTTP 請求 */
    @Autowired
    private MockMvc mockMvc;

    /** Jackson 物件序列化工具 */
    @Autowired
    private ObjectMapper objectMapper;

    /** Mock：認證業務服務 */
    @MockitoBean
    private AuthService authService;

    /** Mock：JWT 服務（JwtAuthenticationFilter 所需） */
    @MockitoBean
    private JwtService jwtService;

    /** Mock：Redis 模板 */
    @MockitoBean
    private StringRedisTemplate redisTemplate;

    /** Mock：用戶認證服務（JwtAuthenticationFilter 所需） */
    @MockitoBean
    private UserAuthService userAuthService;

    /** Mock：認證狀態載入器（JwtAuthenticationFilter 所需） */
    @MockitoBean
    private AuthStateLoader authStateLoader;

    @Test
    @DisplayName("count=1：POST /login → 偽造 XFF 前綴 + proxy 附加的真實 IP → 傳入最右節，而非可偽造的第一節")
    void login_trustedProxyCountOne_shouldPassRightmostXffEntry() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setIdentifier("test@example.com");
        request.setPassword("Password123!");
        when(authService.login(anyString(), anyString(), anyString()))
                .thenReturn(new LoginResult("mock.access.token", "mock.refresh.token"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Forwarded-For", "6.6.6.6, 203.0.113.7")
                        .with(req -> { req.setRemoteAddr("10.0.0.5"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(authService).login(eq("test@example.com"), eq("Password123!"), eq("203.0.113.7"));
    }

    @Test
    @DisplayName("count=1：POST /register → 偽造 XFF 前綴 + proxy 附加的真實 IP → 傳入最右節")
    void register_trustedProxyCountOne_shouldPassRightmostXffEntry() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("test@example.com");
        request.setPassword("Password123!");
        request.setUsername("testuser");
        request.setNickname("testUser");
        doNothing().when(authService).register(anyString(), anyString(), anyString(), anyString(), anyString());

        mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Forwarded-For", "6.6.6.6, 7.7.7.7, 203.0.113.9")
                        .with(req -> { req.setRemoteAddr("10.0.0.5"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(authService).register(eq("test@example.com"), eq("Password123!"), eq("testuser"),
                eq("testUser"), eq("203.0.113.9"));
    }

    @Test
    @DisplayName("count=1：POST /login → 無 XFF（繞過 proxy 直連）→ 傳入 remoteAddr")
    void login_trustedProxyCountOneWithoutXff_shouldPassRemoteAddr() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setIdentifier("test@example.com");
        request.setPassword("Password123!");
        when(authService.login(anyString(), anyString(), anyString()))
                .thenReturn(new LoginResult("mock.access.token", "mock.refresh.token"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> { req.setRemoteAddr("198.51.100.23"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(authService).login(eq("test@example.com"), eq("Password123!"), eq("198.51.100.23"));
    }
}
