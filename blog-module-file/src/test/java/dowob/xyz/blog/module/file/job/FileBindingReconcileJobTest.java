package dowob.xyz.blog.module.file.job;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.file.config.FileProperties;
import dowob.xyz.blog.module.file.service.FileBindingReconcileService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FileBindingReconcileJob} 單元測試（B1＋D6 的排程編排）：鎖、順序（先補綁定再清理）、fail-safe、游標、指標。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("FileBindingReconcileJob 單元測試")
class FileBindingReconcileJobTest {

    private static final String LOCK_KEY = RedisKeyConstant.getReconciliationLockKey("file", "file_metadata.unbound_cleanup");
    private static final String CURSOR_KEY = RedisKeyConstant.getReconciliationCursorKey("file", "file_metadata.unbound_cleanup");

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private FileBindingReconcileService service;
    private SimpleMeterRegistry registry;
    private AtomicLong clock;
    private FileBindingReconcileJob job;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        service = mock(FileBindingReconcileService.class);
        registry = new SimpleMeterRegistry();
        clock = new AtomicLong();
        FileProperties props = new FileProperties(Map.of(), Set.of(), null, null, null, null);
        job = new FileBindingReconcileJob(redis, service, props, registry, clock::get);
        when(service.rebindRecentlyUpdatedArticles(any(), anyInt(), any()))
                .thenReturn(new FileBindingReconcileService.RebindResult(3, 2, 0, false));
        when(service.cleanupUnboundFiles(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new FileBindingReconcileService.CleanupResult(5, 4, 1, null, false));
    }

    @Test
    @DisplayName("排程：每日 04:17，且不與既有排程（03:17～03:59、04:11／23／29／37、每小時 :07）撞時刻")
    void scheduledMethod_usesCron0417() {
        List<Method> scheduled = Arrays.stream(FileBindingReconcileJob.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class)).toList();

        assertThat(scheduled).hasSize(1);
        assertThat(scheduled.get(0).getAnnotation(Scheduled.class).cron()).isEqualTo("0 17 4 * * *");
        assertThat(FileBindingReconcileJob.CRON).isEqualTo("0 17 4 * * *");
        assertThat(FileBindingReconcileJob.CRON).isNotEqualTo(FileArticleOrphanReconciliationJob.CRON);
    }

    @Test
    @DisplayName("鎖 key 與同模組的孤兒校正 job 不同（兩個 job 不互相排擠）")
    void lockKey_differsFromOrphanJobLock() {
        assertThat(LOCK_KEY).isNotEqualTo(RedisKeyConstant.getReconciliationLockKey("file", "file_metadata"));
    }

    @Test
    @DisplayName("先補綁定、再清理（順序）；since／cutoff 依設定的 3 天／7 天計算")
    void run_rebindsBeforeCleanup_usingConfiguredDays() {
        LocalDateTime before = LocalDateTime.now();

        FileBindingReconcileJob.Result result = job.run();

        assertThat(result).isEqualTo(FileBindingReconcileJob.Result.COMPLETED);
        InOrder order = inOrder(service);
        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        order.verify(service).rebindRecentlyUpdatedArticles(since.capture(), anyInt(), any(BooleanSupplier.class));
        order.verify(service).cleanupUnboundFiles(cutoff.capture(), any(), eq(500), anyInt(), any(BooleanSupplier.class));
        assertThat(since.getValue()).isBetween(before.minusDays(3).minusMinutes(1), LocalDateTime.now().minusDays(3).plusMinutes(1));
        assertThat(cutoff.getValue()).isBetween(before.minusDays(7).minusMinutes(1), LocalDateTime.now().minusDays(7).plusMinutes(1));
    }

    @Test
    @DisplayName("fail-safe：補綁定階段（facade 列舉）失敗 → FAILED，完全不執行清理")
    void run_rebindFails_skipsCleanup() {
        when(service.rebindRecentlyUpdatedArticles(any(), anyInt(), any())).thenThrow(new IllegalStateException("article db down"));

        FileBindingReconcileJob.Result result = job.run();

        assertThat(result).isEqualTo(FileBindingReconcileJob.Result.FAILED);
        verify(service, never()).cleanupUnboundFiles(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("清理階段失敗 → FAILED，游標不推進；run 不外拋")
    void run_cleanupFails_resultFailedAndCursorUntouched() {
        when(service.cleanupUnboundFiles(any(), any(), anyInt(), anyInt(), any())).thenThrow(new IllegalStateException("minio down"));

        FileBindingReconcileJob.Result result = job.run();

        assertThat(result).isEqualTo(FileBindingReconcileJob.Result.FAILED);
        verify(valueOps, never()).set(eq(CURSOR_KEY), anyString(), any(Duration.class));
        verify(redis, never()).delete(CURSOR_KEY);
    }

    @Test
    @DisplayName("取不到鎖 → SKIPPED，不做任何事")
    void run_lockNotAcquired_skips() {
        when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        assertThat(job.run()).isEqualTo(FileBindingReconcileJob.Result.SKIPPED);

        verify(service, never()).rebindRecentlyUpdatedArticles(any(), anyInt(), any());
        verify(service, never()).cleanupUnboundFiles(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("結束後（含失敗）以 token compare-and-delete 釋放鎖")
    @SuppressWarnings("unchecked")
    void run_alwaysReleasesLockWithToken() {
        when(service.rebindRecentlyUpdatedArticles(any(), anyInt(), any())).thenThrow(new IllegalStateException("x"));
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> acquiredToken = ArgumentCaptor.forClass(String.class);

        job.run();

        verify(valueOps).setIfAbsent(eq(LOCK_KEY), acquiredToken.capture(), anyLong(), any(TimeUnit.class));
        verify(redis).execute(any(RedisScript.class), eq(List.of(LOCK_KEY)), token.capture());
        assertThat(token.getValue()).isEqualTo(acquiredToken.getValue());
    }

    @Test
    @DisplayName("游標：讀取上次存的游標傳給清理；清理回傳新游標則寫回（結果 PARTIAL）")
    void run_cursorReadAndPersisted() {
        UUID saved = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(valueOps.get(CURSOR_KEY)).thenReturn(saved.toString());
        when(service.cleanupUnboundFiles(any(), eq(saved), anyInt(), anyInt(), any()))
                .thenReturn(new FileBindingReconcileService.CleanupResult(10, 2, 0, next, true));

        FileBindingReconcileJob.Result result = job.run();

        assertThat(result).isEqualTo(FileBindingReconcileJob.Result.PARTIAL);
        verify(valueOps).set(eq(CURSOR_KEY), eq(next.toString()), any(Duration.class));
    }

    @Test
    @DisplayName("整輪掃描完成（游標 null）→ 清除游標，下次從頭")
    void run_scanFinished_clearsCursor() {
        when(valueOps.get(CURSOR_KEY)).thenReturn(UUID.randomUUID().toString());

        FileBindingReconcileJob.Result result = job.run();

        assertThat(result).isEqualTo(FileBindingReconcileJob.Result.COMPLETED);
        verify(redis).delete(CURSOR_KEY);
    }

    @Test
    @DisplayName("游標內容損毀 → 當作從頭開始，不拋例外")
    void run_corruptCursor_startsFromBeginning() {
        when(valueOps.get(CURSOR_KEY)).thenReturn("not-a-uuid");

        assertThat(job.run()).isEqualTo(FileBindingReconcileJob.Result.COMPLETED);
        verify(service).cleanupUnboundFiles(any(), eq((UUID) null), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("指標：記錄補綁定文章數、刪除檔案數與各結果的執行次數")
    void run_recordsMetrics() {
        job.run();

        assertThat(registry.counter("file.reconcile.rebind", "result", "rebound").count()).isEqualTo(2.0);
        assertThat(registry.counter("file.reconcile.unbound_deleted").count()).isEqualTo(4.0);
        assertThat(registry.counter("file.reconcile.runs", "result", "completed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("時間預算：超過鎖 TTL 的一定比例後，傳給服務的 timeUp 回 true")
    void run_timeBudget_timeUpSupplierTurnsTrue() {
        ArgumentCaptor<BooleanSupplier> timeUp = ArgumentCaptor.forClass(BooleanSupplier.class);

        job.run();
        verify(service).rebindRecentlyUpdatedArticles(any(), anyInt(), timeUp.capture());

        assertThat(timeUp.getValue().getAsBoolean()).isFalse();
        clock.set(TimeUnit.SECONDS.toNanos(FileBindingReconcileJob.LOCK_TTL_SECONDS));
        assertThat(timeUp.getValue().getAsBoolean()).isTrue();
    }
}
