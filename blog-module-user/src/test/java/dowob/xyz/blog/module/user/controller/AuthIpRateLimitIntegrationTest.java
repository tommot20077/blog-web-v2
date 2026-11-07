package dowob.xyz.blog.module.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.user.service.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * forgot-password／resend-verification／verify-email-code 的 per-IP 限流整合測試（A3／SEC-22；真實 Spring Security + Redis）。
 *
 * <p>每個請求都使用<b>不同的 email</b>，因此 per-email 限流（每分鐘 1 次）不會干擾：被擋下的原因只能是 IP 計數。
 * 涵蓋：同一 IP 第 11 次被擋、不同 IP 互不影響、三個端點各自計數、輪換 {@code X-Forwarded-For} 無法繞過
 * （預設 {@code app.http.trusted-proxy-count=0}，只看 remoteAddr）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@AutoConfigureMockMvc
@DisplayName("忘記密碼／重寄／驗證碼 per-IP 限流整合測試（A3）")
class AuthIpRateLimitIntegrationTest extends AbstractIntegrationTest {

    private static final String IP_A = "198.51.100.71";
    private static final String IP_B = "198.51.100.72";
    private static final String FORGOT = "/api/v1/auth/forgot-password";
    private static final String RESEND = "/api/v1/auth/resend-verification";
    private static final String VERIFY_CODE = "/api/v1/auth/verify-email-code";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void cleanKeys() {
        for (String ip : new String[]{IP_A, IP_B}) {
            redis.delete(RedisKeyConstant.getForgotPasswordIpKey(ip));
            redis.delete(RedisKeyConstant.getResendVerificationIpKey(ip));
            redis.delete(RedisKeyConstant.getVerifyCodeIpKey(ip));
        }
        Set<String> perEmail = redis.keys("*iplimit-*");
        if (perEmail != null && !perEmail.isEmpty()) {
            redis.delete(perEmail);
        }
    }

    private ResultActions call(String path, String remoteAddr, String xff) throws Exception {
        String email = "iplimit-" + UUID.randomUUID() + "@example.com";
        Map<String, String> body = VERIFY_CODE.equals(path)
                ? Map.of("email", email, "code", "123456")
                : Map.of("email", email);
        var request = post(path)
                .with(req -> { req.setRemoteAddr(remoteAddr); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (xff != null) {
            request.header("X-Forwarded-For", xff);
        }
        return mockMvc.perform(request);
    }

    private void expectNotRateLimited(ResultActions result) throws Exception {
        result.andExpect(jsonPath("$.code").value(not(UserErrorCode.RATE_LIMIT_EXCEEDED.getCode())));
    }

    private void expectRateLimited(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.RATE_LIMIT_EXCEEDED.getCode()));
    }

    private void exhaust(String path, String remoteAddr) throws Exception {
        for (int i = 1; i <= RedisKeyConstant.FORGOT_PASSWORD_IP_MAX; i++) {
            expectNotRateLimited(call(path, remoteAddr, null));
        }
    }

    @Test
    @DisplayName("forgot-password：同一 IP 前 10 次放行、第 11 次 RATE_LIMIT_EXCEEDED；不同 IP 不受影響")
    void forgotPassword_eleventhRequestFromSameIpBlocked_otherIpUnaffected() throws Exception {
        exhaust(FORGOT, IP_A);

        expectRateLimited(call(FORGOT, IP_A, null));
        expectNotRateLimited(call(FORGOT, IP_B, null));
    }

    @Test
    @DisplayName("resend-verification：同一 IP 第 11 次被擋；不同 IP 不受影響")
    void resendVerification_eleventhRequestFromSameIpBlocked_otherIpUnaffected() throws Exception {
        exhaust(RESEND, IP_A);

        expectRateLimited(call(RESEND, IP_A, null));
        expectNotRateLimited(call(RESEND, IP_B, null));
    }

    @Test
    @DisplayName("verify-email-code：同一 IP 第 11 次被擋（先於驗證碼比對）；不同 IP 不受影響")
    void verifyEmailCode_eleventhRequestFromSameIpBlocked_otherIpUnaffected() throws Exception {
        exhaust(VERIFY_CODE, IP_A);

        expectRateLimited(call(VERIFY_CODE, IP_A, null));
        expectNotRateLimited(call(VERIFY_CODE, IP_B, null));
    }

    @Test
    @DisplayName("三個端點各自計數：forgot 用盡後 resend 與 verify-code 對同一 IP 仍可用")
    void endpointsCountIndependently() throws Exception {
        exhaust(FORGOT, IP_A);
        expectRateLimited(call(FORGOT, IP_A, null));

        expectNotRateLimited(call(RESEND, IP_A, null));
        expectNotRateLimited(call(VERIFY_CODE, IP_A, null));
        assertThat(redis.opsForValue().get(RedisKeyConstant.getResendVerificationIpKey(IP_A))).isEqualTo("1");
        assertThat(redis.opsForValue().get(RedisKeyConstant.getVerifyCodeIpKey(IP_A))).isEqualTo("1");
    }

    @Test
    @DisplayName("輪換偽造 X-Forwarded-For 無法繞過：計數仍累積在 remoteAddr，第 11 次被擋，且偽造位址不產生任何 key")
    void rotatingForgedXff_cannotBypass() throws Exception {
        for (int i = 1; i <= RedisKeyConstant.FORGOT_PASSWORD_IP_MAX; i++) {
            expectNotRateLimited(call(FORGOT, IP_A, "10." + i + ".0.1"));
        }

        expectRateLimited(call(FORGOT, IP_A, "10.250.0.1"));

        assertThat(redis.opsForValue().get(RedisKeyConstant.getForgotPasswordIpKey(IP_A)))
                .isEqualTo(String.valueOf(RedisKeyConstant.FORGOT_PASSWORD_IP_MAX + 1));
        assertThat(redis.keys(RedisKeyConstant.FORGOT_PASSWORD_IP_PREFIX + "10.*"))
                .as("偽造的 XFF 位址不得產生任何限流 key").isEmpty();
    }

    @Test
    @DisplayName("超限回應與 email 是否存在無關：不存在的 email 與（IP 已超限時）任何 email 得到同樣的錯誤，且窗口有 TTL")
    void overLimit_responseIndependentOfEmail_andKeyHasTtl() throws Exception {
        exhaust(FORGOT, IP_A);

        String first = call(FORGOT, IP_A, null).andReturn().getResponse().getContentAsString();
        String second = call(FORGOT, IP_A, null).andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(first).path("code").asText())
                .isEqualTo(objectMapper.readTree(second).path("code").asText())
                .isEqualTo(UserErrorCode.RATE_LIMIT_EXCEEDED.getCode());
        Long ttl = redis.getExpire(RedisKeyConstant.getForgotPasswordIpKey(IP_A));
        assertThat(ttl).isBetween(1L, RedisKeyConstant.FORGOT_PASSWORD_IP_TTL_MINUTES * 60);
    }
}
