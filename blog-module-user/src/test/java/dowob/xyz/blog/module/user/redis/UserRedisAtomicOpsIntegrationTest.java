package dowob.xyz.blog.module.user.redis;

import dowob.xyz.blog.module.user.service.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserRedisAtomicOps} 整合測試（真 Redis，驗證 Lua 腳本行為）。
 *
 * <p>涵蓋 RACE-11（INCR 後 EXPIRE 非原子，key 可能永遠沒有 TTL）與
 * RACE-10（登入裝置數修剪非原子）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("UserRedisAtomicOps 整合測試（真 Redis Lua）")
class UserRedisAtomicOpsIntegrationTest extends AbstractIntegrationTest {

    /** 被測元件 */
    @Autowired
    private UserRedisAtomicOps ops;

    /** Redis 模板（準備資料與驗證結果） */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 每個測試獨立的 key，避免互相干擾 */
    private String key;

    @BeforeEach
    void setUp() {
        key = "test:atomic:" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(key);
    }

    @Test
    @DisplayName("RACE-11：新 key 第一次計數回 1 且帶有 TTL")
    void incrementWithTtl_newKey_returnsOneAndHasTtl() {
        long count = ops.incrementWithTtl(key, Duration.ofMinutes(15));

        assertThat(count).isEqualTo(1L);
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 900L);
    }

    @Test
    @DisplayName("RACE-11：key 已存在但沒有 TTL（先前 EXPIRE 失敗）→ 計數後必須補上 TTL")
    void incrementWithTtl_existingKeyWithoutTtl_getsTtl() {
        redisTemplate.opsForValue().set(key, "5");
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isEqualTo(-1L);

        long count = ops.incrementWithTtl(key, Duration.ofMinutes(15));

        assertThat(count).isEqualTo(6L);
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 900L);
    }

    @Test
    @DisplayName("RACE-11：已有 TTL 的 key 計數時不得重設 TTL（固定窗口語意不變）")
    void incrementWithTtl_existingTtl_isNotReset() {
        redisTemplate.opsForValue().set(key, "1", 100, TimeUnit.SECONDS);

        long count = ops.incrementWithTtl(key, Duration.ofHours(1));

        assertThat(count).isEqualTo(2L);
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 100L);
    }

    @Test
    @DisplayName("RACE-10：已有 3 個裝置時加入第 4 個 → 剩 3 個、被移除的是最舊的、key 有 TTL")
    void registerRefreshToken_fourthDevice_evictsOldest() {
        ops.registerRefreshToken(key, "t1", 1_000L, 3, Duration.ofDays(7));
        ops.registerRefreshToken(key, "t2", 2_000L, 3, Duration.ofDays(7));
        ops.registerRefreshToken(key, "t3", 3_000L, 3, Duration.ofDays(7));

        ops.registerRefreshToken(key, "t4", 4_000L, 3, Duration.ofDays(7));

        Set<String> remaining = redisTemplate.opsForZSet().range(key, 0, -1);
        assertThat(remaining).containsExactly("t2", "t3", "t4");
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 7L * 86400);
    }

    @Test
    @DisplayName("RACE-10：未達上限時全部保留")
    void registerRefreshToken_underLimit_keepsAll() {
        ops.registerRefreshToken(key, "t1", 1_000L, 3, Duration.ofDays(7));
        ops.registerRefreshToken(key, "t2", 2_000L, 3, Duration.ofDays(7));

        assertThat(redisTemplate.opsForZSet().range(key, 0, -1)).containsExactly("t1", "t2");
    }

    @Test
    @DisplayName("RACE-10：key 內已超過上限（先前競態殘留）→ 一次修剪回上限，保留最新的")
    void registerRefreshToken_alreadyOverLimit_trimsToLimitKeepingNewest() {
        for (int i = 1; i <= 5; i++) {
            redisTemplate.opsForZSet().add(key, "old" + i, i * 1_000L);
        }

        ops.registerRefreshToken(key, "new", 9_000L, 3, Duration.ofDays(7));

        assertThat(redisTemplate.opsForZSet().range(key, 0, -1)).containsExactly("old4", "old5", "new");
    }
}
