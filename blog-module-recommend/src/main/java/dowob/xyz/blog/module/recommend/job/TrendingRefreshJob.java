package dowob.xyz.blog.module.recommend.job;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleTrendingData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 熱門文章排行定期更新排程
 *
 * <p>
 * 每 30 分鐘執行一次，依時間衰減演算法重新計算各週期熱門分數，
 * 並更新 Redis ZSet。
 * 衰減公式：{@code score = (viewCount + likeCount * 3) * exp(-λ * ageInDays)}
 * 其中 λ = ln(2) / halfLifeDays（半衰期：24h=1d, 7d=3.5d, 30d=15d）。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrendingRefreshJob {

    /**
     * 文章跨模組 Facade
     */
    private final ArticleFacade articleFacade;

    /**
     * Redis 操作模板
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 分散式鎖 TTL（秒）
     *
     * <p>每次執行為 3 個週期各 1 次全表範圍查詢＋1 次 ZADD，正常僅數秒，慢查詢時也遠低於一分鐘；
     * 取 600 秒是最壞耗時的一個數量級以上緩衝（原 120 秒在 DB 變慢時有執行中過期的風險）。
     * 排程間隔為 30 分鐘，TTL 拉長只影響「持鎖實例當機」時的恢復，最多多等 10 分鐘，仍早於下次排程。
     * 不做續期：即使鎖仍意外過期，tmp key 每次唯一、RENAME 原子、釋放鎖只刪自己的 token，
     * 兩個執行重疊最多是重複計算，不會互相破壞資料。</p>
     */
    private static final long LOCK_TTL_SECONDS = 600L;

    /**
     * 暫存 key 的安全 TTL（分鐘），僅用於程序崩潰時避免殘留
     */
    private static final long TMP_KEY_TTL_MINUTES = 10L;

    /**
     * 原子 RENAME 並移除 TTL：KEYS[1]=tmp，KEYS[2]=正式 key
     */
    private static final RedisScript<Long> RENAME_AND_PERSIST_SCRIPT = new DefaultRedisScript<>(
            "redis.call('RENAME', KEYS[1], KEYS[2]) return redis.call('PERSIST', KEYS[2])",
            Long.class);

    /**
     * 各週期配置：period 名稱 → 查詢天數範圍（天）與半衰期（天）
     */
    private static final Map<String, long[]> PERIOD_CONFIG = Map.of(
            "24h", new long[]{1L, 1L},
            "7d",  new long[]{7L, 4L},
            "30d", new long[]{30L, 15L}
    );

    /**
     * 定期重新計算各週期熱門文章排行分數
     *
     * <p>每 30 分鐘執行，使用 Redis 分散式鎖確保多實例環境下只有一個實例執行。</p>
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
            Long.class);

    @Scheduled(fixedDelay = 1800000)
    public void refreshTrending() {
        String lockValue = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(RedisKeyConstant.LOCK_TRENDING_REFRESH, lockValue, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("未取得分散式鎖，跳過本次熱門排行更新");
            return;
        }
        try {
            log.info("開始更新熱門文章排行...");
            PERIOD_CONFIG.forEach((period, config) -> {
                try {
                    refreshPeriod(period, config[0], config[1]);
                } catch (Exception e) {
                    log.error("更新熱門排行失敗，period={}: {}", period, e.getMessage(), e);
                }
            });
            log.info("熱門文章排行更新完成");
        } finally {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, List.of(RedisKeyConstant.LOCK_TRENDING_REFRESH), lockValue);
        }
    }

    /**
     * 計算指定週期的熱門分數並更新 Redis ZSet
     *
     * @param period       週期名稱（如 {@code 24h}）
     * @param lookbackDays 查詢的天數範圍
     * @param halfLifeDays 衰減半衰期（天）
     */
    void refreshPeriod(String period, long lookbackDays, long halfLifeDays) {
        LocalDateTime since = LocalDateTime.now().minusDays(lookbackDays);
        List<ArticleTrendingData> articles = articleFacade.getArticlesPublishedAfter(since);

        if (articles.isEmpty()) {
            return;
        }

        double lambda = Math.log(2.0) / halfLifeDays;
        LocalDateTime now = LocalDateTime.now();
        String key = RedisKeyConstant.getTrendingKey(period);

        Set<ZSetOperations.TypedTuple<String>> tuples = new java.util.HashSet<>();
        for (ArticleTrendingData data : articles) {
            double ageInDays = java.time.Duration.between(data.publishedAt(), now).toHours() / 24.0;
            double score = (data.viewCount() + data.likeCount() * 3.0)
                    * Math.exp(-lambda * ageInDays);
            tuples.add(ZSetOperations.TypedTuple.of(data.uuid().toString(), score));
        }

        /* tmp key 每次執行唯一，避免鎖意外過期時兩個執行共用同一個 tmp 而互相覆蓋；失敗時 finally 清除，
           成功時 RENAME 已移走 tmp，delete 只是無害的 no-op */
        String tmpKey = key + ":tmp:" + UUID.randomUUID();
        try {
            stringRedisTemplate.opsForZSet().add(tmpKey, tuples);
            /* JVM 被 kill／OOM 時 finally 不會執行，帶 UUID 的 tmp 會永久殘留；先設短 TTL 讓它自行過期 */
            stringRedisTemplate.expire(tmpKey, TMP_KEY_TTL_MINUTES, TimeUnit.MINUTES);
            /* RENAME 會把 tmp 的 TTL 一併帶到正式 key；正式 key 原本無 TTL，故同一腳本內 PERSIST，保持原語意且原子 */
            stringRedisTemplate.execute(RENAME_AND_PERSIST_SCRIPT, List.of(tmpKey, key));
        } finally {
            try {
                stringRedisTemplate.delete(tmpKey);
            } catch (Exception e) {
                log.warn("清除熱門排行暫存 key 失敗（tmp 帶 TTL，會自行過期），tmpKey={}: {}", tmpKey, e.getMessage());
            }
        }
        log.debug("熱門排行更新完成，period={}，文章數={}", period, articles.size());
    }
}
