package dowob.xyz.blog.infrastructure.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProcessedEventsCleanupJob} 迴圈終止條件的單元測試（PERF-19 複審）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ProcessedEventsCleanupJob 終止條件（單元）")
class ProcessedEventsCleanupJobTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 4, 37, 0);

    @SuppressWarnings("unchecked")
    private static TransactionTemplate passThroughTx() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        return tx;
    }

    @Test
    @DisplayName("某批不足 batchSize（多實例 SKIP LOCKED 下可能發生）不應提前結束：只在刪 0 筆時才停")
    void shortBatch_doesNotStopLoop_untilZeroDeleted() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object.class), anyInt())).thenReturn(3, 2, 0);
        ProcessedEventsCleanupJob job = new ProcessedEventsCleanupJob(
                jdbc, passThroughTx(), new IdempotencyProperties(30, 5, 200));

        ProcessedEventsCleanupJob.Result result = job.run(NOW);

        verify(jdbc, times(3)).update(anyString(), any(Object.class), anyInt());
        assertThat(result.deleted()).isEqualTo(5);
        assertThat(result.batches()).isEqualTo(2);
    }

    @Test
    @DisplayName("達 maxBatchesPerRun 上限即停，即使每批都滿")
    void stopsAtMaxBatchesPerRun() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object.class), anyInt())).thenReturn(5);
        ProcessedEventsCleanupJob job = new ProcessedEventsCleanupJob(
                jdbc, passThroughTx(), new IdempotencyProperties(30, 5, 3));

        ProcessedEventsCleanupJob.Result result = job.run(NOW);

        verify(jdbc, times(3)).update(anyString(), any(Object.class), anyInt());
        assertThat(result.batches()).isEqualTo(3);
    }
}
