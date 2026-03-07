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
     */
    private static final long LOCK_TTL_SECONDS = 120L;

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

        String tmpKey = key + ":tmp";
        stringRedisTemplate.delete(tmpKey);
        stringRedisTemplate.opsForZSet().add(tmpKey, tuples);
        stringRedisTemplate.rename(tmpKey, key);
        log.debug("熱門排行更新完成，period={}，文章數={}", period, articles.size());
    }
}
