package dowob.xyz.blog.module.search.reindex;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.search.model.dto.response.SearchReindexJobResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SearchReindexService} 單元測試：背景執行、同時只能一個、進度與結果寫回作業狀態（D1／PERF-01）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("SearchReindexService 單元測試")
class SearchReindexServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private ReindexJobStore jobStore;
    private ArticleIndexRebuilder rebuilder;
    private ArticleFacade articleFacade;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private CapturingExecutor executor;
    private SearchReindexService service;

    /** 只收下工作、不執行的 executor：證明觸發端不會在呼叫執行緒上跑重建 */
    private static final class CapturingExecutor extends AbstractExecutorService {
        private final List<Runnable> submitted = new ArrayList<>();
        private boolean shutdown;
        private boolean rejectNext;

        @Override
        public void execute(Runnable command) {
            if (rejectNext) {
                throw new RejectedExecutionException("busy");
            }
            submitted.add(command);
        }

        void runAll() {
            new ArrayList<>(submitted).forEach(Runnable::run);
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jobStore = mock(ReindexJobStore.class);
        rebuilder = mock(ArticleIndexRebuilder.class);
        articleFacade = mock(ArticleFacade.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(jobStore.tryLock(any())).thenReturn(true);
        when(jobStore.renewLock(any())).thenReturn(true);
        executor = new CapturingExecutor();
        service = new SearchReindexService(jobStore, rebuilder, articleFacade, redis, executor,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private UUID startedJobId() {
        ReindexStartResult result = service.start();
        assertThat(result.started()).isTrue();
        return result.job().getJobId();
    }

    @Test
    @DisplayName("觸發：取得鎖、寫入 RUNNING 作業後立即返回；重建交給背景執行緒，呼叫端不會等它（立即回 202 的前提）")
    void start_returnsImmediatelyWithoutRunningRebuild() {
        ReindexStartResult result = service.start();

        assertThat(result.started()).isTrue();
        SearchReindexJobResponse job = result.job();
        assertThat(job.getJobId()).isNotNull();
        assertThat(job.getState()).isEqualTo(ReindexJobState.RUNNING);
        assertThat(job.getProcessed()).isZero();
        assertThat(job.getTotal()).isNull();
        assertThat(job.getStartedAt()).isEqualTo(NOW_LOCAL);
        verify(jobStore).tryLock(job.getJobId());
        verify(jobStore).start(job);
        assertThat(executor.submitted).hasSize(1);
        verify(rebuilder, never()).rebuild(any(), any());
        verify(articleFacade, never()).countPublished();
    }

    @Test
    @DisplayName("已有作業持有鎖：不建立新作業、不送出工作，回傳持鎖的作業（呼叫端回 409）")
    void start_whenLocked_returnsRunningJob() {
        SearchReindexJobResponse running = SearchReindexJobResponse.builder()
                .jobId(UUID.randomUUID()).state(ReindexJobState.RUNNING).build();
        when(jobStore.tryLock(any())).thenReturn(false);
        when(jobStore.lockHolderJob()).thenReturn(Optional.of(running));

        ReindexStartResult result = service.start();

        assertThat(result.started()).isFalse();
        assertThat(result.job()).isEqualTo(running);
        verify(jobStore, never()).start(any());
        assertThat(executor.submitted).isEmpty();
    }

    @Test
    @DisplayName("同時觸發兩次：第一次取得鎖，第二次被擋；第一個作業結束（解鎖）後才能再觸發")
    void start_secondTriggerBlockedUntilFirstFinishes() {
        AtomicReference<UUID> lock = new AtomicReference<>();
        when(jobStore.tryLock(any())).thenAnswer(inv -> lock.compareAndSet(null, inv.getArgument(0)));
        org.mockito.Mockito.doAnswer(inv -> lock.compareAndSet(inv.getArgument(0), null)).when(jobStore).unlock(any());
        when(jobStore.lockHolderJob()).thenAnswer(inv -> Optional.of(SearchReindexJobResponse.builder()
                .jobId(lock.get()).state(ReindexJobState.RUNNING).build()));

        ReindexStartResult first = service.start();
        ReindexStartResult second = service.start();

        assertThat(first.started()).isTrue();
        assertThat(second.started()).isFalse();
        assertThat(second.job().getJobId()).isEqualTo(first.job().getJobId());

        executor.runAll();
        assertThat(service.start().started()).isTrue();
    }

    @Test
    @DisplayName("m5：409 的 data 以鎖的值（jobId）取得持鎖作業，不讀「最近一次作業」（新作業剛取鎖、狀態尚未寫入時會讀到舊作業）")
    void start_whenLocked_usesLockHolderNotLatestJob() {
        when(jobStore.tryLock(any())).thenReturn(false);
        UUID holder = UUID.randomUUID();
        when(jobStore.lockHolderJob()).thenReturn(Optional.of(SearchReindexJobResponse.builder()
                .jobId(holder).state(ReindexJobState.RUNNING).build()));
        when(jobStore.current()).thenReturn(Optional.of(SearchReindexJobResponse.builder()
                .jobId(UUID.randomUUID()).state(ReindexJobState.COMPLETED).build()));

        ReindexStartResult result = service.start();

        assertThat(result.started()).isFalse();
        assertThat(result.job().getJobId()).isEqualTo(holder);
        verify(jobStore, never()).current();
    }

    @Test
    @DisplayName("m3：重建遇到 Error：作業 FAILED、錯誤摘要為該 Error、釋放鎖，Error 原樣往外拋（不吞掉）")
    void run_error_marksFailedAndRethrows() {
        when(rebuilder.rebuild(any(), any())).thenThrow(new OutOfMemoryError("Java heap space"));
        UUID jobId = startedJobId();

        assertThatThrownBy(() -> executor.runAll()).isInstanceOf(OutOfMemoryError.class);

        verify(jobStore).fail(jobId, "OutOfMemoryError: Java heap space", NOW_LOCAL);
        verify(jobStore).unlock(jobId);
    }

    @Test
    @DisplayName("m1：重建因中斷失敗（中斷旗標仍在）時，寫入 FAILED 與解鎖前先清掉旗標，之後再設回")
    void run_interrupted_clearsFlagForBookkeepingAndRestores() {
        java.util.concurrent.atomic.AtomicBoolean interruptedWhileFailing = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicBoolean interruptedWhileUnlocking = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(rebuilder.rebuild(any(), any())).thenAnswer(inv -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重建被中止");
        });
        org.mockito.Mockito.doAnswer(inv -> {
            interruptedWhileFailing.set(Thread.currentThread().isInterrupted());
            return null;
        }).when(jobStore).fail(any(), anyString(), any());
        org.mockito.Mockito.doAnswer(inv -> {
            interruptedWhileUnlocking.set(Thread.currentThread().isInterrupted());
            return null;
        }).when(jobStore).unlock(any());
        startedJobId();

        try {
            executor.runAll();
            assertThat(Thread.currentThread().isInterrupted()).as("收尾後中斷旗標設回").isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(interruptedWhileFailing.get()).isFalse();
        assertThat(interruptedWhileUnlocking.get()).isFalse();
    }

    @Test
    @DisplayName("背景執行緒滿載而拒絕：釋放鎖、作業標記 FAILED，例外往外拋（不留下永遠 RUNNING 的作業）")
    void start_executorRejects_releasesLockAndFails() {
        executor.rejectNext = true;

        assertThatThrownBy(() -> service.start()).isInstanceOf(RejectedExecutionException.class);
        verify(jobStore).unlock(any());
        verify(jobStore).fail(any(), anyString(), eq(NOW_LOCAL));
    }

    @Test
    @DisplayName("背景成功：寫入總數、每批進度並續期鎖，完成後標記 COMPLETED、寫入最後重建時間、釋放鎖")
    void run_success_recordsProgressAndCompletion() {
        when(articleFacade.countPublished()).thenReturn(450L);
        when(rebuilder.rebuild(any(), any())).thenAnswer(inv -> {
            ReindexProgress progress = inv.getArgument(1);
            progress.onProgress(200);
            progress.onProgress(450);
            return 450L;
        });
        UUID jobId = startedJobId();

        executor.runAll();

        verify(jobStore).updateTotal(jobId, 450L);
        verify(jobStore).updateProgress(jobId, 200L);
        verify(jobStore).updateProgress(jobId, 450L);
        verify(jobStore, org.mockito.Mockito.times(2)).renewLock(jobId);
        verify(jobStore).complete(jobId, 450L, NOW_LOCAL);
        verify(valueOps).set(RedisKeyConstant.SEARCH_REINDEX_AT_KEY, NOW_LOCAL.toString());
        verify(jobStore).unlock(jobId);
        verify(jobStore, never()).fail(any(), anyString(), any());
    }

    @Test
    @DisplayName("鎖已遺失（續期失敗）：進度回報拋例外中止重建，作業 FAILED 並說明原因")
    void run_lockLost_abortsRebuild() {
        when(jobStore.renewLock(any())).thenReturn(false);
        when(rebuilder.rebuild(any(), any())).thenAnswer(inv -> {
            ((ReindexProgress) inv.getArgument(1)).onProgress(200);
            return 200L;
        });
        UUID jobId = startedJobId();

        executor.runAll();

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(jobStore).fail(eq(jobId), error.capture(), eq(NOW_LOCAL));
        assertThat(error.getValue()).contains("鎖");
        verify(jobStore, never()).complete(any(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(jobStore).unlock(jobId);
    }

    @Test
    @DisplayName("重建失敗：作業 FAILED、錯誤摘要含例外類型與訊息、釋放鎖；不寫最後重建時間")
    void run_rebuildFails_marksFailed() {
        when(rebuilder.rebuild(any(), any())).thenThrow(new IllegalStateException("bulk failed"));
        UUID jobId = startedJobId();

        executor.runAll();

        verify(jobStore).fail(jobId, "IllegalStateException: bulk failed", NOW_LOCAL);
        verify(jobStore).unlock(jobId);
        verify(valueOps, never()).set(eq(RedisKeyConstant.SEARCH_REINDEX_AT_KEY), anyString());
    }

    @Test
    @DisplayName("錯誤摘要有長度上限（避免把整串堆疊塞進狀態）")
    void run_errorSummaryIsTruncated() {
        when(rebuilder.rebuild(any(), any())).thenThrow(new IllegalStateException("x".repeat(5000)));
        UUID jobId = startedJobId();

        executor.runAll();

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(jobStore).fail(eq(jobId), error.capture(), any());
        assertThat(error.getValue()).hasSizeLessThanOrEqualTo(SearchReindexService.MAX_ERROR_LENGTH);
    }

    @Test
    @DisplayName("最後重建時間寫入失敗：best-effort，作業仍為 COMPLETED")
    void run_lastReindexAtFailure_stillCompleted() {
        when(rebuilder.rebuild(any(), any())).thenReturn(3L);
        doThrow(new IllegalStateException("redis down")).when(valueOps)
                .set(eq(RedisKeyConstant.SEARCH_REINDEX_AT_KEY), anyString());
        UUID jobId = startedJobId();

        executor.runAll();

        verify(jobStore).complete(jobId, 3L, NOW_LOCAL);
        verify(jobStore, never()).fail(any(), anyString(), any());
    }

    @Test
    @DisplayName("預設的 executor 是獨立的單一執行緒（search-reindex），不是 HTTP 執行緒，也不是排程池")
    void defaultExecutor_runsOnDedicatedThread() throws Exception {
        SearchReindexService real = new SearchReindexService(jobStore, rebuilder, articleFacade, redis);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        when(rebuilder.rebuild(any(), any())).thenAnswer(inv -> {
            threadName.set(Thread.currentThread().getName());
            done.countDown();
            return 0L;
        });
        try {
            real.start();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            real.destroy();
        }

        assertThat(threadName.get()).isEqualTo(SearchReindexService.THREAD_NAME)
                .isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    @DisplayName("currentJob 委派作業狀態")
    void currentJob_delegatesToStore() {
        SearchReindexJobResponse job = SearchReindexJobResponse.builder().jobId(UUID.randomUUID()).build();
        when(jobStore.current()).thenReturn(Optional.of(job));

        assertThat(service.currentJob()).contains(job);
    }

    @Test
    @DisplayName("應用程式關閉：中斷並關閉背景執行緒")
    void destroy_shutsDownExecutor() {
        service.destroy();

        assertThat(executor.isShutdown()).isTrue();
    }
}
