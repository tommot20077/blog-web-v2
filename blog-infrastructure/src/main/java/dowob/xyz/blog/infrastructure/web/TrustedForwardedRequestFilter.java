package dowob.xyz.blog.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 還原 TLS 終結 proxy 後方的請求 scheme／host／port（SEC-01 移除 {@code forward-headers-strategy} 的副作用修復）。
 *
 * <p>
 * <b>為何需要</b>：正式拓撲 Client → Cloudflare → nginx → Ingress → app 的 TLS 在 proxy 終結，app 看到的
 * scheme 是 {@code http}、port 是 Tomcat 對 http 回報的 80。Spring 的 {@code CorsUtils.isCorsRequest}
 * 以 scheme＋host＋port 比對 {@code Origin}，於是同網域請求（{@code Origin: https://90030.xyz}）被誤判為跨域，
 * 若 {@code APP_CORS_ALLOWED_ORIGINS} 未列該網域就回 403 Invalid CORS request。
 * 原本由 {@code server.forward-headers-strategy: framework} 掩蓋，但它同時讓 XFF 無條件改寫 remoteAddr，已移除（SEC-01）。
 * </p>
 *
 * <p>
 * <b>信任規則</b>（與 {@link ClientIpResolver} 一致）：
 * </p>
 * <ul>
 *   <li>{@code app.http.trusted-proxy-count = 0}（預設）：<b>完全忽略</b> {@code X-Forwarded-Proto/Host/Port}，原樣放行。</li>
 *   <li>{@code >= 1}：各 header 分別取「由右數第 N 個」值（多行 header 視同逗號串接；項目數不足 N 則該 header 不採用）。
 *       因此 proxy 鏈上第 N 層（由 app 往外數）必須讓該 header 的右數第 N 個值代表 client 看到的 scheme／host／port。</li>
 *   <li>值一律白名單驗證，非法值忽略（不拋例外、不拒絕請求）：proto 只接受 {@code http}／{@code https}（不分大小寫）；
 *       host 為 RFC 1123 主機名稱或 IPv4，可選 {@code :port}（不接受方括號 IPv6、userinfo、路徑）；port 為 1–65535 的十進位整數。</li>
 * </ul>
 *
 * <p>
 * <b>只還原</b> {@code getScheme()}／{@code isSecure()}／{@code getServerName()}／{@code getServerPort()}。
 * <b>不處理任何 IP</b>：不讀 {@code X-Forwarded-For}／{@code Forwarded}，也不改 {@code getRemoteAddr()}／{@code getRemoteHost()}
 * ——client IP 只由 {@link ClientIpResolver} 決定。亦不改寫 {@code getRequestURL()}（程式不依 request 組絕對 URL）。
 * </p>
 *
 * <p>
 * <b>port 規則</b>：Tomcat 在 Host 不含 port 時回報 80，與還原後的 https 不符，Spring 比對時
 * {@code https://90030.xyz}（隱含 443）會再次對不上，故必須一併還原。優先序：{@code X-Forwarded-Port}
 * → {@code X-Forwarded-Host} 內的 port → 依有效 scheme 的預設值（http=80、https=443）；
 * 若只有 proto 且 {@code Host} header 帶明確 port 則保留該 port。
 * </p>
 *
 * <p>
 * <b>順序</b>：{@link #ORDER} 為 {@code HIGHEST_PRECEDENCE + 10}，小於 Spring Security 過濾器鏈的
 * {@code SecurityProperties.DEFAULT_FILTER_ORDER}（-100），故必先於其內的 {@code CorsFilter}；
 * 守衛：{@code TrustedForwardedRequestFilterTest#order_mustPrecedeSpringSecurityFilterChain}。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@Order(TrustedForwardedRequestFilter.ORDER)
public class TrustedForwardedRequestFilter extends OncePerRequestFilter {

    /** 過濾器順序：早於 Spring Security 過濾器鏈（{@code SecurityProperties.DEFAULT_FILTER_ORDER}） */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    /** 唯二允許出現此 header 字面值的類別之一（由 ClientIpAccessGuardTest 守衛，不得出現 X-Forwarded-For） */
    private static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";

    private static final String X_FORWARDED_HOST = "X-Forwarded-Host";

    private static final String X_FORWARDED_PORT = "X-Forwarded-Port";

    private static final String HOST = "Host";

    /** 單一 DNS label：英數開頭結尾、中間可含連字號，最長 63 */
    private static final String LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?";

    /** 主機名稱（含 IPv4 字面值）＋可選 port */
    private static final Pattern HOST_WITH_OPTIONAL_PORT =
            Pattern.compile("(" + LABEL + "(?:\\." + LABEL + ")*)(?::([0-9]{1,5}))?");

    /** 純十進位 port */
    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");

    /** 主機名稱總長上限（RFC 1035） */
    private static final int MAX_HOST_LENGTH = 253;

    private static final int MAX_PORT = 65535;

    /** 受信任的反向代理層數；0 表示不採用任何 forwarded header */
    private final int trustedProxyCount;

    /**
     * 建構過濾器。
     *
     * @param trustedProxyCount 受信任的反向代理層數（{@code app.http.trusted-proxy-count}，預設 0）
     * @throws IllegalArgumentException 設定為負數時（與 {@link ClientIpResolver} 一致，啟動即失敗）
     */
    public TrustedForwardedRequestFilter(@Value("${app.http.trusted-proxy-count:0}") int trustedProxyCount) {
        if (trustedProxyCount < 0) {
            throw new IllegalArgumentException(
                    "app.http.trusted-proxy-count 不可為負數，實際值：" + trustedProxyCount);
        }
        this.trustedProxyCount = trustedProxyCount;
    }

    /**
     * 與 Spring 的 {@code ForwardedHeaderFilter} 一致：async 轉派時請求為原始物件，需再次還原。
     *
     * @return 永遠 false
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /**
     * 與 Spring 的 {@code ForwardedHeaderFilter} 一致：error 轉派亦還原。
     *
     * @return 永遠 false
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (trustedProxyCount == 0) {
            chain.doFilter(request, response);
            return;
        }

        String proto = pickProto(request);
        HostPort host = pickHost(request);
        Integer port = pickPort(request);
        if (proto == null && host == null && port == null) {
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(new ForwardedRequest(request, proto, host, port), response);
    }

    /**
     * 取右數第 N 個 {@code X-Forwarded-Proto}，通過白名單才採用。
     *
     * @param request 原始請求
     * @return 小寫的 {@code http}／{@code https}；缺漏、項目不足或非法時為 {@code null}
     */
    private String pickProto(HttpServletRequest request) {
        String raw = pickNthFromRight(request, X_FORWARDED_PROTO);
        if (raw == null) {
            return null;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return "http".equals(lower) || "https".equals(lower) ? lower : null;
    }

    /**
     * 取右數第 N 個 {@code X-Forwarded-Host}，通過白名單才採用。
     *
     * @param request 原始請求
     * @return 主機與可選 port；缺漏、項目不足或非法時為 {@code null}
     */
    private HostPort pickHost(HttpServletRequest request) {
        String raw = pickNthFromRight(request, X_FORWARDED_HOST);
        if (raw == null || raw.length() > MAX_HOST_LENGTH) {
            return null;
        }
        Matcher matcher = HOST_WITH_OPTIONAL_PORT.matcher(raw);
        if (!matcher.matches()) {
            return null;
        }
        String portText = matcher.group(2);
        int port = -1;
        if (portText != null) {
            port = parsePort(portText);
            if (port < 0) {
                return null;
            }
        }
        return new HostPort(matcher.group(1), port);
    }

    /**
     * 取右數第 N 個 {@code X-Forwarded-Port}，通過白名單才採用。
     *
     * @param request 原始請求
     * @return port；缺漏、項目不足或非法時為 {@code null}
     */
    private Integer pickPort(HttpServletRequest request) {
        String raw = pickNthFromRight(request, X_FORWARDED_PORT);
        if (raw == null) {
            return null;
        }
        int port = parsePort(raw);
        return port < 0 ? null : port;
    }

    /**
     * 解析 1–65535 的十進位 port。
     *
     * @param text 候選字串
     * @return port；不合法為 -1
     */
    private static int parsePort(String text) {
        if (!PORT.matcher(text).matches()) {
            return -1;
        }
        int port = Integer.parseInt(text);
        return port >= 1 && port <= MAX_PORT ? port : -1;
    }

    /**
     * 讀出 header（多行視同逗號串接）並取由右數第 N 個值。
     *
     * @param request 原始請求
     * @param name    header 名稱
     * @return 已 trim 的值；header 缺漏或項目數少於 N 為 {@code null}
     */
    private String pickNthFromRight(HttpServletRequest request, String name) {
        Enumeration<String> lines = request.getHeaders(name);
        if (lines == null) {
            return null;
        }
        List<String> chain = new ArrayList<>();
        for (String line : Collections.list(lines)) {
            if (line == null || line.isBlank()) {
                continue;
            }
            for (String entry : line.split(",", -1)) {
                chain.add(entry.trim());
            }
        }
        int index = chain.size() - trustedProxyCount;
        return index < 0 ? null : chain.get(index);
    }

    /**
     * 通過驗證的 host（與 port，無則 -1）。
     *
     * @param name 主機名稱
     * @param port 明確指定的 port；沒有為 -1
     */
    private record HostPort(String name, int port) {
    }

    /**
     * 套用還原值的請求視圖；未被還原的屬性一律委派原始請求。
     */
    private static final class ForwardedRequest extends HttpServletRequestWrapper {

        private final String scheme;
        private final String serverName;
        private final int serverPort;

        ForwardedRequest(HttpServletRequest request, String proto, HostPort host, Integer forwardedPort) {
            super(request);
            this.scheme = proto != null ? proto : request.getScheme();
            this.serverName = host != null ? host.name() : request.getServerName();
            this.serverPort = resolvePort(request, proto, host, forwardedPort, this.scheme);
        }

        /**
         * 決定 port：X-Forwarded-Port → X-Forwarded-Host 內的 port → 依 scheme 預設 → 保留原值。
         */
        private static int resolvePort(HttpServletRequest request, String proto, HostPort host,
                Integer forwardedPort, String effectiveScheme) {
            if (forwardedPort != null) {
                return forwardedPort;
            }
            if (host != null) {
                return host.port() != -1 ? host.port() : defaultPort(effectiveScheme);
            }
            if (proto != null) {
                int explicit = explicitHostHeaderPort(request.getHeader(HOST));
                return explicit != -1 ? explicit : defaultPort(effectiveScheme);
            }
            return request.getServerPort();
        }

        private static int defaultPort(String scheme) {
            return "https".equals(scheme) ? 443 : 80;
        }

        /**
         * 取 {@code Host} header 中明確寫出的 port（Tomcat 在未寫時回報 80，無法與明確的 :80 區分，故自行解析）。
         */
        private static int explicitHostHeaderPort(String hostHeader) {
            if (hostHeader == null) {
                return -1;
            }
            String value = hostHeader.trim();
            int colon = value.lastIndexOf(':');
            if (colon < 0 || colon < value.lastIndexOf(']')) {
                return -1;
            }
            String portText = value.substring(colon + 1);
            return PORT.matcher(portText).matches() ? parsePort(portText) : -1;
        }

        @Override
        public String getScheme() {
            return scheme;
        }

        @Override
        public boolean isSecure() {
            return "https".equals(scheme);
        }

        @Override
        public String getServerName() {
            return serverName;
        }

        @Override
        public int getServerPort() {
            return serverPort;
        }
    }
}
