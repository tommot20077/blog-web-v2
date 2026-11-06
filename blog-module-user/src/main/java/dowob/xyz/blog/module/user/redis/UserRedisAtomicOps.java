package dowob.xyz.blog.module.user.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * user 模組的 Redis 原子操作（以 Lua 腳本讓多個指令在 Redis 內一次完成）。
 *
 * <p>放在 user 模組而非 blog-infrastructure：目前只有認證流程（登入失敗鎖定、驗證碼、忘記密碼、
 * 重寄驗證、IP 限流、登入裝置數）使用，沿用專案「腳本跟著使用它的模組」的慣例
 * （reading、recommend、tag 模組亦各自持有自己的腳本）；若其他模組日後需要同樣的計數語意，
 * 再抽到 blog-infrastructure。</p>
 *
 * <ul>
 *   <li>RACE-11：{@code INCR} 與 {@code EXPIRE} 分兩次呼叫時，若 EXPIRE 失敗（連線中斷、逾時）
 *       計數器就永遠沒有 TTL，登入失敗次數 ≥ 5 等於永久鎖定。本類別的腳本在同一次原子執行中
 *       遞增並在 TTL 為 -1（無過期）時補設，因此後續任何一次呼叫都能自我修復。</li>
 *   <li>RACE-10：登入裝置數修剪原本是 {@code ZADD → ZCARD → ZPOPMIN → EXPIRE} 四個獨立指令，
 *       併發登入時可能短暫超過上限或多踢。改為單一腳本。</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class UserRedisAtomicOps {

    /**
     * 計數加一；若 key 沒有 TTL（新建，或先前的 EXPIRE 失敗）就補上 TTL。已有 TTL 時不重設，
     * 維持固定窗口語意。KEYS[1] = 計數 key，ARGV[1] = TTL 秒數。回傳加一後的計數。
     */
    private static final RedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if redis.call('TTL', KEYS[1]) == -1 then
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
            end
            return count
            """, Long.class);

    /**
     * 登記 refresh token 並修剪裝置數：ZADD → 只保留分數最高（最新）的 N 個 → EXPIRE。
     * KEYS[1] = ZSet key，ARGV[1] = 分數，ARGV[2] = token，ARGV[3] = 最多保留數，ARGV[4] = TTL 秒數。
     * 回傳修剪後的裝置數。
     */
    private static final RedisScript<Long> REGISTER_REFRESH_TOKEN = new DefaultRedisScript<>("""
            redis.call('ZADD', KEYS[1], tonumber(ARGV[1]), ARGV[2])
            local max = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYRANK', KEYS[1], 0, -(max + 1))
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
            return redis.call('ZCARD', KEYS[1])
            """, Long.class);

    /** Redis 模板 */
    private final StringRedisTemplate redisTemplate;

    /**
     * 計數加一，並確保 key 帶有 TTL（原子）。
     *
     * @param key 計數 key
     * @param ttl 窗口長度（至少 1 秒）
     * @return 加一後的計數
     */
    public long incrementWithTtl(String key, Duration ttl) {
        Long count = redisTemplate.execute(INCREMENT_WITH_TTL, List.of(key), String.valueOf(seconds(ttl)));
        return count == null ? 0L : count;
    }

    /**
     * 登記一個 refresh token，並把裝置數修剪到上限（保留最新的 N 個，踢掉最舊的）。
     *
     * <p>被踢掉的 token 只是從 ZSet 移除；refresh 流程以 ZSet 成員資格判斷 token 是否有效，
     * 因此移除即等於撤銷，沒有其他副作用需要保留。</p>
     *
     * @param key         refresh token ZSet key
     * @param token       新 refresh token
     * @param scoreMillis 分數（建立時間毫秒）
     * @param maxDevices  最多保留的裝置數
     * @param ttl         key 的 TTL
     */
    public void registerRefreshToken(String key, String token, long scoreMillis, int maxDevices, Duration ttl) {
        redisTemplate.execute(REGISTER_REFRESH_TOKEN, List.of(key),
                String.valueOf(scoreMillis), token, String.valueOf(maxDevices), String.valueOf(seconds(ttl)));
    }

    /**
     * 將 {@link Duration} 轉成至少 1 的秒數（EXPIRE 0 會直接刪 key）。
     *
     * @param ttl 時間長度
     * @return 秒數
     */
    private static long seconds(Duration ttl) {
        return Math.max(1L, ttl.toSeconds());
    }
}
