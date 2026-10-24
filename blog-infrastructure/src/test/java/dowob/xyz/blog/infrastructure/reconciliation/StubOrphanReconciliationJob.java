package dowob.xyz.blog.infrastructure.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 測試用的 {@link OrphanReconciliationJob} 子類別。
 *
 * <p>以記憶體中的有序集合模擬「本模組表中的對外 UUID 欄位」（keyset 分頁），
 * 並以 {@link #existingInOwner} 模擬 owner facade 的存在性查詢。
 * 依規定，具體子類別只存在於 src/test。</p>
 *
 * @author Yuan
 * @version 1.0
 */
class StubOrphanReconciliationJob extends OrphanReconciliationJob {

    /** 本模組表中被參照的 UUID（有序，供 keyset 分頁） */
    final TreeSet<UUID> referenced = new TreeSet<>();

    /** owner 端仍存在的 UUID */
    final Set<UUID> existingInOwner = new HashSet<>();

    /** removeOrphans 收到的所有 UUID（依呼叫順序累積） */
    final List<UUID> removed = new ArrayList<>();

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

    /** 每次 fetch 前執行的掛鉤（供整合測試在執行中途動手腳） */
    Consumer<Integer> beforeFetchHook;

    private int fetchCalls;

    StubOrphanReconciliationJob(StringRedisTemplate redis, MeterRegistry registry) {
        super(redis, registry);
    }

    StubOrphanReconciliationJob(StringRedisTemplate redis, MeterRegistry registry,
                                int pageSize, int maxPagesPerRun) {
        super(redis, registry, pageSize, maxPagesPerRun);
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
    protected List<UUID> fetchReferencedUuids(UUID afterExclusive, int limit) {
        fetchCalls++;
        fetchCursors.add(afterExclusive);
        if (beforeFetchHook != null) {
            beforeFetchHook.accept(fetchCalls);
        }
        return referenced.stream()
                .filter(u -> afterExclusive == null || u.compareTo(afterExclusive) > 0)
                .limit(limit)
                .toList();
    }

    @Override
    protected Set<UUID> filterExisting(Collection<UUID> uuids) {
        filterCalls++;
        if (filterFailure != null && (failOnFilterCall == null || failOnFilterCall == filterCalls)) {
            throw filterFailure;
        }
        Set<UUID> result = new HashSet<>(uuids);
        result.retainAll(existingInOwner);
        return result;
    }

    @Override
    protected int removeOrphans(Collection<UUID> missing) {
        removeCalls++;
        removed.addAll(missing);
        return missing.size();
    }
}
