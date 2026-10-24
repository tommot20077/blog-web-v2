package dowob.xyz.blog.infrastructure.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 孤兒校正排程框架。
 *
 * <p>跨模組 FK 拿掉後，owner 模組的資料被刪除時，其他模組只能靠事件連動；事件遺失就會留下
 * 「參照了已不存在 owner」的孤兒。本框架提供每日校正的共通骨架：走訪本模組表中的對外 UUID 欄位，
 * 以 owner facade 分批確認存在性，把不存在者交給子類別處理，並留下計數與日誌。</p>
 *
 * <h3>子類別需提供</h3>
 * <ul>
 *   <li>{@link #module()}、{@link #table()}：識別（用於鎖 key、游標 key、指標 tag 與日誌）</li>
 *   <li>{@link #fetchReferencedUuids(UUID, int)}：keyset 分頁取得本表被參照的 UUID（升冪、去重）</li>
 *   <li>{@link #filterExisting(Collection)}：委派 owner facade 查存在性</li>
 *   <li>{@link #removeOrphans(Collection)}：處理孤兒——語意見該方法 JavaDoc</li>
 * </ul>
 *
 * <p>排程由子類別以 {@code @Scheduled} 觸發並呼叫 {@link #run()}；本類別不新增任何具體子類別於 src/main
 * （具體 job 由各模組的 PR 提供）。</p>
 *
 * <h3>框架負責</h3>
 * <ul>
 *   <li>Redis 分散式鎖：{@code setIfAbsent + TTL} 取鎖，以 <b>token compare-and-delete</b>（Lua）解鎖，
 *       不會刪到他人（例如 TTL 到期後被別的實例取得）的鎖；鎖 key 含 module 與 table</li>
 *   <li>逐頁處理，且<b>單次執行頁數有上限</b>（{@link #MAX_PAGES_PER_RUN}）：超過即結束本次（result=partial），
 *       游標存入 Redis，下次從游標續跑；掃完整張表後清除游標。避免長掃描長時間佔住排程執行緒（PERF-16）</li>
 *   <li>fail-safe：<b>先掃描、後修改</b>。掃描階段（fetch／owner facade）任何例外都使整次中止、
 *       完全不呼叫 {@code removeOrphans}、游標不推進，result=failed——owner 暫時不可用時絕不能把「查不到」
 *       當成「不存在」而誤刪</li>
 *   <li>指標：{@code reconciliation.orphans_removed{module,table}}、
 *       {@code reconciliation.runs{module,table,result}}（result：completed／partial／skipped／failed）</li>
 *   <li>每次執行輸出恰好一行摘要日誌</li>
 * </ul>
 *
 * <p>{@link #run()} 永不拋出例外（排程執行緒不應因此中斷）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
public abstract class OrphanReconciliationJob {

    /** 單頁筆數預設值（與 {@code BatchedQuery.BATCH_SIZE} 一致，owner 端查詢不會超出參數上限） */
    public static final int PAGE_SIZE = 500;

    /** 單次執行處理頁數上限預設值（200 頁 × 500 筆 = 10 萬筆）；超過即結束本次，下次從游標續跑 */
    public static final int MAX_PAGES_PER_RUN = 200;

    /** 分散式鎖 TTL（秒）；須大於單次執行（至多 {@link #MAX_PAGES_PER_RUN} 頁）的合理耗時 */
    public static final long LOCK_TTL_SECONDS = 600L;

    /** 續跑游標的保存期限，避免遺留過期游標 */
    static final Duration CURSOR_TTL = Duration.ofDays(7);

    /** 鎖 key 前綴；完整 key：{@code lock:reconciliation:{module}:{table}} */
    static final String LOCK_KEY_PREFIX = "lock:reconciliation:";

    /** 游標 key 前綴；完整 key：{@code reconciliation:cursor:{module}:{table}} */
    static final String CURSOR_KEY_PREFIX = "reconciliation:cursor:";

    /** 指標：移除的孤兒數 */
    static final String METRIC_ORPHANS_REMOVED = "reconciliation.orphans_removed";

    /** 指標：執行次數（依結果分類） */
    static final String METRIC_RUNS = "reconciliation.runs";

    /** token compare-and-delete：僅當 key 的值等於自己的 token 才刪除 */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry registry;
    private final int pageSize;
    private final int maxPagesPerRun;

    /**
     * 以預設頁大小與單次頁數上限建立。
     *
     * @param redis    Redis 操作模板（鎖與游標）
     * @param registry 指標註冊表
     */
    protected OrphanReconciliationJob(StringRedisTemplate redis, MeterRegistry registry) {
        this(redis, registry, PAGE_SIZE, MAX_PAGES_PER_RUN);
    }

    /**
     * 指定頁大小與單次頁數上限建立（主要供測試縮小參數）。
     *
     * @param redis          Redis 操作模板（鎖與游標）
     * @param registry       指標註冊表
     * @param pageSize       單頁筆數，須為正數
     * @param maxPagesPerRun 單次執行頁數上限，須為正數
     */
    protected OrphanReconciliationJob(StringRedisTemplate redis, MeterRegistry registry,
                                      int pageSize, int maxPagesPerRun) {
        if (pageSize <= 0 || maxPagesPerRun <= 0) {
            throw new IllegalArgumentException("pageSize 與 maxPagesPerRun 必須為正數");
        }
        this.redis = redis;
        this.registry = registry;
        this.pageSize = pageSize;
        this.maxPagesPerRun = maxPagesPerRun;
    }

    /**
     * 一次校正執行的結果。
     */
    public enum Result {
        /** 掃完整張表 */
        COMPLETED,
        /** 達到單次頁數上限，游標已保存，下次續跑 */
        PARTIAL,
        /** 未取得分散式鎖，未處理任何資料 */
        SKIPPED,
        /** 掃描或移除過程發生例外；掃描階段失敗時零修改 */
        FAILED;

        /**
         * @return 指標 tag 用的小寫名稱
         */
        String tag() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * 本任務所屬模組名稱（用於鎖 key、指標 tag 與日誌）。
     *
     * @return 模組名稱，例如 {@code article}
     */
    protected abstract String module();

    /**
     * 本任務校正的表名（用於鎖 key、指標 tag 與日誌）。
     *
     * @return 表名，例如 {@code articles}
     */
    protected abstract String table();

    /**
     * 以 keyset 分頁取得本模組表中被參照的對外 UUID。
     *
     * <p>實作須回傳大於 {@code afterExclusive} 的 UUID，依 UUID 升冪、去重，至多 {@code limit} 筆；
     * {@code afterExclusive} 為 null 表示從頭開始。回傳筆數少於 {@code limit} 即視為最後一頁。</p>
     *
     * @param afterExclusive 游標（不含）；null 表示從頭
     * @param limit          本頁筆數上限
     * @return 升冪的 UUID 清單，無資料時為空清單
     */
    protected abstract List<UUID> fetchReferencedUuids(UUID afterExclusive, int limit);

    /**
     * 查詢哪些 UUID 在 owner 模組仍然存在（委派 owner facade）。
     *
     * <p>owner 不可用時<b>必須拋出例外</b>，不得回傳空集合：框架會把例外視為整次中止（零修改），
     * 而空集合會被當成「全部都是孤兒」。</p>
     *
     * @param uuids 待確認的 UUID
     * @return 仍存在的 UUID 子集
     */
    protected abstract Set<UUID> filterExisting(Collection<UUID> uuids);

    /**
     * 處理已確認不存在於 owner 的 UUID 所對應的孤兒資料。
     *
     * <p><b>依欄位語意，「刪除該列」與「把參照欄位設為 NULL」兩種處理都是合法的</b>：</p>
     * <ul>
     *   <li>參照屬於該列存在的前提（例如 {@code comments.article_uuid}、{@code user_bookmarks.article_uuid}）
     *       → 刪除該列</li>
     *   <li>參照只是可選的關聯（例如 {@code articles.series_uuid}）→ 僅把該欄位設為 NULL，
     *       <b>絕不可刪除文章</b>（文章本身仍是有效資料）</li>
     * </ul>
     * <p>實作必須冪等（重跑不會出錯）。框架保證只會傳入已確認不存在者，且單次傳入筆數不超過頁大小。</p>
     *
     * @param missing 已確認不存在於 owner 的 UUID
     * @return 實際刪除或清空的筆數（計入 {@code reconciliation.orphans_removed}）
     */
    protected abstract int removeOrphans(Collection<UUID> missing);

    /**
     * 執行一次校正。供子類別的 {@code @Scheduled} 方法呼叫。
     *
     * @return 本次執行結果；本方法不拋出例外
     */
    public final Result run() {
        String lockKey = LOCK_KEY_PREFIX + module() + ":" + table();
        String token = UUID.randomUUID().toString();
        RunStats stats = new RunStats();
        Result result;

        boolean locked;
        try {
            locked = Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL_SECONDS, TimeUnit.SECONDS));
        } catch (RuntimeException e) {
            log.warn("孤兒校正取鎖時發生例外 module={} table={}: {}", module(), table(), e.toString());
            return finish(Result.FAILED, stats);
        }
        if (!locked) {
            return finish(Result.SKIPPED, stats);
        }

        try {
            result = reconcile(stats);
        } catch (RuntimeException e) {
            log.warn("孤兒校正失敗 module={} table={}: {}", module(), table(), e.toString(), e);
            result = Result.FAILED;
        } finally {
            unlock(lockKey, token);
        }
        return finish(result, stats);
    }

    /**
     * 先掃描收集孤兒，掃描全部成功後才進行移除並推進游標。
     *
     * @param stats 本次統計（會被更新）
     * @return COMPLETED 或 PARTIAL；掃描失敗以例外離開（此時尚未修改任何資料）
     */
    private Result reconcile(RunStats stats) {
        String cursorKey = CURSOR_KEY_PREFIX + module() + ":" + table();
        String saved = redis.opsForValue().get(cursorKey);
        UUID after = saved == null || saved.isBlank() ? null : UUID.fromString(saved);

        // 掃描階段：只讀，任何例外都會使整次中止且零修改
        List<UUID> missing = new ArrayList<>();
        boolean finished = false;
        while (stats.pages < maxPagesPerRun) {
            List<UUID> page = fetchReferencedUuids(after, pageSize);
            stats.pages++;
            if (page.isEmpty()) {
                finished = true;
                break;
            }
            Set<UUID> existing = filterExisting(page);
            Set<UUID> existingSet = existing == null ? Set.of() : new HashSet<>(existing);
            for (UUID uuid : page) {
                if (!existingSet.contains(uuid)) {
                    missing.add(uuid);
                }
            }
            stats.scanned += page.size();
            after = page.get(page.size() - 1);
            if (page.size() < pageSize) {
                finished = true;
                break;
            }
        }
        stats.orphansFound = missing.size();

        // 修改階段：掃描已全部成功
        for (int from = 0; from < missing.size(); from += pageSize) {
            List<UUID> chunk = missing.subList(from, Math.min(from + pageSize, missing.size()));
            stats.orphansRemoved += removeOrphans(chunk);
        }

        if (finished) {
            redis.delete(cursorKey);
            return Result.COMPLETED;
        }
        redis.opsForValue().set(cursorKey, after.toString(), CURSOR_TTL);
        return Result.PARTIAL;
    }

    /**
     * 以 token compare-and-delete 解鎖；失敗只記錄，不影響結果（鎖有 TTL 兜底）。
     *
     * @param lockKey 鎖 key
     * @param token   取鎖時寫入的 token
     */
    private void unlock(String lockKey, String token) {
        try {
            redis.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
        } catch (RuntimeException e) {
            log.warn("孤兒校正解鎖失敗（鎖將於 TTL 到期後自動釋放）module={} table={}: {}",
                    module(), table(), e.toString());
        }
    }

    /**
     * 記錄指標與唯一一行摘要日誌。
     *
     * @param result 結果
     * @param stats  統計
     * @return 原結果
     */
    private Result finish(Result result, RunStats stats) {
        registry.counter(METRIC_ORPHANS_REMOVED, "module", module(), "table", table())
                .increment(stats.orphansRemoved);
        registry.counter(METRIC_RUNS, "module", module(), "table", table(), "result", result.tag()).increment();
        log.info("孤兒校正摘要 module={} table={} result={} pages={} scanned={} orphansFound={} orphansRemoved={}",
                module(), table(), result.tag(), stats.pages, stats.scanned, stats.orphansFound,
                stats.orphansRemoved);
        return result;
    }

    /** 單次執行統計 */
    private static final class RunStats {
        int pages;
        long scanned;
        int orphansFound;
        long orphansRemoved;
    }
}
