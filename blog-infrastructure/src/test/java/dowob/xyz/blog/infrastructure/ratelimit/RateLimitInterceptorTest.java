package dowob.xyz.blog.infrastructure.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.infrastructure.ratelimit.FixedWindowCounter.Hit;
import dowob.xyz.blog.infrastructure.web.ClientIpResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RateLimitInterceptor} 單元測試（SEC-23 / A4）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("RateLimitInterceptor 單元測試")
class RateLimitInterceptorTest {

    private static final UUID USER_A = UUID.fromString("3f2b8c1e-5d44-4a8f-9c11-7a6e0b1d2c33");
    private static final UUID USER_B = UUID.fromString("c9d1e7a2-0b3f-4e65-8a90-1f2e3d4c5b6a");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FixedWindowCounter counter;
    private RateLimitInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    /** 測試用 controller：兩個 handler 帶規則，一個不帶 */
    static class Sample {
        @RateLimited("per-user")
        public void limitedByUser() {
        }

        @RateLimited("per-ip")
        public void limitedByIp() {
        }

        public void unlimited() {
        }

        @RateLimited("no-such-rule")
        public void unknownRule() {
        }
    }

    @RateLimited("per-user")
    static class ClassLevel {
        public void handler() {
        }
    }

    @BeforeEach
    void setUp() {
        counter = mock(FixedWindowCounter.class);
        RateLimitProperties props = new RateLimitProperties(Map.of(
                "per-user", new RateLimitProperties.Rule(2, Duration.ofMinutes(1), RateLimitProperties.Subject.USER),
                "per-ip", new RateLimitProperties.Rule(3, Duration.ofSeconds(30), RateLimitProperties.Subject.IP)));
        interceptor = new RateLimitInterceptor(props, counter, new ClientIpResolver(0), objectMapper);
        request = new MockHttpServletRequest("POST", "/api/v1/x");
        request.setRemoteAddr("203.0.113.7");
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static HandlerMethod handler(Object bean, String name) throws NoSuchMethodException {
        return new HandlerMethod(bean, bean.getClass().getMethod(name));
    }

    private static void loginAs(UUID uuid) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthUser(uuid, Role.USER), null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @Test
    @DisplayName("超過門檻：回 429、帶 Retry-After 秒數與 A0009 body，且不放行 controller")
    void exceedLimit_returns429WithRetryAfter() throws Exception {
        loginAs(USER_A);
        when(counter.hit(anyString(), any())).thenReturn(new Hit(3, 42));

        boolean proceed = interceptor.preHandle(request, response, handler(new Sample(), "limitedByUser"));

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("42");
        assertThat(response.getContentType()).startsWith("application/json");
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("A0009");
    }

    @Test
    @DisplayName("恰等於門檻仍放行（第 limit+1 次才拒絕）")
    void atLimit_isAllowed() throws Exception {
        loginAs(USER_A);
        when(counter.hit(anyString(), any())).thenReturn(new Hit(2, 55));

        boolean proceed = interceptor.preHandle(request, response, handler(new Sample(), "limitedByUser"));

        assertThat(proceed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Retry-After")).isNull();
    }

    @Test
    @DisplayName("已登入者以 userUuid 計數，key 與窗口取自規則；不同使用者互不影響")
    void authenticated_countsPerUser() throws Exception {
        when(counter.hit(anyString(), any())).thenReturn(new Hit(1, 60));

        loginAs(USER_A);
        interceptor.preHandle(request, response, handler(new Sample(), "limitedByUser"));
        loginAs(USER_B);
        interceptor.preHandle(request, new MockHttpServletResponse(), handler(new Sample(), "limitedByUser"));

        verify(counter).hit("ratelimit:per-user:u:" + USER_A, Duration.ofMinutes(1));
        verify(counter).hit("ratelimit:per-user:u:" + USER_B, Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("匿名者以 client IP 計數")
    void anonymous_countsPerIp() throws Exception {
        when(counter.hit(anyString(), any())).thenReturn(new Hit(1, 60));
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        interceptor.preHandle(request, response, handler(new Sample(), "limitedByUser"));

        verify(counter).hit("ratelimit:per-user:ip:203.0.113.7", Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("沒有 Authentication（SecurityContext 為空）也以 IP 計數")
    void noAuthentication_countsPerIp() throws Exception {
        when(counter.hit(anyString(), any())).thenReturn(new Hit(1, 60));

        interceptor.preHandle(request, response, handler(new Sample(), "limitedByUser"));

        verify(counter).hit(eq("ratelimit:per-user:ip:203.0.113.7"), any());
    }

    @Test
    @DisplayName("subject=IP 的規則：即使已登入也一律以 IP 計數")
    void ipSubject_ignoresLogin() throws Exception {
        loginAs(USER_A);
        when(counter.hit(anyString(), any())).thenReturn(new Hit(1, 30));

        interceptor.preHandle(request, response, handler(new Sample(), "limitedByIp"));

        verify(counter).hit("ratelimit:per-ip:ip:203.0.113.7", Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("Redis 例外：與 auth 限流一致不吞例外（fail-closed），不放行也不寫回應")
    void redisFailure_propagates() throws Exception {
        loginAs(USER_A);
        when(counter.hit(anyString(), any())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler(new Sample(), "limitedByUser")))
                .isInstanceOf(RedisConnectionFailureException.class);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("未標 @RateLimited 的 handler 直接放行、不碰 Redis")
    void unannotated_passesThrough() throws Exception {
        boolean proceed = interceptor.preHandle(request, response, handler(new Sample(), "unlimited"));

        assertThat(proceed).isTrue();
        verifyNoInteractions(counter);
    }

    @Test
    @DisplayName("非 HandlerMethod（例如靜態資源）直接放行")
    void nonHandlerMethod_passesThrough() {
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verify(counter, never()).hit(anyString(), any());
    }

    @Test
    @DisplayName("類層級的 @RateLimited 套用到該類所有 handler")
    void classLevelAnnotation_applies() throws Exception {
        loginAs(USER_A);
        when(counter.hit(anyString(), any())).thenReturn(new Hit(1, 60));

        interceptor.preHandle(request, response, handler(new ClassLevel(), "handler"));

        verify(counter).hit(eq("ratelimit:per-user:u:" + USER_A), any());
    }

    @Test
    @DisplayName("規則名稱不在設定中（啟動驗證之外的最後防線）：拋 IllegalStateException，不靜默放行")
    void unknownRule_throws() {
        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler(new Sample(), "unknownRule")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-rule");
        verifyNoInteractions(counter);
    }

    @Test
    @DisplayName("Retry-After 至少為 1 秒")
    void retryAfter_atLeastOne() throws Exception {
        loginAs(USER_A);
        when(counter.hit(anyString(), any())).thenReturn(new Hit(9, 0));

        interceptor.preHandle(request, response, handler(new Sample(), "limitedByUser"));

        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(List.of(response.getStatus())).containsExactly(429);
    }
}
