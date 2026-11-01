package dowob.xyz.blog.infrastructure.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F1：{@code app.http.trusted-proxy-count=1}（prod）時，TLS 終結 proxy 後方的同網域請求不得被誤判為跨域。
 *
 * <p>重現審查者的案例：{@code Host: 90030.xyz}、{@code Origin: https://90030.xyz}、
 * {@code X-Forwarded-Proto: https}，app 實際看到 scheme=http（Tomcat 預設 port 80），
 * 而 Spring 的 {@code CorsUtils.isCorsRequest} 以 scheme＋host＋port 比對 Origin。
 * 移除 {@code forward-headers-strategy} 後若無還原，回 403 Invalid CORS request。
 * MockHttpServletRequest 與 Tomcat 一致：Host 不含 port 時 serverPort 為 80、scheme 為 http。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@TestPropertySource(properties = {
        "app.http.trusted-proxy-count=1",
        "app.cors.allowed-origins=http://localhost:5500"})
@DisplayName("TLS 終結 proxy 後方的 CORS 判定（count=1）")
class ForwardedProtoCorsTrustedProxyTest extends AbstractForwardedCorsTest {

    @Test
    @DisplayName("審查者重現：Host/Origin 同網域 + X-Forwarded-Proto: https → 不得回 403")
    void sameOriginBehindTlsProxy_shouldNotBeRejectedAsCors() throws Exception {
        mockMvc.perform(post(LOGIN_PATH)
                        .header("Host", PUBLIC_HOST)
                        .header("Origin", "https://" + PUBLIC_HOST)
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("proxy 改寫 Host（Host=內部名）但帶 X-Forwarded-Host → 仍視為同網域")
    void sameOriginWithRewrittenHost_shouldUseForwardedHost() throws Exception {
        mockMvc.perform(post(LOGIN_PATH)
                        .header("Host", "blog-app.internal:8080")
                        .header("Origin", "https://" + PUBLIC_HOST)
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", PUBLIC_HOST))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("真的跨域（Origin=https://evil.test，不在白名單）在 count=1 時仍被 CORS 擋下")
    void genuineCrossOrigin_shouldStillBeRejected() throws Exception {
        mockMvc.perform(post(LOGIN_PATH)
                        .header("Host", PUBLIC_HOST)
                        .header("Origin", "https://evil.test")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("攻擊者偽造 X-Forwarded-Host 想把自己的 Origin 變成同域 → 仍被擋（右數第 1 個才採用）")
    void forgedForwardedHostPrefix_shouldNotMakeEvilOriginSameOrigin() throws Exception {
        // 偽造值在左、proxy 附加的真實值在右；count=1 只採用最右一個（90030.xyz），evil.test 不被採用
        mockMvc.perform(post(LOGIN_PATH)
                        .header("Host", PUBLIC_HOST)
                        .header("Origin", "https://evil.test")
                        .header("X-Forwarded-Proto", "https, https")
                        .header("X-Forwarded-Host", "evil.test, " + PUBLIC_HOST))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("非法 X-Forwarded-Proto（非 http/https）被忽略 → 維持 http，同網域 https Origin 仍視為跨域")
    void invalidForwardedProto_shouldBeIgnored() throws Exception {
        mockMvc.perform(post(LOGIN_PATH)
                        .header("Host", PUBLIC_HOST)
                        .header("Origin", "https://" + PUBLIC_HOST)
                        .header("X-Forwarded-Proto", "javascript"))
                .andExpect(status().isForbidden());
    }
}
