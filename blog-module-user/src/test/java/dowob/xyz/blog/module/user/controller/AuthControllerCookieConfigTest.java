package dowob.xyz.blog.module.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.infrastructure.config.SecurityConfig;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.user.model.dto.request.LoginRequest;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 驗證 Cookie secure flag 可透過 app.cookie.secure 設定注入。
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "app.cookie.secure=false")
class AuthControllerCookieConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private UserAuthService userAuthService;

    @Test
    @DisplayName("POST /login → app.cookie.secure=false → Set-Cookie 不含 Secure flag")
    void login_cookieSecureFalse_shouldNotHaveSecureFlagInCookie() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setIdentifier("test@example.com");
        request.setPassword("Test1234");

        when(authService.login(anyString(), anyString(), nullable(String.class)))
                .thenReturn(new LoginResult("mock.access.token", "mock.refresh.token"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(header().string("Set-Cookie", not(containsString("Secure"))));
    }
}
