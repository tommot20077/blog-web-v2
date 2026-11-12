package dowob.xyz.blog.infrastructure.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F1：{@code app.http.trusted-proxy-count=0}（預設）時完全忽略 {@code X-Forwarded-*}，CORS 判定維持原狀。
 *
 * @author Yuan
 * @version 1.0
 */
@TestPropertySource(properties = {
        "app.http.trusted-proxy-count=0",
        "app.cors.allowed-origins=http://localhost:5500"})
@DisplayName("TLS 終結 proxy 後方的 CORS 判定（count=0，預設）")
class ForwardedProtoCorsUntrustedTest extends AbstractForwardedCorsTest {

    @Test
    @DisplayName("count=0：帶 X-Forwarded-Proto: https 不改變 scheme，https Origin 仍被視為跨域 → 403（維持原狀）")
    void forwardedProto_shouldBeIgnored() throws Exception {
        mockMvc.perform(post(LOGIN_PATH)
                        .header("Host", PUBLIC_HOST)
                        .header("Origin", "https://" + PUBLIC_HOST)
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("count=0：偽造 X-Forwarded-Host 也不能讓跨域 Origin 變同域")
    void forwardedHost_shouldBeIgnored() throws Exception {
        mockMvc.perform(post(LOGIN_PATH)
                        .header("Host", "blog-app.internal")
                        .header("Origin", "https://" + PUBLIC_HOST)
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", PUBLIC_HOST))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("count=0：真正同網域 http（Origin scheme/host/port 與 Host 一致）仍通過，不受影響")
    void trueSameOriginHttp_shouldPass() throws Exception {
        mockMvc.perform(get("/api/v1/articles")
                        .header("Host", PUBLIC_HOST)
                        .header("Origin", "http://" + PUBLIC_HOST))
                .andExpect(status().isOk());
    }
}
