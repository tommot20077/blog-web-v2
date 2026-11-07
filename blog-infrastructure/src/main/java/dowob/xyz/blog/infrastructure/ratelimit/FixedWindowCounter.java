package dowob.xyz.blog.infrastructure.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

/**
 * Redis 固定窗口計數器（全專案固定窗口計數 Lua 的唯一一份）。
 *
 * <p>腳本在 Redis 內原子完成「{@code INCR}；TTL 為 -1（新建，或先前 EXPIRE 失敗）才補上 {@code EXPIRE}；
 * 回傳計數與剩餘毫秒數」。已有 TTL 時不重設，維持固定窗口語意；因為每次呼叫都檢查 TTL，
 * 即使某次 EXPIRE 失敗，下一次呼叫就會自我修復（RACE-11：否則計數器永不過期等於永久封鎖）。</p>
 *
 * <p>user 模組的 {@code UserRedisAtomicOps#incrementWithTtl} 也透過 {@link #INCREMENT_WITH_TTL} 執行同一份腳本，
 * 不再各自持有一份 Lua。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class FixedWindowCounter {

    /**
     * 計數加一並確保 TTL。KEYS[1] = 計數 key，ARGV[1] = 窗口秒數。
     * 回傳 {計數, 剩餘毫秒數}（兩者皆為整數）。
     */
    @SuppressWarnings("rawtypes")
    public static final RedisScript<List> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if redis.call('TTL', KEYS[1]) == -1 then
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
            end
            return {count, redis.call('PTTL', KEYS[1])}
            """, List.class);

    /** Redis 模板 */
    private final StringRedisTemplate redisTemplate;

    /**
     * 建構計數器。
     *
     * @param redisTemplate Redis 模板
     */
    public FixedWindowCounter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 一次計數結果。
     *
     * @param count             加一後的窗口內計數
     * @param retryAfterSeconds 窗口剩餘秒數（無條件進位，至少 1），用於 {@code Retry-After}
     */
    public record Hit(long count, long retryAfterSeconds) {
    }

    /**
     * 計數加一，並回傳計數與窗口剩餘秒數。
     *
     * @param key    計數 key
     * @param window 窗口長度（至少 1 秒）
     * @return 計數結果
     */
    public Hit hit(String key, Duration window) {
        List<?> result = redisTemplate.execute(INCREMENT_WITH_TTL, List.of(key),
                String.valueOf(seconds(window)));
        if (result == null || result.size() < 2) {
            throw new IllegalStateException("限流計數腳本回傳格式異常：" + result);
        }
        long count = ((Number) result.get(0)).longValue();
        long pttlMillis = ((Number) result.get(1)).longValue();
        long retryAfter = pttlMillis > 0 ? (pttlMillis + 999) / 1000 : seconds(window);
        return new Hit(count, Math.max(1L, retryAfter));
    }

    /**
     * 將 {@link Duration} 轉成至少 1 的秒數（EXPIRE 0 會直接刪 key）。
     *
     * @param window 時間長度
     * @return 秒數
     */
    static long seconds(Duration window) {
        return Math.max(1L, window.toSeconds());
    }
}
