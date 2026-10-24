package dowob.xyz.blog.infrastructure.reconciliation;

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

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    @DisplayName("取鎖失敗與執行失敗各自也只輸出一行摘要")
    void run_whenSkippedOrFailed_stillLogsOneSummaryLine() {
        when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        StubOrphanReconciliationJob skipped = new StubOrphanReconciliationJob(redis, registry);
        skipped.run();
        List<String> skippedLines = new ArrayList<>(appender.events.stream()
                .map(e -> e.getMessage().getFormattedMessage()).toList());
        assertThat(skippedLines).hasSize(1);
        assertThat(skippedLines.get(0)).contains("result=skipped");
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
