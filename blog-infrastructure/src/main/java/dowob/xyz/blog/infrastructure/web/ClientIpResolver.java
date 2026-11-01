package dowob.xyz.blog.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Client IP 解析器（SEC-01 第 3 層；全專案取得 client IP 的<b>唯一入口</b>）。
 *
 * <p>
 * 正式拓撲為 Client → Cloudflare → nginx → k3s Ingress → app。{@code X-Forwarded-For}（XFF）的
 * <b>左側節點可由 client 任意偽造</b>，只有右側由自家 proxy 逐層附加的節點可信。
 * 因此採「由右往左數」：{@code app.http.trusted-proxy-count = N} 表示取 XFF 由右數第 N 節。
 * </p>
 *
 * <ul>
 *   <li>{@code N = 0}（預設）：完全不信任 XFF，回傳 {@link HttpServletRequest#getRemoteAddr()}
 *       （本機 docker、CI、任何無 proxy 的部署）。</li>
 *   <li>{@code N >= 1}：取 XFF 由右數第 N 節。多行 XFF header 依 RFC 7230 §3.2.2 視同以逗號串接。</li>
 *   <li>退回 {@code remoteAddr} 的情況：XFF 缺漏／空白、項目數少於 N（請求沒有經過預期層數的 proxy，
 *       XFF 內任何一節都無法確認可信，連最左節也不行）、被選中的節點不是合法 IP 字面值。
 *       退回的方向是「多人共用同一限流桶」而非「被繞過」。</li>
 * </ul>
 *
 * <p>
 * 被選中的節點只接受 IPv4／IPv6 字面值（不做 DNS 查詢；不接受主機名稱、帶埠、方括號、zone id），
 * 並正規化為 {@link InetAddress#getHostAddress()} 形式（與 Tomcat 的 remoteAddr 同格式；IPv6 完整展開小寫，
 * IPv4-mapped IPv6 轉為 IPv4），避免同一 client 因寫法不同而分裂成多個限流 key。
 * 回傳值會被拿來組 Redis key，故不允許任意字串通過。
 * </p>
 *
 * <p>
 * <b>設定值的不對稱風險</b>：N 設太大 → 取到攻擊者可控的節點，等於無防護；N 設太小 → 取到自家內網 IP，
 * 全部人共用一個限流桶（服務退化但不被繞過）。拿不準時寧可設小。
 * 本機與 CI 用 0；prod 在 nginx 以 realip 覆寫 XFF 後設 1。
 * </p>
 *
 * <p>
 * <b>啟動警示</b>：N = 0 時啟動記一行 WARN。若 prod 忘了設 {@code APP_HTTP_TRUSTED_PROXY_COUNT}，
 * 所有請求的 remoteAddr 都是 Ingress 的 IP，IP 限流等於全站共用一個桶（任一攻擊者即可鎖死全站登入），
 * 且沒有其他徵兆；dev／CI 本來就是 0，所以只警示、不讓啟動失敗。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
public class ClientIpResolver {

    /** X-Forwarded-For 標頭名稱（全專案唯一允許出現此字面值的地方，由 ClientIpAccessGuardTest 守衛） */
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    /** 嚴格 IPv4 十進位點分格式（拒絕前導 0、十六進位與不足四段的縮寫寫法） */
    private static final Pattern IPV4 = Pattern.compile(
            "(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");

    /** IPv6 字面值允許的字元集（含 IPv4-mapped 的點分尾巴）；通過後才交給 JDK 解析，確保不會觸發 DNS */
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9A-Fa-f:.]+");

    /** 受信任的反向代理層數；0 表示不信任 XFF */
    private final int trustedProxyCount;

    /**
     * 建構解析器。
     *
     * @param trustedProxyCount 受信任的反向代理層數（{@code app.http.trusted-proxy-count}，預設 0）
     * @throws IllegalArgumentException 設定為負數時（視為設定錯誤，啟動即失敗，不靜默當成 0）
     */
    public ClientIpResolver(@Value("${app.http.trusted-proxy-count:0}") int trustedProxyCount) {
        if (trustedProxyCount < 0) {
            throw new IllegalArgumentException(
                    "app.http.trusted-proxy-count 不可為負數，實際值：" + trustedProxyCount);
        }
        this.trustedProxyCount = trustedProxyCount;
        if (trustedProxyCount == 0) {
            log.warn("app.http.trusted-proxy-count=0：所有請求的 client IP 一律以 remoteAddr 計（不信任 X-Forwarded-For）。"
                    + "若部署在反向代理（Cloudflare／nginx／Ingress）後方，remoteAddr 會是 proxy 的 IP，"
                    + "IP 限流將全站共用同一個桶；請設定 APP_HTTP_TRUSTED_PROXY_COUNT（prod 通常為 1）。");
        }
    }

    /**
     * 解析請求的 client IP。
     *
     * @param request HTTP 請求
     * @return client IP 字串；信任層數為 0、XFF 無法採信或格式錯誤時為 {@link HttpServletRequest#getRemoteAddr()}
     */
    public String resolve(HttpServletRequest request) {
        if (trustedProxyCount == 0) {
            return request.getRemoteAddr();
        }

        List<String> chain = readForwardedChain(request);
        int index = chain.size() - trustedProxyCount;
        if (index < 0) {
            return request.getRemoteAddr();
        }

        String normalized = normalizeIpLiteral(chain.get(index));
        return normalized != null ? normalized : request.getRemoteAddr();
    }

    /**
     * 讀出 XFF 鏈：多行 header 依序串接，以逗號切開並 trim。
     *
     * @param request HTTP 請求
     * @return 由左到右的節點清單；header 不存在時為空清單
     */
    private List<String> readForwardedChain(HttpServletRequest request) {
        Enumeration<String> lines = request.getHeaders(X_FORWARDED_FOR);
        if (lines == null) {
            return List.of();
        }
        List<String> chain = new ArrayList<>();
        for (String line : Collections.list(lines)) {
            if (line == null || line.isBlank()) {
                continue;
            }
            // limit = -1 保留尾端空節，使「尾端多逗號」被視為空節而非被悄悄吃掉、讓位置偏移
            for (String entry : line.split(",", -1)) {
                chain.add(entry.trim());
            }
        }
        return chain;
    }

    /**
     * 驗證並正規化 IP 字面值。
     *
     * @param candidate XFF 的單一節點（已 trim）
     * @return 正規化後的 IP；不是合法 IPv4／IPv6 字面值時為 {@code null}
     */
    private static String normalizeIpLiteral(String candidate) {
        boolean ipv4 = IPV4.matcher(candidate).matches();
        boolean ipv6 = !ipv4 && candidate.indexOf(':') >= 0 && IPV6_CHARS.matcher(candidate).matches();
        if (!ipv4 && !ipv6) {
            return null;
        }
        try {
            // 已確認是純 IP 字面值（IPv6 含 ':'），InetAddress 不會做 DNS 查詢
            return InetAddress.getByName(candidate).getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
