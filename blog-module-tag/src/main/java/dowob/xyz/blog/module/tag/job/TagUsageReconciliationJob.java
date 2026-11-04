package dowob.xyz.blog.module.tag.job;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.tag.repository.TagRepository;
import dowob.xyz.blog.module.tag.service.TagUsageRecalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 標籤使用計數每日全量校正（DATA-01）：掃過所有標籤，重算 {@code tags.usage_count}（已發布文章數）。
 *
 * <p>事件觸發的重算（{@code TagUsageConsumer}、{@code TagArticleLifecycleConsumer}、
 * {@code TagArticleDeletedConsumer}）是 best-effort——事件可能遺失、進 DLQ，或 producer 端
 * 發送失敗只記 warn；本排程是第二道保險，也負責把舊實作留下的灌水計數一次修正回來。
 * 寫法比照 {@code TrendingRefreshJob}：Redis 分散式鎖確保多實例只有一個在跑。</p>
 *
 * <p>掃描以 keyset 分頁（依標籤 id 升冪、每頁 {@link #PAGE_SIZE} 個標籤），每頁一個獨立交易
 * （{@link TagUsageRecalculator#recalculate} 的列鎖只在該頁期間持有）。某頁失敗只記 error 並繼續下一頁，
 * 隔天會再試；整個執行不外拋例外。</p>
 *
 * <p><b>規模界限</b>：同 {@link TagUsageRecalculator}——單一標籤超過約 1 萬篇文章時，
 * 應改為增量計數＋定期校正，本排程則退為只做校正。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
public class TagUsageReconciliationJob {

    /**
     * 排程時間：每日 04:11（伺服器時區），秒 分 時 日 月 週。
     *
     * <p>與既有各校正排程（03:17／23／29／35／41／47／53／59）錯開，並排在全部孤兒校正之後——
     * 孤兒關聯先被清掉，這裡才計得準。排程執行緒池為單執行緒（PERF-16），同時觸發只會互相排隊。</p>
     */
    public static final String CRON = "0 11 4 * * *";

    /** 每頁掃描的標籤數（每頁一個交易；每個標籤各查一次關聯與一次已發布判定） */
    public static final int PAGE_SIZE = 50;

    /** 分散式鎖 TTL（秒） */
    private static final long LOCK_TTL_SECONDS = 1800L;

    /** keyset 分頁起點：nil UUID 在 Postgres 的 uuid 排序中最小 */
    private static final UUID START_CURSOR = new UUID(0L, 0L);

    /** token compare-and-delete 解鎖腳本（不會刪到他人，例如 TTL 到期後被別的實例取得的鎖） */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
            Long.class);

    /**
     * 執行結果。
     *
     * @param executed    是否取得鎖並執行（false 表示別的實例正在跑，本次跳過）
     * @param scanned     掃描（含重算失敗頁）的標籤數
     * @param corrected   計數被修正的標籤數
     * @param failedPages 重算失敗的頁數
     */
    public record Result(boolean executed, int scanned, int corrected, int failedPages) {
    }

    private final TagRepository tagRepository;
    private final TagUsageRecalculator recalculator;
    private final StringRedisTemplate redis;
    private final int pageSize;

    /**
     * 建立排程（Spring 使用）。
     *
     * @param tagRepository 標籤資料存取
     * @param recalculator  標籤使用計數重算元件
     * @param redis         Redis 模板（分散式鎖）
     */
    @Autowired
    public TagUsageReconciliationJob(TagRepository tagRepository, TagUsageRecalculator recalculator,
                                     StringRedisTemplate redis) {
        this(tagRepository, recalculator, redis, PAGE_SIZE);
    }

    /**
     * 建立排程（可指定頁大小，測試用）。
     *
     * @param tagRepository 標籤資料存取
     * @param recalculator  標籤使用計數重算元件
     * @param redis         Redis 模板（分散式鎖）
     * @param pageSize      每頁標籤數
     */
    TagUsageReconciliationJob(TagRepository tagRepository, TagUsageRecalculator recalculator,
                              StringRedisTemplate redis, int pageSize) {
        this.tagRepository = tagRepository;
        this.recalculator = recalculator;
        this.redis = redis;
        this.pageSize = pageSize;
    }

    /** 每日排程入口：委派 {@link #run()}，任何例外只記錄、不外拋（避免影響排程執行緒）。 */
    @Scheduled(cron = CRON)
    void scheduledRun() {
        try {
            run();
        } catch (RuntimeException e) {
            log.error("標籤使用計數每日校正失敗", e);
        }
    }

    /**
     * 執行一次全量校正。
     *
     * @return 執行結果；取不到鎖時 {@code executed=false}
     */
    public Result run() {
        String lockKey = RedisKeyConstant.getReconciliationLockKey("tag", "tags.usage_count");
        String token = UUID.randomUUID().toString();
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("未取得分散式鎖，跳過本次標籤使用計數校正");
            return new Result(false, 0, 0, 0);
        }
        try {
            return scanAllTags();
        } finally {
            unlock(lockKey, token);
        }
    }

    /**
     * 以 keyset 分頁掃過所有標籤並逐頁重算。
     *
     * @return 執行結果
     */
    private Result scanAllTags() {
        int scanned = 0;
        int corrected = 0;
        int failedPages = 0;
        UUID cursor = START_CURSOR;
        while (true) {
            List<UUID> page = tagRepository.findIdsAfter(cursor, pageSize);
            if (page.isEmpty()) {
                break;
            }
            scanned += page.size();
            try {
                corrected += recalculator.recalculate(page);
            } catch (RuntimeException e) {
                failedPages++;
                log.error("標籤使用計數校正某頁失敗（隔天會再試）：起始游標={} 筆數={}", cursor, page.size(), e);
            }
            cursor = page.get(page.size() - 1);
            if (page.size() < pageSize) {
                break;
            }
        }
        log.info("標籤使用計數每日校正完成 scanned={} corrected={} failedPages={}", scanned, corrected, failedPages);
        return new Result(true, scanned, corrected, failedPages);
    }

    /**
     * 以 token compare-and-delete 解鎖；失敗只記錄（鎖有 TTL 兜底）。
     *
     * @param lockKey 鎖 key
     * @param token   取鎖時的 token
     */
    private void unlock(String lockKey, String token) {
        try {
            redis.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
        } catch (RuntimeException e) {
            log.warn("標籤使用計數校正解鎖失敗（鎖將於 TTL 到期後自動釋放）: {}", e.getMessage());
        }
    }
}
