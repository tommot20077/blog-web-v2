package dowob.xyz.blog.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TrustedForwardedRequestFilter} 單元測試（F1）。
 *
 * <p>不啟動 Spring：以 {@link MockHttpServletRequest} 模擬 Tomcat 看到的原始請求
 * （TLS 在 proxy 終結 → scheme=http、Host 不含 port 時 serverPort=80），檢查下游拿到的請求視圖。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("TrustedForwardedRequestFilter")
class TrustedForwardedRequestFilterTest {

    /** 對外網域 */
    private static final String PUBLIC_HOST = "90030.xyz";

    /**
     * 建立 Tomcat 視角的原始請求：Host=90030.xyz、scheme=http、port=80、remoteAddr=Ingress 內網 IP。
     *
     * @return 原始請求
     */
    private static MockHttpServletRequest rawRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.addHeader("Host", PUBLIC_HOST);
        request.setScheme("http");
        request.setServerName(PUBLIC_HOST);
        request.setServerPort(80);
        request.setRemoteAddr("10.42.0.7");
        return request;
    }

    /**
     * 跑一次 filter，回傳下游實際收到的請求。
     *
     * @param trustedProxyCount 受信任層數
     * @param request           原始請求
     * @return 下游看到的請求
     * @throws Exception 例外
     */
    private static HttpServletRequest filtered(int trustedProxyCount, MockHttpServletRequest request) throws Exception {
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, jakarta.servlet.http.HttpServletResponse resp) {
                seen.set(req);
            }
        });
        new TrustedForwardedRequestFilter(trustedProxyCount).doFilter(request, new MockHttpServletResponse(), chain);
        return seen.get();
    }

    @Test
    @DisplayName("順序必須早於 Spring Security 過濾器鏈（CorsFilter 在其內）")
    void order_mustPrecedeSpringSecurityFilterChain() {
        assertThat(TrustedForwardedRequestFilter.ORDER).isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
    }

    @Nested
    @DisplayName("count=0：完全忽略")
    class CountZero {

        @Test
        @DisplayName("X-Forwarded-Proto/Host/Port 全部不採用，scheme 維持 http")
        void ignoresAllForwardedHeaders() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", "https");
            request.addHeader("X-Forwarded-Host", "evil.test");
            request.addHeader("X-Forwarded-Port", "8443");

            HttpServletRequest seen = filtered(0, request);

            assertThat(seen.getScheme()).isEqualTo("http");
            assertThat(seen.isSecure()).isFalse();
            assertThat(seen.getServerName()).isEqualTo(PUBLIC_HOST);
            assertThat(seen.getServerPort()).isEqualTo(80);
        }
    }

    @Nested
    @DisplayName("count=1：採用 proxy 附加的最右一個值")
    class CountOne {

        @Test
        @DisplayName("X-Forwarded-Proto: https → scheme=https、isSecure、port 補預設 443（Tomcat 回報的 80 是 http 預設）")
        void protoHttps_restoresSchemeAndDefaultPort() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", "https");

            HttpServletRequest seen = filtered(1, request);

            assertThat(seen.getScheme()).isEqualTo("https");
            assertThat(seen.isSecure()).isTrue();
            assertThat(seen.getServerName()).isEqualTo(PUBLIC_HOST);
            assertThat(seen.getServerPort()).isEqualTo(443);
        }

        @Test
        @DisplayName("X-Forwarded-Proto 大小寫不敏感並正規化為小寫")
        void protoIsCaseInsensitive() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", "HTTPS");

            assertThat(filtered(1, request).getScheme()).isEqualTo("https");
        }

        @Test
        @DisplayName("X-Forwarded-Proto: http → scheme 維持 http、port 80")
        void protoHttp_keepsHttp() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", "http");

            HttpServletRequest seen = filtered(1, request);

            assertThat(seen.getScheme()).isEqualTo("http");
            assertThat(seen.isSecure()).isFalse();
            assertThat(seen.getServerPort()).isEqualTo(80);
        }

        @Test
        @DisplayName("沒有任何 X-Forwarded-*：原樣放行（不包裝）")
        void noForwardedHeaders_passesOriginalRequest() throws Exception {
            MockHttpServletRequest request = rawRequest();

            assertThat(filtered(1, request)).isSameAs(request);
        }

        @Test
        @DisplayName("X-Forwarded-Port 優先於預設 port")
        void forwardedPort_overridesDefault() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", "https");
            request.addHeader("X-Forwarded-Port", "8443");

            assertThat(filtered(1, request).getServerPort()).isEqualTo(8443);
        }

        @Test
        @DisplayName("X-Forwarded-Host 覆寫 serverName；其中的 port 同時採用")
        void forwardedHost_overridesServerNameAndPort() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", "https");
            request.addHeader("X-Forwarded-Host", "blog.example.org:9443");

            HttpServletRequest seen = filtered(1, request);

            assertThat(seen.getServerName()).isEqualTo("blog.example.org");
            assertThat(seen.getServerPort()).isEqualTo(9443);
        }

        @Test
        @DisplayName("X-Forwarded-Host 不帶 port → 依有效 scheme 取預設 port")
        void forwardedHostWithoutPort_usesSchemeDefault() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.setServerPort(8080);
            request.addHeader("Host", "blog-app.internal:8080");
            request.addHeader("X-Forwarded-Proto", "https");
            request.addHeader("X-Forwarded-Host", PUBLIC_HOST);

            HttpServletRequest seen = filtered(1, request);

            assertThat(seen.getServerName()).isEqualTo(PUBLIC_HOST);
            assertThat(seen.getServerPort()).isEqualTo(443);
        }

        @Test
        @DisplayName("只有 proto 且 Host header 帶明確 port → 保留該 port")
        void protoOnly_keepsExplicitHostPort() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.removeHeader("Host");
            request.addHeader("Host", PUBLIC_HOST + ":8443");
            request.setServerPort(8443);
            request.addHeader("X-Forwarded-Proto", "https");

            assertThat(filtered(1, request).getServerPort()).isEqualTo(8443);
        }

        @Test
        @DisplayName("不處理 IP：X-Forwarded-For 存在也不改 remoteAddr/remoteHost（IP 只由 ClientIpResolver 決定）")
        void doesNotTouchRemoteAddress() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", "https");
            request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.9");
            request.addHeader("Forwarded", "for=6.6.6.6;proto=http;host=evil.test");
            request.setRemoteHost("ingress.local");

            HttpServletRequest seen = filtered(1, request);

            assertThat(seen.getRemoteAddr()).isEqualTo("10.42.0.7");
            assertThat(seen.getRemoteHost()).isEqualTo("ingress.local");
            assertThat(seen.getScheme()).isEqualTo("https");
            // RFC 7239 Forwarded 的 proto／host 亦不採用
            assertThat(seen.getServerName()).isEqualTo(PUBLIC_HOST);
        }

        @Test
        @DisplayName("其他 header 與 URI 維持不變")
        void otherRequestStateUnchanged() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("Origin", "https://" + PUBLIC_HOST);
            request.addHeader("X-Forwarded-Proto", "https");

            HttpServletRequest seen = filtered(1, request);

            assertThat(seen.getHeader("Origin")).isEqualTo("https://" + PUBLIC_HOST);
            assertThat(seen.getMethod()).isEqualTo("POST");
            assertThat(seen.getRequestURI()).isEqualTo("/api/v1/auth/login");
        }
    }

    @Nested
    @DisplayName("多值 header：依 trusted-proxy-count 取右數第 N 個")
    class MultiValue {

        @Test
        @DisplayName("count=1：偽造值在左、proxy 附加值在右 → 取最右")
        void countOne_takesRightmost() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", "http, https");

            assertThat(filtered(1, request).getScheme()).isEqualTo("https");
        }

        @Test
        @DisplayName("count=1：最右是 http（偽造的 https 在左）→ 不被左側 https 帶偏")
        void countOne_forgedLeftHttpsDoesNotWin() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", "https, http");

            HttpServletRequest seen = filtered(1, request);

            assertThat(seen.getScheme()).isEqualTo("http");
            assertThat(seen.isSecure()).isFalse();
        }

        @Test
        @DisplayName("count=2：取右數第 2 個")
        void countTwo_takesSecondFromRight() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", "http, https, http");

            assertThat(filtered(2, request).getScheme()).isEqualTo("https");
        }

        @Test
        @DisplayName("多行 header 視同以逗號串接")
        void multipleHeaderLines_areJoined() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", "http");
            request.addHeader("X-Forwarded-Proto", "https");

            assertThat(filtered(1, request).getScheme()).isEqualTo("https");
        }

        @Test
        @DisplayName("項目數少於 count → 該 header 不採用（不退而取最左，連最左也無法確認可信）")
        void fewerEntriesThanCount_isIgnored() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", "https");

            HttpServletRequest seen = filtered(2, request);

            assertThat(seen.getScheme()).isEqualTo("http");
            assertThat(seen.getServerPort()).isEqualTo(80);
        }

        @Test
        @DisplayName("X-Forwarded-Host 多值：取右數第 N 個，偽造的左側值不採用")
        void forwardedHost_takesRightmost() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", "https");
            request.addHeader("X-Forwarded-Host", "evil.test, " + PUBLIC_HOST);

            assertThat(filtered(1, request).getServerName()).isEqualTo(PUBLIC_HOST);
        }
    }

    @Nested
    @DisplayName("白名單驗證：非法值一律忽略")
    class Validation {

        @ParameterizedTest(name = "X-Forwarded-Proto=[{0}] 被忽略")
        @ValueSource(strings = {"ftp", "javascript", "https://evil.test", "", " ", "httpss", "h ttps",
                "https;host=evil.test", "ht\ttps"})
        void invalidProto_isIgnored(String value) throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", value);

            HttpServletRequest seen = filtered(1, request);

            assertThat(seen.getScheme()).isEqualTo("http");
            assertThat(seen.isSecure()).isFalse();
            assertThat(seen.getServerPort()).isEqualTo(80);
        }

        @ParameterizedTest(name = "X-Forwarded-Host=[{0}] 被忽略")
        @ValueSource(strings = {"evil.test/path", "evil.test?x=1", "user@evil.test", "evil .test", "-evil.test",
                "evil-.test", "evil..test", "evil.test:", "evil.test:abc", "evil.test:0", "evil.test:65536",
                "evil.test:80:81", "[::1]", "[::1]:8443", "<script>", "evil.test\r\nX-Injected: 1", "",
                "evıl.test", "e%76il.test"})
        void invalidHost_isIgnored(String value) throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", "https");
            request.addHeader("X-Forwarded-Host", value);

            assertThat(filtered(1, request).getServerName()).isEqualTo(PUBLIC_HOST);
        }

        @Test
        @DisplayName("X-Forwarded-Host 過長（>253）被忽略")
        void tooLongHost_isIgnored() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", "https");
            request.addHeader("X-Forwarded-Host", ("a".repeat(60) + ".").repeat(5) + "com");

            assertThat(filtered(1, request).getServerName()).isEqualTo(PUBLIC_HOST);
        }

        @ParameterizedTest(name = "X-Forwarded-Port=[{0}] 被忽略")
        @ValueSource(strings = {"0", "65536", "-1", "80a", "8 0", "", "99999999999", "0x50", "+80"})
        void invalidPort_isIgnored(String value) throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", "https");
            request.addHeader("X-Forwarded-Port", value);

            assertThat(filtered(1, request).getServerPort()).isEqualTo(443);
        }

        @Test
        @DisplayName("token 前後的空白（OWS）被容忍，與 ClientIpResolver 的 trim 一致")
        void surroundingWhitespace_isTrimmed() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", "  https\t");

            assertThat(filtered(1, request).getScheme()).isEqualTo("https");
        }

        @Test
        @DisplayName("proto 非法但 host 合法：只採用合法的那個 header")
        void validatesEachHeaderIndependently() throws Exception {
            MockHttpServletRequest request = rawRequest();
            request.addHeader("X-Forwarded-Proto", "gopher");
            request.addHeader("X-Forwarded-Host", "blog.example.org");

            HttpServletRequest seen = filtered(1, request);

            assertThat(seen.getScheme()).isEqualTo("http");
            assertThat(seen.getServerName()).isEqualTo("blog.example.org");
            assertThat(seen.getServerPort()).isEqualTo(80);
        }
    }
}
