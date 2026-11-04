package dowob.xyz.blog.e2e.security;

import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1 端到端：真實 Tomcat＋完整 Spring Security 過濾器鏈＋{@code app.http.trusted-proxy-count=1}。
 *
 * <p>以原始 socket 送請求（可自訂 {@code Host}，JDK HttpClient 不允許），模擬 TLS 在 proxy 終結後
 * proxy 轉給 app 的明文 HTTP 請求。證明 {@code TrustedForwardedRequestFilter} 確實被 component scan 註冊、
 * 且在真實容器中先於 Spring Security 的 {@code CorsFilter}（MockMvc 單元層無法證明容器排序）。
 * 驗證登入端點的回應不是 CORS 拒絕：Tomcat 回報 port 80 與 https Origin 的 443 不符，
 * 若 filter 未生效或順序在 Security 之後，Spring 會回 403 且 body 為 {@code Invalid CORS request}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@TestPropertySource(properties = "app.http.trusted-proxy-count=1")
@DisplayName("TLS 終結 proxy 後方的 CORS 判定 E2E（真實 Tomcat，count=1）")
class ForwardedProtoCorsE2E extends AbstractE2ETest {

    @LocalServerPort
    private int port;

    /**
     * 送一個 POST /api/v1/auth/login（空 JSON body）並回傳 {@code 狀態行 + body}。
     *
     * @param origin           Origin header
     * @param extraHeaderLines 額外 header（每行不含 CRLF）
     * @return 回應全文
     * @throws Exception IO 例外
     */
    private String postLogin(String origin, String... extraHeaderLines) throws Exception {
        String body = "{}";
        StringBuilder request = new StringBuilder()
                .append("POST /api/v1/auth/login HTTP/1.1\r\n")
                .append("Host: 90030.xyz\r\n")
                .append("Origin: ").append(origin).append("\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n")
                .append("Connection: close\r\n");
        for (String line : extraHeaderLines) {
            request.append(line).append("\r\n");
        }
        request.append("\r\n").append(body);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            StringBuilder response = new StringBuilder();
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    response.append(line).append('\n');
                }
            }
            return response.toString();
        }
    }

    @Test
    @DisplayName("同網域 https Origin + X-Forwarded-Proto: https → 不是 CORS 拒絕（登入請求進入 controller）")
    void sameOriginBehindTlsProxy_isNotRejectedAsCors() throws Exception {
        String response = postLogin("https://90030.xyz", "X-Forwarded-Proto: https");

        assertThat(response).doesNotContain("Invalid CORS request");
        assertThat(response).doesNotStartWith("HTTP/1.1 403");
    }

    @Test
    @DisplayName("真的跨域（https://evil.test）在 count=1 時仍被 CORS 擋下 → 403 Invalid CORS request")
    void genuineCrossOrigin_isStillRejected() throws Exception {
        String response = postLogin("https://evil.test", "X-Forwarded-Proto: https");

        assertThat(response).startsWith("HTTP/1.1 403").contains("Invalid CORS request");
    }

    @Test
    @DisplayName("非法 X-Forwarded-Proto 被忽略 → 同網域 https Origin 仍視為跨域（403）")
    void invalidForwardedProto_isIgnored() throws Exception {
        String response = postLogin("https://90030.xyz", "X-Forwarded-Proto: gopher");

        assertThat(response).startsWith("HTTP/1.1 403").contains("Invalid CORS request");
    }
}
