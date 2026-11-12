package dowob.xyz.blog.infrastructure.reconciliation;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

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
 *   <li>（選用）覆寫 {@link #maxOrphansPerRun()}、{@link #maxOrphanRatio()} 調整中止門檻；
 *       <b>不提供執行期旁路開關</b></li>
 * </ul>
 *
 * <p>排程由子類別以 {@code @Scheduled} 觸發並呼叫 {@link #run()}；本類別不新增任何具體子類別於 src/main
 * （具體 job 由各模組的 PR 提供）。</p>
 *
 * <h3>框架負責</h3>
 * <ul>
 *   <li>Redis 分散式鎖：{@code setIfAbsent + TTL} 取鎖，以 <b>token compare-and-delete</b>（Lua）解鎖，
 *       不會刪到他人（例如 TTL 到期後被別的實例取得）的鎖；鎖 key 含 module 與 table
 *       （見 {@link RedisKeyConstant#getReconciliationLockKey(String, String)}）</li>
 *   <li>逐頁處理，且<b>單次執行頁數有上限</b>（{@link #MAX_PAGES_PER_RUN}）：超過即結束本次（result=partial），
 *       游標存入 Redis，下次從游標續跑；掃完整張表後清除游標。避免長掃描長時間佔住排程執行緒（PERF-16）</li>
 *   <li><b>時間預算</b>（以單調時鐘，相對於鎖 TTL）：掃描超過 TTL 的 40% 即收尾為 partial；
 *       複查與修改階段的每個 chunk 前若已超過 TTL 的 80%，不再複查／修改剩餘部分
 *       （result=truncated，游標不推進；truncated 不會重置連續失敗次數，持續變慢時指標才看得出來），
 *       避免鎖在修改途中到期而與下一個實例重疊</li>
 *   <li>fail-safe：<b>先掃描、後修改</b>。掃描階段（fetch／owner facade）任何例外都使整次中止、
 *       完全不呼叫 {@code removeOrphans}、游標不推進，result=failed——owner 暫時不可用時絕不能把「查不到」
 *       當成「不存在」而誤刪</li>
 *   <li><b>異常中止</b>（result=aborted）：owner 查詢回傳空集合等「看起來成功、其實錯誤」的情況不會拋例外，
 *       只能從結果的規模辨識。以本次掃描到的不重複 UUID 計，孤兒數超過 {@link #maxOrphansPerRun()}
 *       （預設 {@link #MAX_ORPHANS_PER_RUN}），或掃描數達 {@link #MIN_SCANNED_FOR_RATIO} 後孤兒占比超過
 *       {@link #maxOrphanRatio()}（預設 {@link #MAX_ORPHAN_RATIO}），即完全不修改、游標不推進，
 *       並以一行 error log 呈報。真的有大量孤兒時須由人調查後處理，而非讓排程自動大量刪除。
 *       另有一條針對小表的規則：本次被參照的 UUID <b>全部</b>都是孤兒且掃描數 &gt;=
 *       {@link #MIN_SCANNED_FOR_ALL_ORPHANS} 也中止。<b>代價</b>：小表若合法地全部是孤兒（例如最後一個
 *       owner 被刪除），每次執行都會持續 aborted，需要人工處理（代價是告警，而非資料遺失）；
 *       掃描數只有 1 筆時無法分辨，為接受的殘餘風險</li>
 *   <li>契約檢查：{@code filterExisting} 回傳 null 或含輸入以外的元素，視為實作錯誤，整次 failed</li>
 *   <li>修改前複查（TOCTOU）：進入修改階段前，對疑似孤兒再呼叫一次 {@code filterExisting}，
 *       只處理兩次都不存在者</li>
 *   <li>指標：{@code reconciliation.orphans_removed{module,table}}、
 *       {@code reconciliation.runs{module,table,result}}（result：completed／partial／truncated／skipped／failed／aborted）、
 *       gauge {@code reconciliation.consecutive_failures{module,table}}（本實例連續 failed／aborted 的次數，
 *       completed 或 partial 即歸零，skipped 與 truncated 不影響；實例重啟後歸零）</li>
 *   <li>日誌：每次執行輸出恰好一行摘要（含 consecutiveFailures）；failed 時另有一行 warn 帶例外細節，
 *       aborted 時摘要本身為 error 層級並帶出 found、scanned 與門檻</li>
 * </ul>
 *
 * <h3>修改階段中途失敗的語意</h3>
 * <p>{@code removeOrphans} 以 chunk 逐批呼叫，<b>不在同一交易內</b>。若第 N 個 chunk 失敗：前 N-1 個 chunk
 * 已提交、<b>不回滾</b>（並已計入 {@code orphans_removed}）；result=failed；<b>游標不推進</b>，下次從原游標重新掃描
 * （已移除者自然不再出現）。因此若某筆資料每次都讓 {@code removeOrphans} 失敗（毒丸），游標會卡在該處，
 * 其後的資料不會被校正——此時 {@code reconciliation.consecutive_failures} 會持續上升，應據此告警並人工處理。</p>
 *
 * <p>{@link #run()} 永不拋出例外（排程執行緒不應因此中斷）。</p>
 *
 * @author Yuan
 * @version 1.1
 */
@Slf4j
public abstract class OrphanReconciliationJob {

    /** 單頁筆數預設值（與 {@link BatchedQuery#BATCH_SIZE} 同源，owner 端查詢不會超出參數上限） */
    public static final int PAGE_SIZE = BatchedQuery.BATCH_SIZE;

    /** 單次執行處理頁數上限預設值（200 頁 × 500 筆 = 10 萬筆）；超過即結束本次，下次從游標續跑 */
    public static final int MAX_PAGES_PER_RUN = 200;

    /** 單次執行允許處理的孤兒數上限預設值；超過即中止（aborted）。子類別可覆寫 {@link #maxOrphansPerRun()} */
    public static final int MAX_ORPHANS_PER_RUN = 1000;

    /** 孤兒占掃描數的比例上限預設值（0.20 = 20%）；超過即中止。子類別可覆寫 {@link #maxOrphanRatio()} */
    public static final double MAX_ORPHAN_RATIO = 0.20;

    /** 比例門檻啟用所需的最少掃描數；樣本太小時比例沒有意義，只受絕對上限約束 */
    public static final int MIN_SCANNED_FOR_RATIO = 50;

    /**
     * 「本次被參照的 UUID 全部都是孤兒」中止規則啟用所需的最少掃描數。
     *
     * <p>補足 {@link #MIN_SCANNED_FOR_RATIO} 之前比例門檻不啟用、又遠低於絕對上限的小表缺口：
     * owner 查詢異常回空集合時，小表會被整個清掉。掃描數只有 1 筆時無法分辨「真的是孤兒」與「查詢異常」，
     * 為接受的殘餘風險。</p>
     */
    public static final int MIN_SCANNED_FOR_ALL_ORPHANS = 2;

    /** 分散式鎖 TTL（秒）；須大於單次執行（至多 {@link #MAX_PAGES_PER_RUN} 頁）的合理耗時 */
    public static final long LOCK_TTL_SECONDS = 600L;

    /** 掃描階段的時間預算占鎖 TTL 的比例，超過即收尾為 partial */
    static final double SCAN_BUDGET_FRACTION = 0.4;

    /** 修改階段的時間預算占鎖 TTL 的比例，超過即不再修改剩餘部分 */
    static final double MODIFY_BUDGET_FRACTION = 0.8;

    /** 續跑游標的保存期限，避免遺留過期游標 */
    static final Duration CURSOR_TTL = Duration.ofDays(7);

    /** 指標：移除的孤兒數 */
    static final String METRIC_ORPHANS_REMOVED = "reconciliation.orphans_removed";

    /** 指標：執行次數（依結果分類） */
    static final String METRIC_RUNS = "reconciliation.runs";

    /** 指標：連續失敗次數 */
    static final String METRIC_CONSECUTIVE_FAILURES = "reconciliation.consecutive_failures";

    /** token compare-and-delete：僅當 key 的值等於自己的 token 才刪除 */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry registry;
    private final int pageSize;
    private final int maxPagesPerRun;
    private final LongSupplier nanoClock;
    private final long scanBudgetNanos;
    private final long modifyBudgetNanos;

    /** 本實例連續失敗（failed／aborted）次數 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** gauge 是否已註冊（module()／table() 為抽象方法，不在建構子中呼叫） */
    private final AtomicBoolean gaugeRegistered = new AtomicBoolean();

    /**
     * 以預設頁大小、單次頁數上限與<b>全域 registry</b>（{@link Metrics#globalRegistry}）建立。
     *
     * <p>供不想注入 {@link MeterRegistry} 的子類別使用：沒有 actuator 的模組（例如各模組的測試應用程式）
     * 不會有 {@code MeterRegistry} bean，強制注入會讓每個模組都得自己補一個。正式環境 Spring Boot 會把
     * 自動配置的 registry 綁到全域 registry（{@code management.metrics.use-global-registry} 預設 true），
     * 指標照常輸出；全域 registry 尚未掛任何子 registry 時，指標呼叫為 no-op，不影響校正行為。</p>
     *
     * @param redis Redis 操作模板（鎖與游標）
     */
    protected OrphanReconciliationJob(StringRedisTemplate redis) {
        this(redis, Metrics.globalRegistry);
    }

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
     * @throws IllegalArgumentException 任一參數不為正數
     */
    protected OrphanReconciliationJob(StringRedisTemplate redis, MeterRegistry registry,
                                      int pageSize, int maxPagesPerRun) {
        this(redis, registry, pageSize, maxPagesPerRun, System::nanoTime);
    }

    /**
     * 指定頁大小、頁數上限與時鐘建立（供測試注入可控時鐘）。
     *
     * @param redis          Redis 操作模板（鎖與游標）
     * @param registry       指標註冊表
     * @param pageSize       單頁筆數，須為正數
     * @param maxPagesPerRun 單次執行頁數上限，須為正數
     * @param nanoClock      單調時鐘（奈秒），正式環境為 {@link System#nanoTime()}
     * @throws IllegalArgumentException 頁大小或頁數上限不為正數
     */
    protected OrphanReconciliationJob(StringRedisTemplate redis, MeterRegistry registry,
                                      int pageSize, int maxPagesPerRun, LongSupplier nanoClock) {
        if (pageSize <= 0 || maxPagesPerRun <= 0) {
            throw new IllegalArgumentException("pageSize 與 maxPagesPerRun 必須為正數");
        }
        this.redis = redis;
        this.registry = registry;
        this.pageSize = pageSize;
        this.maxPagesPerRun = maxPagesPerRun;
        this.nanoClock = nanoClock;
        long ttlNanos = TimeUnit.SECONDS.toNanos(LOCK_TTL_SECONDS);
        this.scanBudgetNanos = (long) (ttlNanos * SCAN_BUDGET_FRACTION);
        this.modifyBudgetNanos = (long) (ttlNanos * MODIFY_BUDGET_FRACTION);
    }

    /**
     * 一次校正執行的結果。
     */
    public enum Result {
        /** 掃完整張表 */
        COMPLETED,
        /** 達到頁數上限或掃描時間預算，游標已保存，下次續跑 */
        PARTIAL,
        /** 未取得分散式鎖，未處理任何資料 */
        SKIPPED,
        /** 掃描或移除過程發生例外；掃描階段失敗時零修改 */
        FAILED,
        /** 修改階段因時間預算被截斷（未完成、游標未推進）；不重置連續失敗次數 */
        TRUNCATED,
        /** 孤兒數超過安全門檻，疑似 owner 查詢異常，完全未修改 */
        ABORTED;

        /**
         * 指標 tag 與日誌使用的結果名稱。
         *
         * @return 小寫的結果名稱，例如 {@code completed}
         */
        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 單次執行允許處理的孤兒數上限，可由子類別覆寫。
     *
     * <p>以本次掃描到的不重複 UUID 中「owner 端不存在」的個數計；超過即中止（{@link Result#ABORTED}）。
     * 預設 {@link #MAX_ORPHANS_PER_RUN}。</p>
     *
     * @return 孤兒數上限（含）
     */
    protected int maxOrphansPerRun() {
        return MAX_ORPHANS_PER_RUN;
    }

    /**
     * 孤兒占掃描數的比例上限，可由子類別覆寫。
     *
     * <p>僅在本次掃描數達 {@link #MIN_SCANNED_FOR_RATIO} 時啟用；孤兒數／掃描數超過此值即中止
     * （{@link Result#ABORTED}）。預設 {@link #MAX_ORPHAN_RATIO}。</p>
     *
     * @return 比例上限（含，0～1）
     */
    protected double maxOrphanRatio() {
        return MAX_ORPHAN_RATIO;
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
     * <p><b>排序必須由資料庫決定</b>：SQL 寫成 {@code WHERE <uuid 欄位> > ? ORDER BY <uuid 欄位> LIMIT ?}
     * （去重用 {@code SELECT DISTINCT}）。PostgreSQL 的 uuid 比較是 16 個位元組依序以無號數比較，
     * 與 {@link UUID#compareTo(UUID)}（高低 64 位元各自以有號數比較）在最高位元為 1 的 UUID 上順序不同，
     * 因此不可在 Java 端排序或比較後再當游標使用。</p>
     *
     * <p>游標以最後一筆 UUID 的字串形式存在 Redis。<b>子類別若更改排序方式或所參照的欄位，
     * 必須同時清除既有游標</b>（{@link RedisKeyConstant#getReconciliationCursorKey(String, String)}），
     * 否則會從錯誤的位置續跑。</p>
     *
     * @param afterExclusive 游標（不含）；null 表示從頭
     * @param limit          本頁筆數上限
     * @return 升冪的 UUID 清單，無資料時為空清單
     */
    protected abstract List<UUID> fetchReferencedUuids(UUID afterExclusive, int limit);

    /**
     * 查詢哪些 UUID 在 owner 模組仍然存在（委派 owner facade）。
     *
     * <p><b>存在的定義：不論狀態、可見性，只要 owner 的資料列還在就算存在。</b>
     * 草稿、未公開、已封存、軟刪除的列都算「存在」——孤兒指的是 owner 的列已被實體刪除。
     * <b>不可用 {@code filterPublishedUuids} 之類只回傳「已發佈／可見」的方法實作</b>，
     * 否則所有非公開的 owner 都會被誤判為孤兒而遭刪除。</p>
     *
     * <p>契約：回傳值必須是輸入的子集且不為 null。回傳 null 或含輸入以外的元素，框架視為實作錯誤並拋出
     * {@link IllegalStateException}（整次 failed、零修改）。owner 不可用時<b>必須拋出例外</b>，不得回傳空集合：
     * 例外會使整次中止，而空集合會被當成「全部都是孤兒」（框架的異常中止門檻是最後一道防線，不是依賴對象）。</p>
     *
     * @param uuids 待確認的 UUID（不重複）
     * @return 仍存在的 UUID 子集，不得為 null
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
     *
     * <p>實作須冪等（重跑不會出錯）。框架保證只會傳入已確認（兩次查詢都）不存在者，且單次傳入筆數不超過頁大小。</p>
     *
     * <p><b>交易</b>：本方法由框架以 {@code this} 直接呼叫，不經過 Spring 代理，因此子類別在此方法上標
     * {@code @Transactional} <b>不會生效</b>。需要交易時請在方法內使用 {@code TransactionTemplate}。
     * 每次呼叫是獨立的 chunk，chunk 之間不共用交易；中途失敗的語意見類別說明。</p>
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
        String lockKey = RedisKeyConstant.getReconciliationLockKey(module(), table());
        String token = UUID.randomUUID().toString();
        RunStats stats = new RunStats(nanoClock.getAsLong());
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
     * 先掃描收集疑似孤兒，通過中止門檻與複查後才進行移除並推進游標。
     *
     * @param stats 本次統計（會被更新）
     * @return COMPLETED、PARTIAL 或 ABORTED；掃描／複查失敗以例外離開（此時尚未修改任何資料）
     */
    private Result reconcile(RunStats stats) {
        String cursorKey = RedisKeyConstant.getReconciliationCursorKey(module(), table());
        UUID after = readCursor(cursorKey);

        // 掃描階段：只讀，任何例外都會使整次中止且零修改
        Set<UUID> suspected = new LinkedHashSet<>();
        boolean finished = false;
        while (stats.pages < maxPagesPerRun) {
            List<UUID> page = fetchReferencedUuids(after, pageSize);
            stats.pages++;
            if (page.isEmpty()) {
                finished = true;
                break;
            }
            Set<UUID> distinct = new LinkedHashSet<>(page);
            Set<UUID> existing = checkedFilterExisting(distinct);
            for (UUID uuid : distinct) {
                if (!existing.contains(uuid)) {
                    suspected.add(uuid);
                }
            }
            stats.scanned += distinct.size();
            after = page.get(page.size() - 1);
            if (page.size() < pageSize) {
                finished = true;
                break;
            }
            if (elapsedNanos(stats) > scanBudgetNanos) {
                break;
            }
        }
        stats.orphansFound = suspected.size();

        // 異常中止：結果規模不合理時，寧可不處理也不能誤刪
        if (exceedsSafetyThreshold(stats)) {
            return Result.ABORTED;
        }

        // 修改前複查（TOCTOU）：只處理兩次都不存在者
        List<UUID> confirmed = recheck(suspected, stats);

        // 修改階段：掃描已全部成功
        boolean truncated = stats.truncated;
        for (int from = 0; !truncated && from < confirmed.size(); from += pageSize) {
            if (elapsedNanos(stats) > modifyBudgetNanos) {
                truncated = true;
                break;
            }
            List<UUID> chunk = confirmed.subList(from, Math.min(from + pageSize, confirmed.size()));
            stats.orphansRemoved += removeOrphans(chunk);
        }

        if (truncated) {
            return Result.TRUNCATED; // 複查／修改未完成：游標不推進，下次重掃
        }
        if (finished) {
            redis.delete(cursorKey);
            return Result.COMPLETED;
        }
        redis.opsForValue().set(cursorKey, after.toString(), CURSOR_TTL);
        return Result.PARTIAL;
    }

    /**
     * 判斷疑似孤兒的規模是否超過安全門檻，超過時把門檻記入統計供日誌呈報。
     *
     * @param stats 本次統計
     * @return true 表示應中止
     */
    private boolean exceedsSafetyThreshold(RunStats stats) {
        int maxOrphans = maxOrphansPerRun();
        double maxRatio = maxOrphanRatio();
        boolean overAbsolute = stats.orphansFound > maxOrphans;
        boolean overRatio = stats.scanned >= MIN_SCANNED_FOR_RATIO
                && (double) stats.orphansFound / stats.scanned > maxRatio;
        boolean allOrphans = stats.scanned >= MIN_SCANNED_FOR_ALL_ORPHANS && stats.orphansFound == stats.scanned;
        if (overAbsolute || overRatio || allOrphans) {
            stats.maxOrphans = maxOrphans;
            stats.maxRatio = maxRatio;
            return true;
        }
        return false;
    }

    /**
     * 對疑似孤兒再查一次 owner，排除掃描期間才出現的 owner 資料。
     *
     * <p>複查也受修改預算約束：每批複查前若已超過預算，標記 {@code stats.truncated} 並停止。</p>
     *
     * @param suspected 第一次判定不存在者
     * @param stats     本次統計
     * @return 兩次都不存在者（保持原順序）；被截斷時內容不完整，呼叫端不得使用
     */
    private List<UUID> recheck(Set<UUID> suspected, RunStats stats) {
        List<UUID> all = new ArrayList<>(suspected);
        List<UUID> confirmed = new ArrayList<>();
        for (int from = 0; from < all.size(); from += pageSize) {
            if (elapsedNanos(stats) > modifyBudgetNanos) {
                stats.truncated = true;
                return confirmed;
            }
            Set<UUID> chunk = new LinkedHashSet<>(all.subList(from, Math.min(from + pageSize, all.size())));
            Set<UUID> existingNow = checkedFilterExisting(chunk);
            for (UUID uuid : chunk) {
                if (!existingNow.contains(uuid)) {
                    confirmed.add(uuid);
                }
            }
        }
        return confirmed;
    }

    /**
     * 呼叫 {@link #filterExisting(Collection)} 並檢查其契約。
     *
     * @param input 待確認的 UUID
     * @return 通過檢查的存在集合
     * @throws IllegalStateException 回傳 null 或含輸入以外的元素
     */
    private Set<UUID> checkedFilterExisting(Set<UUID> input) {
        Set<UUID> existing = filterExisting(input);
        if (existing == null) {
            throw new IllegalStateException("filterExisting 不得回傳 null（owner 不可用時應拋出例外）");
        }
        if (!input.containsAll(existing)) {
            throw new IllegalStateException("filterExisting 回傳了輸入以外的元素，違反子集契約");
        }
        return existing;
    }

    /**
     * 讀取續跑游標；內容損毀時刪除並從頭掃描。
     *
     * @param cursorKey 游標 key
     * @return 游標，無或損毀時為 null
     */
    private UUID readCursor(String cursorKey) {
        String saved = redis.opsForValue().get(cursorKey);
        if (saved == null || saved.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(saved);
        } catch (IllegalArgumentException e) {
            log.warn("孤兒校正游標損毀，已刪除並從頭掃描 module={} table={} cursor={}", module(), table(), saved);
            redis.delete(cursorKey);
            return null;
        }
    }

    /**
     * 本次執行已經過的時間。
     *
     * @param stats 本次統計
     * @return 經過的奈秒數
     */
    private long elapsedNanos(RunStats stats) {
        return nanoClock.getAsLong() - stats.startNanos;
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
     * 更新連續失敗計數、記錄指標與唯一一行摘要日誌。
     *
     * @param result 結果
     * @param stats  統計
     * @return 原結果
     */
    private Result finish(Result result, RunStats stats) {
        if (gaugeRegistered.compareAndSet(false, true)) {
            registry.gauge(METRIC_CONSECUTIVE_FAILURES, Tags.of("module", module(), "table", table()),
                    consecutiveFailures);
        }
        int failures;
        if (result == Result.FAILED || result == Result.ABORTED) {
            failures = consecutiveFailures.incrementAndGet();
        } else if (result == Result.SKIPPED || result == Result.TRUNCATED) {
            failures = consecutiveFailures.get();
        } else {
            consecutiveFailures.set(0);
            failures = 0;
        }
        registry.counter(METRIC_ORPHANS_REMOVED, "module", module(), "table", table())
                .increment(stats.orphansRemoved);
        registry.counter(METRIC_RUNS, "module", module(), "table", table(), "result", result.tag()).increment();

        if (result == Result.ABORTED) {
            log.error("孤兒校正摘要 module={} table={} result={} pages={} scanned={} orphansFound={} orphansRemoved={} "
                            + "consecutiveFailures={} maxOrphans={} maxRatio={} minScannedForRatio={} minScannedForAllOrphans={} "
                            + "（孤兒規模超過安全門檻，疑似 owner 查詢異常，未做任何修改，請人工調查）",
                    module(), table(), result.tag(), stats.pages, stats.scanned, stats.orphansFound,
                    stats.orphansRemoved, failures, stats.maxOrphans, stats.maxRatio, MIN_SCANNED_FOR_RATIO,
                    MIN_SCANNED_FOR_ALL_ORPHANS);
        } else {
            log.info("孤兒校正摘要 module={} table={} result={} pages={} scanned={} orphansFound={} "
                            + "orphansRemoved={} consecutiveFailures={}",
                    module(), table(), result.tag(), stats.pages, stats.scanned, stats.orphansFound,
                    stats.orphansRemoved, failures);
        }
        return result;
    }

    /** 單次執行統計 */
    private static final class RunStats {
        final long startNanos;
        int pages;
        long scanned;
        int orphansFound;
        long orphansRemoved;
        int maxOrphans;
        double maxRatio;
        boolean truncated;

        RunStats(long startNanos) {
            this.startNanos = startNanos;
        }
    }
}
