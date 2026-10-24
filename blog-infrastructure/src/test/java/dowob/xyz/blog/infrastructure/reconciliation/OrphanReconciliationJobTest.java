package dowob.xyz.blog.infrastructure.reconciliation;

import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob.Result;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OrphanReconciliationJob} 單元測試（Redis 以 Mockito 替身；真實 Lua 解鎖語意見
 * {@code OrphanReconciliationJobRedisIntegrationTest}）。
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrphanReconciliationJob 單元測試")
class OrphanReconciliationJobTest {

    private static final String LOCK_KEY = "lock:reconciliation:stub:stub_table";
    private static final String CURSOR_KEY = "reconciliation:cursor:stub:stub_table";

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private MeterRegistry registry;
    private CapturingAppender appender;
    private org.apache.logging.log4j.Level originalLevel;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        when(redis.opsForValue()).thenReturn(valueOps);
        // 預設取鎖成功、無游標
        when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(valueOps.get(CURSOR_KEY)).thenReturn(null);

        appender = new CapturingAppender();
        appender.start();
        Logger logger = (Logger) LogManager.getLogger(OrphanReconciliationJob.class);
        originalLevel = logger.getLevel();
        // 測試環境預設可能不輸出 INFO，明確開啟後才能驗證摘要 log
        Configurator.setLevel(OrphanReconciliationJob.class.getName(), org.apache.logging.log4j.Level.INFO);
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LogManager.getLogger(OrphanReconciliationJob.class)).removeAppender(appender);
        Configurator.setLevel(OrphanReconciliationJob.class.getName(), originalLevel);
        appender.stop();
    }

    /**
     * 產生遞增排序的 UUID（以數值遞增保證 compareTo 順序與產生順序一致）。
     *
     * @param n 序號（1 起算）
     * @return 對應 UUID
     */
    private static UUID uuid(int n) {
        return new UUID(0L, n);
    }

    private double counter(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? -1 : c.count();
    }

    @Test
    @DisplayName("取鎖失敗時不處理任何資料並記錄 skipped")
    void run_whenLockNotAcquired_doesNothingAndCountsSkipped() {
        when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);
        job.referenced.add(uuid(1));

        Result result = job.run();

        assertThat(result).isEqualTo(Result.SKIPPED);
        assertThat(job.fetchCursors).isEmpty();
        assertThat(job.filterCalls).isZero();
        assertThat(job.removeCalls).isZero();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());
        assertThat(counter("reconciliation.runs", "module", "stub", "table", "stub_table", "result", "skipped"))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("取鎖時鎖 key 含 module 與 table 且帶 TTL")
    void run_acquiresLockWithModuleTableKeyAndTtl() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);

        job.run();

        verify(valueOps).setIfAbsent(eq(LOCK_KEY), anyString(),
                eq(OrphanReconciliationJob.LOCK_TTL_SECONDS), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("解鎖以取鎖時的 token 做 compare-and-delete")
    @SuppressWarnings("unchecked")
    void run_unlocksWithSameTokenUsedToAcquire() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);
        ArgumentCaptor<String> acquiredToken = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> unlockToken = ArgumentCaptor.forClass(Object.class);

        job.run();

        verify(valueOps).setIfAbsent(eq(LOCK_KEY), acquiredToken.capture(), anyLong(), any(TimeUnit.class));
        verify(redis).execute(any(RedisScript.class), eq(List.of(LOCK_KEY)), unlockToken.capture());
        assertThat(acquiredToken.getValue()).isNotBlank();
        assertThat(unlockToken.getValue()).isEqualTo(acquiredToken.getValue());
        // 不得再以無條件 delete 解鎖
        verify(redis, never()).delete(LOCK_KEY);
    }

    @Test
    @DisplayName("處理中拋出例外時仍以 token 解鎖")
    @SuppressWarnings("unchecked")
    void run_whenProcessingFails_stillUnlocks() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);
        job.referenced.add(uuid(1));
        job.filterFailure = new IllegalStateException("owner down");

        job.run();

        verify(redis).execute(any(RedisScript.class), eq(List.of(LOCK_KEY)), any());
    }

    @Test
    @DisplayName("多頁走訪：每頁以前一頁最後一筆作為 keyset 游標")
    void run_walksAllPagesWithKeysetCursor() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry, 2, 100);
        for (int i = 1; i <= 5; i++) {
            job.referenced.add(uuid(i));
            job.existingInOwner.add(uuid(i));
        }

        Result result = job.run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(job.fetchCursors).containsExactly(null, uuid(2), uuid(4));
        assertThat(job.filterCalls).isEqualTo(3);
        assertThat(job.removeCalls).isZero();
        assertThat(counter("reconciliation.runs", "module", "stub", "table", "stub_table", "result", "completed"))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("只移除 owner 端不存在者，存在者不受影響")
    void run_removesOnlyMissingUuids() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry, 2, 100);
        for (int i = 1; i <= 5; i++) {
            job.referenced.add(uuid(i));
        }
        job.existingInOwner.addAll(List.of(uuid(1), uuid(3), uuid(5)));

        Result result = job.run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(job.removed).containsExactlyInAnyOrder(uuid(2), uuid(4));
        assertThat(counter("reconciliation.orphans_removed", "module", "stub", "table", "stub_table"))
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("全數存在時不呼叫 removeOrphans，但計數器仍為 0")
    void run_whenNoOrphans_doesNotCallRemoveAndCounterIsZero() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);
        job.referenced.add(uuid(1));
        job.existingInOwner.add(uuid(1));

        job.run();

        assertThat(job.removeCalls).isZero();
        assertThat(counter("reconciliation.orphans_removed", "module", "stub", "table", "stub_table"))
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("filterExisting 拋例外時整次中止：零修改、result=failed、不會把例外丟給排程執行緒")
    void run_whenFilterExistingThrows_abortsWithZeroModification() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry, 2, 100);
        for (int i = 1; i <= 6; i++) {
            job.referenced.add(uuid(i)); // 全部都是孤兒（owner 端沒有）
        }
        job.filterFailure = new IllegalStateException("owner facade timeout");
        job.failOnFilterCall = 2; // 第一頁成功、第二頁失敗：驗證先前頁面的孤兒也不被修改

        Result result = job.run();

        assertThat(result).isEqualTo(Result.FAILED);
        assertThat(job.removeCalls).isZero();
        assertThat(job.removed).isEmpty();
        assertThat(counter("reconciliation.runs", "module", "stub", "table", "stub_table", "result", "failed"))
                .isEqualTo(1.0);
        assertThat(counter("reconciliation.orphans_removed", "module", "stub", "table", "stub_table"))
                .isEqualTo(0.0);
        // 失敗不得推進游標
        verify(valueOps, never()).set(eq(CURSOR_KEY), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("超過單次頁數上限時結束本次（partial）並保存游標，下次從游標續跑")
    void run_whenPageCapReached_savesCursorAndNextRunResumes() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry, 2, 2);
        for (int i = 1; i <= 6; i++) {
            job.referenced.add(uuid(i));
        }
        job.existingInOwner.addAll(List.of(uuid(1), uuid(2), uuid(3), uuid(4), uuid(5)));
        // uuid(6) 為孤兒，位於第 3 頁

        Result first = job.run();

        assertThat(first).isEqualTo(Result.PARTIAL);
        assertThat(job.fetchCursors).containsExactly(null, uuid(2));
        assertThat(job.removed).isEmpty();
        verify(valueOps).set(eq(CURSOR_KEY), eq(uuid(4).toString()), any(Duration.class));
        assertThat(counter("reconciliation.runs", "module", "stub", "table", "stub_table", "result", "partial"))
                .isEqualTo(1.0);

        // 第二次執行：游標讀回 uuid(4)，續跑第 3 頁
        when(valueOps.get(CURSOR_KEY)).thenReturn(uuid(4).toString());
        job.fetchCursors.clear();

        Result second = job.run();

        assertThat(second).isEqualTo(Result.COMPLETED);
        assertThat(job.fetchCursors).containsExactly(uuid(4), uuid(6)); // 第 3 頁滿頁，需再取一頁確認結尾
        assertThat(job.removed).containsExactly(uuid(6));
        verify(redis).delete(CURSOR_KEY); // 掃完整張表後清除游標
    }

    @Test
    @DisplayName("單次頁數上限預設值為常數")
    void maxPagesPerRun_hasDefaultConstant() {
        assertThat(OrphanReconciliationJob.MAX_PAGES_PER_RUN).isPositive();
        assertThat(OrphanReconciliationJob.PAGE_SIZE).isPositive();
    }

    @Test
    @DisplayName("每次執行輸出恰好一行摘要 log，含 module、table、result、移除數")
    void run_logsExactlyOneSummaryLine() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);
        job.referenced.addAll(List.of(uuid(1), uuid(2)));
        job.existingInOwner.add(uuid(1));

        job.run();

        List<String> infoLines = appender.events.stream()
                .filter(e -> e.getLevel().isMoreSpecificThan(org.apache.logging.log4j.Level.INFO))
                .map(e -> e.getMessage().getFormattedMessage())
                .toList();
        assertThat(infoLines).hasSize(1);
        assertThat(infoLines.get(0))
                .contains("module=stub")
                .contains("table=stub_table")
                .contains("result=completed")
                .contains("orphansRemoved=1");
    }

    // ===================================================================================
    // 摘要 log：每種結果各自恰好一行
    // ===================================================================================

    /** @return 目前捕捉到、層級為 INFO 以上的訊息 */
    private List<String> summaryLines() {
        return appender.events.stream()
                .filter(e -> e.getLevel().isMoreSpecificThan(org.apache.logging.log4j.Level.INFO))
                .map(e -> e.getMessage().getFormattedMessage())
                .toList();
    }

    @Test
    @DisplayName("取鎖失敗（skipped）只輸出一行摘要")
    void run_whenSkipped_logsExactlyOneSummaryLine() {
        when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);

        job.run();

        assertThat(summaryLines()).hasSize(1);
        assertThat(summaryLines().get(0)).contains("result=skipped");
    }

    @Test
    @DisplayName("執行失敗（failed）輸出摘要：除例外細節外不再有其他 INFO 以上的行")
    void run_whenFailed_logsSummaryLineWithResultFailed() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);
        job.referenced.add(uuid(1));
        job.filterFailure = new IllegalStateException("owner down");

        job.run();

        List<String> lines = summaryLines();
        assertThat(lines).anyMatch(l -> l.contains("result=failed"));
        assertThat(lines).filteredOn(l -> l.contains("孤兒校正摘要")).hasSize(1);
    }

    @Test
    @DisplayName("中止（aborted）輸出一行 error 摘要，含 found、scanned 與門檻")
    void run_whenAborted_logsOneErrorLineWithFoundScannedAndThresholds() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);
        populate(job, 100, 100);
        job.filterOverride = (call, in) -> Set.of();

        job.run();

        List<LogEvent> errors = appender.events.stream()
                .filter(e -> e.getLevel().isMoreSpecificThan(org.apache.logging.log4j.Level.ERROR)).toList();
        assertThat(errors).hasSize(1);
        assertThat(summaryLines()).hasSize(1);
        String line = errors.get(0).getMessage().getFormattedMessage();
        assertThat(line).contains("result=aborted").contains("orphansFound=100").contains("scanned=100")
                .contains("maxOrphans=1000").contains("maxRatio=0.2");
    }

    // ===================================================================================
    // 中止門檻（CRITICAL：filterExisting 異常時整張表不可被當孤兒）
    // ===================================================================================

    /**
     * 建立 1..total 的被參照 UUID，其中前 orphanCount 個在 owner 端不存在。
     *
     * @param job         測試 job
     * @param total       被參照總數
     * @param orphanCount 孤兒數
     */
    private static void populate(StubOrphanReconciliationJob job, int total, int orphanCount) {
        for (int i = 1; i <= total; i++) {
            job.referenced.add(uuid(i));
            if (i > orphanCount) {
                job.existingInOwner.add(uuid(i));
            }
        }
    }

    private void assertAbortedWithoutAnyModification(StubOrphanReconciliationJob job, Result result) {
        assertThat(result).isEqualTo(Result.ABORTED);
        assertThat(job.removeCalls).isZero();
        assertThat(job.removed).isEmpty();
        verify(valueOps, never()).set(eq(CURSOR_KEY), anyString(), any(Duration.class));
        verify(redis, never()).delete(CURSOR_KEY);
        assertThat(counter("reconciliation.runs", "module", "stub", "table", "stub_table", "result", "aborted"))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("filterExisting 回傳空集合（疑似 owner 異常）時中止：不移除、不推進游標")
    @SuppressWarnings("unchecked")
    void run_whenFilterExistingReturnsEmptySet_abortsWithoutRemoving() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);
        populate(job, 1000, 0); // 實際上全部都存在
        job.filterOverride = (call, in) -> Set.of(); // owner 異常：回傳空集合

        Result result = job.run();

        assertAbortedWithoutAnyModification(job, result);
        verify(redis).execute(any(RedisScript.class), eq(List.of(LOCK_KEY)), any()); // 仍然解鎖
    }

    @Test
    @DisplayName("filterExisting 回傳 null 違反契約：拋 IllegalStateException，整次 failed、零修改")
    void run_whenFilterExistingReturnsNull_failsWithoutRemoving() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);
        populate(job, 100, 0);
        job.filterOverride = (call, in) -> null;

        Result result = job.run();

        assertThat(result).isEqualTo(Result.FAILED);
        assertThat(job.removeCalls).isZero();
        verify(valueOps, never()).set(eq(CURSOR_KEY), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("filterExisting 回傳輸入以外的元素違反契約：整次 failed、零修改")
    void run_whenFilterExistingReturnsForeignElement_failsWithoutRemoving() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);
        populate(job, 10, 3);
        job.filterOverride = (call, in) -> {
            Set<UUID> result = new java.util.HashSet<>(in);
            result.add(uuid(999_999)); // 輸入以外
            return result;
        };

        Result result = job.run();

        assertThat(result).isEqualTo(Result.FAILED);
        assertThat(job.removeCalls).isZero();
    }

    @Test
    @DisplayName("孤兒數剛好等於絕對上限（1000）時照常處理；超過 1 筆（1001）即中止")
    void run_absoluteThreshold_boundary() {
        StubOrphanReconciliationJob atLimit = new StubOrphanReconciliationJob(redis, registry);
        populate(atLimit, 10_000, OrphanReconciliationJob.MAX_ORPHANS_PER_RUN); // 10%，不觸發比例門檻
        assertThat(atLimit.run()).isEqualTo(Result.COMPLETED);
        assertThat(atLimit.removed).hasSize(1000);

        StubOrphanReconciliationJob overLimit = new StubOrphanReconciliationJob(redis, registry);
        populate(overLimit, 10_000, OrphanReconciliationJob.MAX_ORPHANS_PER_RUN + 1);
        Result result = overLimit.run();
        assertThat(overLimit.removeCalls).isZero();
        assertThat(result).isEqualTo(Result.ABORTED);
    }

    @Test
    @DisplayName("孤兒占比剛好 20% 時照常處理；超過（21%）即中止")
    void run_ratioThreshold_boundary() {
        StubOrphanReconciliationJob atLimit = new StubOrphanReconciliationJob(redis, registry);
        populate(atLimit, 100, 20);
        assertThat(atLimit.run()).isEqualTo(Result.COMPLETED);
        assertThat(atLimit.removed).hasSize(20);

        StubOrphanReconciliationJob overLimit = new StubOrphanReconciliationJob(redis, registry);
        populate(overLimit, 100, 21);
        Result result = overLimit.run();
        assertThat(overLimit.removeCalls).isZero();
        assertThat(result).isEqualTo(Result.ABORTED);
    }

    @Test
    @DisplayName("掃描數少於 50 時比例門檻不啟用（49 筆中 48 筆孤兒仍處理）；剛好 50 筆時啟用")
    void run_ratioThreshold_disabledBelowMinimumScanned() {
        StubOrphanReconciliationJob below = new StubOrphanReconciliationJob(redis, registry);
        populate(below, 49, 48); // 98% 孤兒但仍有一筆存在（全部孤兒的情況另有專屬門檻）
        assertThat(below.run()).isEqualTo(Result.COMPLETED);
        assertThat(below.removed).hasSize(48);

        StubOrphanReconciliationJob atMinimum = new StubOrphanReconciliationJob(redis, registry);
        populate(atMinimum, 50, 11); // 22%
        Result result = atMinimum.run();
        assertThat(atMinimum.removeCalls).isZero();
        assertThat(result).isEqualTo(Result.ABORTED);
    }

    @Test
    @DisplayName("子類別可覆寫絕對上限")
    void run_subclassCanOverrideAbsoluteThreshold() {
        StubOrphanReconciliationJob ok = new StubOrphanReconciliationJob(redis, registry);
        ok.maxOrphansOverride = 3;
        populate(ok, 10_000, 3);
        assertThat(ok.run()).isEqualTo(Result.COMPLETED);

        StubOrphanReconciliationJob over = new StubOrphanReconciliationJob(redis, registry);
        over.maxOrphansOverride = 3;
        populate(over, 10_000, 4);
        assertThat(over.run()).isEqualTo(Result.ABORTED);
        assertThat(over.removeCalls).isZero();
    }

    @Test
    @DisplayName("子類別可覆寫比例上限")
    void run_subclassCanOverrideRatioThreshold() {
        StubOrphanReconciliationJob ok = new StubOrphanReconciliationJob(redis, registry);
        ok.maxRatioOverride = 0.5;
        populate(ok, 100, 40); // 40% 超過預設 20% 但在 50% 內
        assertThat(ok.run()).isEqualTo(Result.COMPLETED);

        StubOrphanReconciliationJob over = new StubOrphanReconciliationJob(redis, registry);
        over.maxRatioOverride = 0.5;
        populate(over, 100, 51);
        assertThat(over.run()).isEqualTo(Result.ABORTED);
        assertThat(over.removeCalls).isZero();
    }

    // ===================================================================================
    // 修改前複查（TOCTOU）
    // ===================================================================================

    @Test
    @DisplayName("修改前複查：第一次判定不存在、複查已存在者不移除")
    void run_recheckBeforeModification_skipsUuidsThatAppearedMeanwhile() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);
        populate(job, 5, 2); // u1、u2 為孤兒
        job.filterOverride = (call, in) -> {
            Set<UUID> result = new java.util.HashSet<>(in);
            if (call == 1) {
                result.removeAll(Set.of(uuid(1), uuid(2)));
            } else {
                result.remove(uuid(1)); // 複查時 u2 已出現在 owner
            }
            return result;
        };

        Result result = job.run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(job.removed).containsExactly(uuid(1));
        assertThat(job.filterCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("修改前複查拋例外：整次 failed、零修改")
    void run_whenRecheckThrows_failsWithoutRemoving() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);
        populate(job, 5, 2);
        job.filterFailure = new IllegalStateException("owner down on recheck");
        job.failOnFilterCall = 2;

        Result result = job.run();

        assertThat(result).isEqualTo(Result.FAILED);
        assertThat(job.removeCalls).isZero();
        verify(valueOps, never()).set(eq(CURSOR_KEY), anyString(), any(Duration.class));
    }

    // ===================================================================================
    // removeOrphans 切批
    // ===================================================================================

    @Test
    @DisplayName("孤兒數大於頁大小時，removeOrphans 每次收到的筆數不超過頁大小")
    void run_whenOrphansExceedPageSize_removeOrphansChunksAreBoundedByPageSize() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry, 3, 100);
        populate(job, 9, 8); // 8 個孤兒 + 1 個存在者（避免觸發「全部都是孤兒」中止）

        Result result = job.run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(job.removeChunkSizes).containsExactly(3, 3, 2);
        assertThat(job.removed).hasSize(8);
    }

    // ===================================================================================
    // 時間預算
    // ===================================================================================

    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(OrphanReconciliationJob.LOCK_TTL_SECONDS);

    @Test
    @DisplayName("掃描耗時超過 TTL 的 40% 時收尾為 partial，並保存游標")
    void run_whenScanExceedsFortyPercentOfTtl_endsPartialAndSavesCursor() {
        AtomicLong clock = new AtomicLong();
        StubOrphanReconciliationJob job = StubOrphanReconciliationJob.withClock(redis, registry, 2, 100, clock);
        populate(job, 10, 0);
        job.beforeFetchHook = call -> {
            if (call == 2) {
                clock.set((long) (TTL_NANOS * 0.41));
            }
        };

        Result result = job.run();

        assertThat(result).isEqualTo(Result.PARTIAL);
        assertThat(job.fetchCursors).hasSize(2);
        verify(valueOps).set(eq(CURSOR_KEY), eq(uuid(4).toString()), any(Duration.class));
    }

    @Test
    @DisplayName("進入修改階段前已超過 TTL 的 80%：不修改、truncated、游標不推進")
    void run_whenOverEightyPercentBeforeModification_skipsModificationAndReturnsTruncated() {
        AtomicLong clock = new AtomicLong();
        StubOrphanReconciliationJob job = StubOrphanReconciliationJob.withClock(redis, registry, 2, 100, clock);
        populate(job, 4, 1); // 第一頁 [孤兒, 存在]，避免觸發「全部都是孤兒」中止
        job.beforeFetchHook = call -> clock.set((long) (TTL_NANOS * 0.85));

        Result result = job.run();

        assertThat(result).isEqualTo(Result.TRUNCATED);
        assertThat(job.removeCalls).isZero();
        verify(valueOps, never()).set(eq(CURSOR_KEY), anyString(), any(Duration.class));
        verify(redis, never()).delete(CURSOR_KEY);
        assertThat(counter("reconciliation.runs", "module", "stub", "table", "stub_table", "result", "truncated"))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("修改階段每個 chunk 前檢查 TTL 的 80%：超過即不處理剩餘 chunk、游標不推進")
    void run_whenOverEightyPercentBetweenChunks_stopsRemainingChunks() {
        AtomicLong clock = new AtomicLong();
        StubOrphanReconciliationJob job = StubOrphanReconciliationJob.withClock(redis, registry, 2, 100, clock);
        populate(job, 6, 5); // 5 個孤兒 → 3 個 chunk（2、2、1）
        job.afterRemoveHook = call -> clock.set((long) (TTL_NANOS * 0.81));

        Result result = job.run();

        assertThat(result).isEqualTo(Result.TRUNCATED);
        assertThat(job.removeChunkSizes).containsExactly(2);
        assertThat(counter("reconciliation.orphans_removed", "module", "stub", "table", "stub_table"))
                .isEqualTo(2.0);
        verify(valueOps, never()).set(eq(CURSOR_KEY), anyString(), any(Duration.class));
        verify(redis, never()).delete(CURSOR_KEY);
    }

    @Test
    @DisplayName("複查前已超過 TTL 的 80%：不複查、不修改、truncated")
    void run_whenOverEightyPercentBeforeRecheck_skipsRecheckAndModification() {
        AtomicLong clock = new AtomicLong();
        StubOrphanReconciliationJob job = StubOrphanReconciliationJob.withClock(redis, registry, 2, 100, clock);
        populate(job, 4, 1);
        job.filterOverride = (call, in) -> {
            clock.set((long) (TTL_NANOS * 0.85)); // 第一頁的 owner 查詢很慢
            Set<UUID> result = new java.util.HashSet<>(in);
            result.retainAll(job.existingInOwner);
            return result;
        };

        Result result = job.run();

        assertThat(result).isEqualTo(Result.TRUNCATED);
        assertThat(job.filterCalls).isEqualTo(1); // 沒有複查
        assertThat(job.removeCalls).isZero();
        verify(valueOps, never()).set(eq(CURSOR_KEY), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("truncated 不會把連續失敗次數歸零（持續變慢時指標看得出來）")
    void run_whenTruncated_doesNotResetConsecutiveFailures() {
        AtomicLong clock = new AtomicLong();
        StubOrphanReconciliationJob job = StubOrphanReconciliationJob.withClock(redis, registry, 2, 100, clock);
        populate(job, 4, 1);
        job.filterFailure = new IllegalStateException("owner down");
        job.run();
        var gauge = registry.find("reconciliation.consecutive_failures").tags("module", "stub", "table", "stub_table")
                .gauge();
        assertThat(gauge.value()).isEqualTo(1.0);

        job.filterFailure = null;
        job.beforeFetchHook = call -> clock.set((long) (TTL_NANOS * 0.85));
        assertThat(job.run()).isEqualTo(Result.TRUNCATED);

        assertThat(gauge.value()).isEqualTo(1.0);
    }

    // ===================================================================================
    // 小表全部都是孤兒（scanned < 50 時比例門檻不啟用的缺口）
    // ===================================================================================

    @Test
    @DisplayName("本次被參照的 UUID 全部是孤兒且 scanned >= 2：中止（2、8、49 筆）")
    void run_whenAllReferencedAreOrphansAndScannedAtLeastTwo_aborts() {
        for (int total : new int[]{2, 8, 49}) {
            StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);
            populate(job, total, total);

            Result result = job.run();

            assertThat(result).as("scanned=%d", total).isEqualTo(Result.ABORTED);
            assertThat(job.removeCalls).as("scanned=%d", total).isZero();
        }
    }

    @Test
    @DisplayName("殘餘風險：只有 1 筆且是孤兒時無法分辨，照常處理")
    void run_whenSingleReferencedIsOrphan_isProcessedAsKnownResidualRisk() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);
        populate(job, 1, 1);

        Result result = job.run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(job.removed).containsExactly(uuid(1));
    }

    @Test
    @DisplayName("小表只有部分是孤兒（2 筆中 1 筆）照常處理")
    void run_whenSmallTableHasSomeExistingOwners_processesOrphans() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);
        populate(job, 2, 1);

        assertThat(job.run()).isEqualTo(Result.COMPLETED);
        assertThat(job.removed).containsExactly(uuid(1));
    }

    // ===================================================================================
    // 修改階段失敗、連續失敗、取鎖例外、游標損毀、建構子參數
    // ===================================================================================

    @Test
    @DisplayName("removeOrphans 中途失敗：已刪的不回滾、result=failed、游標不推進、仍解鎖")
    @SuppressWarnings("unchecked")
    void run_whenRemoveOrphansFailsMidway_keepsEarlierRemovalsAndDoesNotAdvanceCursor() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry, 2, 100);
        populate(job, 6, 5); // 5 個孤兒（chunk 2、2、1）+ 1 個存在者
        job.removeFailure = new IllegalStateException("db down");
        job.failOnRemoveCall = 2;

        Result result = job.run();

        assertThat(result).isEqualTo(Result.FAILED);
        assertThat(job.removed).hasSize(2); // 第一個 chunk 已提交
        assertThat(counter("reconciliation.orphans_removed", "module", "stub", "table", "stub_table"))
                .isEqualTo(2.0);
        verify(valueOps, never()).set(eq(CURSOR_KEY), anyString(), any(Duration.class));
        verify(redis, never()).delete(CURSOR_KEY);
        verify(redis).execute(any(RedisScript.class), eq(List.of(LOCK_KEY)), any());
    }

    @Test
    @DisplayName("連續失敗次數以 gauge 與摘要 log 暴露，成功後歸零")
    void run_exposesConsecutiveFailuresAndResetsOnSuccess() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);
        populate(job, 3, 0);
        job.filterFailure = new IllegalStateException("owner down");

        job.run();
        job.run();

        var gauge = registry.find("reconciliation.consecutive_failures").tags("module", "stub", "table", "stub_table")
                .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(2.0);
        assertThat(summaryLines()).anyMatch(l -> l.contains("consecutiveFailures=2"));

        job.filterFailure = null;
        assertThat(job.run()).isEqualTo(Result.COMPLETED);
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("取鎖時 Redis 拋例外：result=failed、不處理、不丟出例外、只輸出一行摘要")
    @SuppressWarnings("unchecked")
    void run_whenLockAcquisitionThrows_returnsFailedWithoutProcessing() {
        when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new IllegalStateException("redis down"));
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);
        populate(job, 3, 3);

        Result result = job.run();

        assertThat(result).isEqualTo(Result.FAILED);
        assertThat(job.fetchCursors).isEmpty();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());
        assertThat(summaryLines().stream().filter(l -> l.contains("孤兒校正摘要"))).hasSize(1);
    }

    @Test
    @DisplayName("游標損毀（無法解析為 UUID）：刪除游標、從頭掃描、記錄 warn")
    void run_whenCursorCorrupted_deletesCursorAndScansFromStart() {
        when(valueOps.get(CURSOR_KEY)).thenReturn("not-a-uuid");
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry);
        populate(job, 3, 0);

        Result result = job.run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(job.fetchCursors).containsExactly((UUID) null);
        verify(redis, org.mockito.Mockito.atLeastOnce()).delete(CURSOR_KEY);
        assertThat(appender.events).anyMatch(e ->
                e.getLevel() == org.apache.logging.log4j.Level.WARN
                        && e.getMessage().getFormattedMessage().contains("游標"));
    }

    @Test
    @DisplayName("建構子的頁大小與頁數上限必須為正數")
    void constructor_rejectsNonPositiveArguments() {
        assertThatThrownBy(() -> new StubOrphanReconciliationJob(redis, registry, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StubOrphanReconciliationJob(redis, registry, -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StubOrphanReconciliationJob(redis, registry, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StubOrphanReconciliationJob(redis, registry, 1, -5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("預設頁大小與 BatchedQuery.BATCH_SIZE 同源")
    void pageSize_isSameAsBatchedQueryBatchSize() {
        assertThat(OrphanReconciliationJob.PAGE_SIZE).isEqualTo(BatchedQuery.BATCH_SIZE);
    }

    @Test
    @DisplayName("游標順序採 PostgreSQL uuid 語意：最高位元為 1 的 UUID 也能完整走訪")
    void run_walksUuidsWithHighBitSetUsingDatabaseOrdering() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, registry, 2, 100);
        List<UUID> all = List.of(new UUID(0x7000000000000000L, 1), new UUID(0x8000000000000000L, 2),
                new UUID(0xF000000000000000L, 3), new UUID(0x0000000000000001L, 4), new UUID(0xFFFFFFFFFFFFFFFFL, 5));
        job.referenced.addAll(all);
        job.existingInOwner.addAll(all);

        Result result = job.run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(job.filterCalls).isEqualTo(3); // 5 筆、頁大小 2 → 2 + 2 + 1
        assertThat(job.fetchCursors.get(1)).isEqualTo(new UUID(0x7000000000000000L, 1));
    }

    /** 捕捉日誌事件的測試用 appender（本專案日誌實作為 log4j2）。 */
    private static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("reconciliation-test-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
