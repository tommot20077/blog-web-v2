package dowob.xyz.blog.module.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.user.model.dto.request.LoginRequest;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登入 IP 限流 × 偽造 {@code X-Forwarded-For} 的整合測試（SEC-01；真實 Spring Security + Redis）。
 *
 * <p>攻擊情境：同一個來源（remoteAddr 固定）每次請求都送不同的 {@code X-Forwarded-For}，
 * 期望藉此讓每次請求落在不同的限流桶。預設 {@code app.http.trusted-proxy-count=0} 下，
 * 計數必須累積在 remoteAddr 的 key 上、第 {@code LOGIN_IP_MAX + 1} 次被擋，且偽造 IP 不會產生任何 key。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@AutoConfigureMockMvc
class AuthLoginIpRateLimitIntegrationTest extends AbstractIntegrationTest {

    /** 攻擊者的真實來源位址（文件保留網段 TEST-NET-2） */
    private static final String ATTACKER_REMOTE_ADDR = "198.51.100.77";

    /** MockMvc（走完整 filter chain） */
    @Autowired
    private MockMvc mockMvc;

    /** Jackson 物件序列化工具 */
    @Autowired
    private ObjectMapper objectMapper;

    /** Redis 模板（檢查限流 key） */
    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    @AfterEach
    void cleanRateLimitKey() {
        redisTemplate.delete(RedisKeyConstant.getLoginIpKey(ATTACKER_REMOTE_ADDR));
    }

    @Test
    @DisplayName("SEC-01：同一來源每次送不同偽造 XFF → 計數仍累積在 remoteAddr，第 LOGIN_IP_MAX+1 次被限流")
    void login_rotatingForgedXff_stillRateLimitedByRemoteAddr() throws Exception {
        LoginRequest body = new LoginRequest();
        body.setIdentifier("no-such-user-" + UUID.randomUUID() + "@example.com");
        body.setPassword("Password123!");
        String json = objectMapper.writeValueAsString(body);

        for (int i = 1; i <= RedisKeyConstant.LOGIN_IP_MAX; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .header("X-Forwarded-For", "10." + i + ".0.1")
                            .with(req -> { req.setRemoteAddr(ATTACKER_REMOTE_ADDR); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(UserErrorCode.RATE_LIMIT_EXCEEDED.getCode())));
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Forwarded-For", "10.250.0.1")
                        .with(req -> { req.setRemoteAddr(ATTACKER_REMOTE_ADDR); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.RATE_LIMIT_EXCEEDED.getCode()));

        assertThat(redisTemplate.opsForValue().get(RedisKeyConstant.getLoginIpKey(ATTACKER_REMOTE_ADDR)))
                .isEqualTo(String.valueOf(RedisKeyConstant.LOGIN_IP_MAX + 1));
        assertThat(redisTemplate.keys(RedisKeyConstant.LOGIN_IP_PREFIX + "10.*"))
                .as("偽造的 XFF 位址不得產生任何限流 key")
                .isEmpty();
    }
}
