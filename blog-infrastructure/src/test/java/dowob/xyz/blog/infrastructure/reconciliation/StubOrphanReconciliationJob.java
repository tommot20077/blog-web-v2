package dowob.xyz.blog.infrastructure.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 測試用的 {@link OrphanReconciliationJob} 子類別。
 *
 * <p>以記憶體中的有序集合模擬「本模組表中的對外 UUID 欄位」（keyset 分頁），
 * 並以 {@link #existingInOwner} 模擬 owner facade 的存在性查詢。
 * 依規定，具體子類別只存在於 src/test。</p>
 *
 * <p>排序比較器刻意採 PostgreSQL 的 uuid 語意（16 個位元組依序以無號數比較），
 * 而不是 {@link UUID#compareTo(UUID)}（高低 64 位元各自以<b>有號</b>數比較）；
 * 兩者在最高位元為 1 的 UUID 上順序不同。正式子類別須以 SQL
 * {@code ORDER BY <uuid 欄位>} 搭配 {@code > ?} 取頁，順序由資料庫決定。</p>
 *
 * @author Yuan
 * @version 1.0
 */
class StubOrphanReconciliationJob extends OrphanReconciliationJob {

    /** PostgreSQL uuid 的排序：位元組無號、由高位到低位 */
    static final Comparator<UUID> PG_UUID_ORDER = Comparator
            .comparingLong((UUID u) -> u.getMostSignificantBits() ^ Long.MIN_VALUE)
            .thenComparingLong(u -> u.getLeastSignificantBits() ^ Long.MIN_VALUE);

    /** 本模組表中被參照的 UUID（以 PG 順序排序，供 keyset 分頁） */
    final TreeSet<UUID> referenced = new TreeSet<>(PG_UUID_ORDER);

    /** owner 端仍存在的 UUID */
    final Set<UUID> existingInOwner = new HashSet<>();

    /** removeOrphans 收到的所有 UUID（依呼叫順序累積） */
    final List<UUID> removed = new ArrayList<>();

    /** removeOrphans 每次收到的筆數 */
    final List<Integer> removeChunkSizes = new ArrayList<>();

    /** fetchReferencedUuids 每次收到的 afterExclusive */
    final List<UUID> fetchCursors = new ArrayList<>();

    /** filterExisting 被呼叫的次數 */
    int filterCalls;

    /** removeOrphans 被呼叫的次數 */
    int removeCalls;

    /** 若非 null，filterExisting 時拋出此例外（模擬 owner facade 故障） */
    RuntimeException filterFailure;

    /** 若非 null，filterExisting 第 N 次呼叫（1 起算）才拋出 filterFailure */
    Integer failOnFilterCall;

    /** 若非 null，取代預設的存在性判斷；參數為 (呼叫序號, 輸入) */
    java.util.function.BiFunction<Integer, Collection<UUID>, Set<UUID>> filterOverride;

    /** 若非 null，removeOrphans 第 N 次呼叫（1 起算）拋出此例外 */
    RuntimeException removeFailure;

    /** removeFailure 生效的呼叫序號（1 起算） */
    int failOnRemoveCall = 1;

    /** 覆寫 maxOrphansPerRun（null 表示使用預設） */
    Integer maxOrphansOverride;

    /** 覆寫 maxOrphanRatio（null 表示使用預設） */
    Double maxRatioOverride;

    /** 每次 fetch 前執行的掛鉤（參數為 fetch 呼叫序號，1 起算） */
    Consumer<Integer> beforeFetchHook;

    /** 每次 removeOrphans 成功後執行的掛鉤（參數為 remove 呼叫序號，1 起算） */
    Consumer<Integer> afterRemoveHook;

    private int fetchCalls;

    StubOrphanReconciliationJob(StringRedisTemplate redis, MeterRegistry registry) {
        super(redis, registry);
    }

    StubOrphanReconciliationJob(StringRedisTemplate redis, MeterRegistry registry,
                                int pageSize, int maxPagesPerRun) {
        super(redis, registry, pageSize, maxPagesPerRun);
    }

    private StubOrphanReconciliationJob(StringRedisTemplate redis, MeterRegistry registry,
                                        int pageSize, int maxPagesPerRun, AtomicLong clock) {
        super(redis, registry, pageSize, maxPagesPerRun, clock::get);
    }

    /**
     * 建立使用可控時鐘的實例。
     *
     * @param redis          Redis 模板
     * @param registry       指標註冊表
     * @param pageSize       頁大小
     * @param maxPagesPerRun 頁數上限
     * @param clock          可控時鐘（奈秒）
     * @return 實例
     */
    static StubOrphanReconciliationJob withClock(StringRedisTemplate redis, MeterRegistry registry,
                                                 int pageSize, int maxPagesPerRun, AtomicLong clock) {
        return new StubOrphanReconciliationJob(redis, registry, pageSize, maxPagesPerRun, clock);
    }

    @Override
    protected String module() {
        return "stub";
    }

    @Override
    protected String table() {
        return "stub_table";
    }

    @Override
    protected int maxOrphansPerRun() {
        return maxOrphansOverride != null ? maxOrphansOverride : super.maxOrphansPerRun();
    }

    @Override
    protected double maxOrphanRatio() {
        return maxRatioOverride != null ? maxRatioOverride : super.maxOrphanRatio();
    }

    @Override
    protected List<UUID> fetchReferencedUuids(UUID afterExclusive, int limit) {
        fetchCalls++;
        fetchCursors.add(afterExclusive);
        if (beforeFetchHook != null) {
            beforeFetchHook.accept(fetchCalls);
        }
        return referenced.stream()
                .filter(u -> afterExclusive == null || PG_UUID_ORDER.compare(u, afterExclusive) > 0)
                .limit(limit)
                .toList();
    }

    @Override
    protected Set<UUID> filterExisting(Collection<UUID> uuids) {
        filterCalls++;
        if (filterFailure != null && (failOnFilterCall == null || failOnFilterCall == filterCalls)) {
            throw filterFailure;
        }
        if (filterOverride != null) {
            return filterOverride.apply(filterCalls, uuids);
        }
        Set<UUID> result = new HashSet<>(uuids);
        result.retainAll(existingInOwner);
        return result;
    }

    @Override
    protected int removeOrphans(Collection<UUID> missing) {
        removeCalls++;
        removeChunkSizes.add(missing.size());
        if (removeFailure != null && removeCalls == failOnRemoveCall) {
            throw removeFailure;
        }
        removed.addAll(missing);
        if (afterRemoveHook != null) {
            afterRemoveHook.accept(removeCalls);
        }
        return missing.size();
    }
}
