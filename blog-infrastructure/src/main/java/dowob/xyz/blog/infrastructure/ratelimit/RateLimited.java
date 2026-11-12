package dowob.xyz.blog.infrastructure.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 宣告 controller handler 套用應用層限流（SEC-23 / A4）。
 *
 * <p>{@link #value()} 是規則名稱，對應 {@code app.rate-limit.rules.<name>} 的門檻、窗口與計數主體。
 * 規則名稱在啟動時由 {@link RateLimitRuleValidator} 驗證，打錯或 yaml 沒設定會讓應用程式啟動失敗，
 * 不會靜默不限流。同一規則名稱的多個 handler 共用同一個計數器（例如留言新增與編輯共用每分鐘 10 次）。</p>
 *
 * <p>選用 annotation 而非 yaml 路徑清單：限流規則跟著 handler 走，新增端點時在程式碼上就看得到，
 * 且能用 ArchUnit 式守衛測試檢查「指定端點必須帶規則」。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RateLimited {

    /**
     * 規則名稱（yaml 的 {@code app.rate-limit.rules} 的 key，例如 {@code comment-write}）。
     *
     * @return 規則名稱
     */
    String value();
}
