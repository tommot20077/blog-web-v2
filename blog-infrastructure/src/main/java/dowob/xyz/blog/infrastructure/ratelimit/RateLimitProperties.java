package dowob.xyz.blog.infrastructure.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

/**
 * 應用層限流設定（{@code app.rate-limit}）。
 *
 * <p>每條規則：{@code limit}（窗口內允許次數）、{@code window}（固定窗口長度，至少 1 秒）、
 * {@code subject}（{@code USER}：已登入者以 userUuid 計、匿名者退回 IP；{@code IP}：一律以 IP 計，登入者也一樣）。</p>
 *
 * @param rules 規則名稱 → 規則內容
 * @author Yuan
 * @version 1.0
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(@DefaultValue Map<String, Rule> rules) {

    /**
     * 計數主體。
     */
    public enum Subject {
        /** 已登入者以使用者計數，匿名者以 IP 計數 */
        USER,
        /** 一律以 IP 計數 */
        IP
    }

    /**
     * 單一限流規則。
     *
     * @param limit   窗口內允許的最大次數（第 limit + 1 次起拒絕）
     * @param window  固定窗口長度
     * @param subject 計數主體
     */
    public record Rule(int limit, Duration window, @DefaultValue("USER") Subject subject) {

        /**
         * 驗證規則內容；不合法即丟例外使啟動失敗。
         */
        public Rule {
            if (limit < 1) {
                throw new IllegalArgumentException("限流規則 limit 必須 >= 1，實際為 " + limit);
            }
            if (window == null || window.compareTo(Duration.ofSeconds(1)) < 0) {
                throw new IllegalArgumentException("限流規則 window 必須 >= 1 秒，實際為 " + window);
            }
            if (subject == null) {
                subject = Subject.USER;
            }
        }
    }
}
