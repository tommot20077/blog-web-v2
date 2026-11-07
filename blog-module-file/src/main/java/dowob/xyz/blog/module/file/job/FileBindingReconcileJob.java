package dowob.xyz.blog.module.file.job;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.file.config.FileProperties;
import dowob.xyz.blog.module.file.service.FileBindingReconcileService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 每日綁定校正（B1）與未綁定檔清理（D6）排程：<b>先補綁定、再清理</b>。
 *
 * <p>業務邏輯在 {@link FileBindingReconcileService}（流程、去留規則與 fail-safe 見其 JavaDoc）；本類別負責排程時間、
 * Redis 分散式鎖（{@code setIfAbsent + TTL} 取鎖、token compare-and-delete 解鎖，寫法同
 * {@code OrphanReconciliationJob}）、清理游標、時間預算與指標。</p>
 *
 * <p><b>為何不繼承 {@code OrphanReconciliationJob}</b>：該框架的模型是「走訪本表被參照的 UUID，向 owner 確認存在性，
 * 刪除不存在者」；本任務是「補綁定 + 依年齡與引用清理」，沒有可對應的 owner 存在性判定，硬套會扭曲框架語意。
 * 同模組既有的 {@code FileArticleOrphanReconciliationJob} 處理「綁定的文章已被刪除」，與本 job 互補，鎖 key 不同、互不排擠。</p>
 *
 * <p><b>失敗語意</b>：補綁定階段列舉文章失敗 → 整次 {@code FAILED}、不執行清理；清理階段任何失敗 → {@code FAILED}、
 * 游標不推進（隔天從原處重來，已刪除的檔案自然不再出現）。{@link #run()} 永不外拋。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
public class FileBindingReconcileJob {

    /**
     * 排程時間：每日 04:17（伺服器時區），秒 分 時 日 月 週。
     *
     * <p>與既有排程錯開：孤兒校正 03:17／23／29／35／41／47／53／59、標籤用量 04:11、ES 孤兒 04:29、
     * 檔案孤兒 04:23、idempotency 清理 04:37，以及每小時的 :07（user-deleted 重送）。
     * 清理含 MinIO 網路呼叫，不與其他校正搶同一時段。</p>
     */
    public static final String CRON = "0 17 4 * * *";

    /** 分散式鎖 TTL（秒）；須大於單次執行的時間預算 */
    public static final long LOCK_TTL_SECONDS = 1800L;

    /** 時間預算占鎖 TTL 的比例：超過即不再處理剩餘部分，避免鎖在作業途中到期而與下一個實例重疊 */
    static final double TIME_BUDGET_FRACTION = 0.6;

    /** 單次補綁定處理文章數上限 */
    static final int MAX_REBIND_ARTICLES_PER_RUN = 20_000;

    /** 單次清理掃描檔案數上限（被引用而保留的檔案也計入；以游標分次掃完整張表） */
    static final int MAX_SCAN_PER_RUN = 10_000;

    /** 清理游標的保存期限 */
    private static final Duration CURSOR_TTL = Duration.ofDays(7);

    /** 鎖與游標 key 的表識別（與 FileArticleOrphanReconciliationJob 的 {@code file_metadata} 區隔） */
    private static final String TABLE = "file_metadata.unbound_cleanup";

    /** token compare-and-delete 解鎖腳本（不會刪到他人，例如 TTL 到期後被別的實例取得的鎖） */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
            Long.class);

    /** 執行結果 */
    public enum Result {
        /** 補綁定與清理都完整做完，整輪未綁定檔掃描完成 */
        COMPLETED,
        /** 做完但有剩餘（達上限／時間預算、游標未歸零，或有單篇回填失敗），下次續跑 */
        PARTIAL,
        /** 未取得分散式鎖，未處理任何資料 */
        SKIPPED,
        /** 發生例外；補綁定失敗時零清理、清理失敗時游標不推進 */
        FAILED;

        String tag() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final StringRedisTemplate redis;
    private final FileBindingReconcileService service;
    private final FileProperties.BindingReconcile settings;
    private final MeterRegistry registry;
    private final LongSupplier nanoClock;

    /**
     * 建立排程（Spring 使用）。指標走全域 registry（{@link Metrics#globalRegistry}），與其他校正 job 一致。
     *
     * @param redis      Redis 模板（分散式鎖與游標）
     * @param service    校正服務
     * @param properties 檔案模組設定（天數與刪除上限）
     */
    @Autowired
    public FileBindingReconcileJob(StringRedisTemplate redis, FileBindingReconcileService service, FileProperties properties) {
        this(redis, service, properties, Metrics.globalRegistry, System::nanoTime);
    }

    /**
     * 建立排程（測試用：可注入指標 registry 與時鐘）。
     *
     * @param redis      Redis 模板
     * @param service    校正服務
     * @param properties 檔案模組設定
     * @param registry   指標 registry
     * @param nanoClock  單調時鐘（奈秒）
     */
    FileBindingReconcileJob(StringRedisTemplate redis, FileBindingReconcileService service, FileProperties properties,
                            MeterRegistry registry, LongSupplier nanoClock) {
        this.redis = redis;
        this.service = service;
        this.settings = properties.bindingReconcile();
        this.registry = registry;
        this.nanoClock = nanoClock;
    }

    /** 每日排程入口：委派 {@link #run()}（結果與日誌由 run 記錄，run 不拋例外）。 */
    @Scheduled(cron = CRON)
    void scheduledRun() {
        run();
    }

    /**
     * 執行一次：取鎖 → 補綁定 → 清理 → 存游標 → 解鎖。
     *
     * @return 本次執行結果；本方法不拋出例外
     */
    public Result run() {
        String lockKey = RedisKeyConstant.getReconciliationLockKey("file", TABLE);
        String token = UUID.randomUUID().toString();
        boolean locked;
        try {
            locked = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL_SECONDS, TimeUnit.SECONDS));
        } catch (RuntimeException e) {
            log.warn("綁定校正取鎖時發生例外: {}", e.toString());
            return finish(Result.FAILED);
        }
        if (!locked) {
            log.debug("未取得分散式鎖，跳過本次綁定校正");
            return finish(Result.SKIPPED);
        }
        Result result;
        try {
            result = reconcile();
        } catch (RuntimeException e) {
            log.warn("綁定校正失敗（補綁定失敗時零清理；清理失敗時游標不推進）: {}", e.toString(), e);
            result = Result.FAILED;
        } finally {
            unlock(lockKey, token);
        }
        return finish(result);
    }

    private Result reconcile() {
        long start = nanoClock.getAsLong();
        long budgetNanos = (long) (TimeUnit.SECONDS.toNanos(LOCK_TTL_SECONDS) * TIME_BUDGET_FRACTION);
        BooleanSupplier timeUp = () -> nanoClock.getAsLong() - start > budgetNanos;
        LocalDateTime now = LocalDateTime.now();

        /* B1：補綁定。列舉文章失敗會拋出，使整次 FAILED 而不進入清理 */
        FileBindingReconcileService.RebindResult rebind = service.rebindRecentlyUpdatedArticles(
                now.minusDays(settings.recentArticleDays()), MAX_REBIND_ARTICLES_PER_RUN, timeUp);
        registry.counter("file.reconcile.rebind", "result", "rebound").increment(rebind.rebound());
        registry.counter("file.reconcile.rebind", "result", "failed").increment(rebind.failed());

        /* D6：清理。游標只在清理成功後才寫回 */
        String cursorKey = RedisKeyConstant.getReconciliationCursorKey("file", TABLE);
        FileBindingReconcileService.CleanupResult cleanup = service.cleanupUnboundFiles(
                now.minusDays(settings.unboundFileRetentionDays()), readCursor(cursorKey),
                settings.maxDeletesPerRun(), MAX_SCAN_PER_RUN, timeUp);
        registry.counter("file.reconcile.unbound_deleted").increment(cleanup.deleted());
        registry.counter("file.reconcile.unbound_kept_referenced").increment(cleanup.keptReferenced());
        if (cleanup.nextCursor() == null) {
            redis.delete(cursorKey);
        } else {
            redis.opsForValue().set(cursorKey, cleanup.nextCursor().toString(), CURSOR_TTL);
        }

        boolean partial = rebind.truncated() || rebind.failed() > 0 || cleanup.truncated() || cleanup.nextCursor() != null;
        return partial ? Result.PARTIAL : Result.COMPLETED;
    }

    private UUID readCursor(String cursorKey) {
        String raw = redis.opsForValue().get(cursorKey);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            log.warn("綁定校正游標內容損毀，改從頭開始: {}", raw);
            return null;
        }
    }

    private Result finish(Result result) {
        registry.counter("file.reconcile.runs", "result", result.tag()).increment();
        log.info("綁定校正執行結束 result={}", result.tag());
        return result;
    }

    private void unlock(String lockKey, String token) {
        try {
            redis.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
        } catch (RuntimeException e) {
            log.warn("綁定校正解鎖失敗（鎖將於 TTL 到期後自動釋放）: {}", e.getMessage());
        }
    }
}
