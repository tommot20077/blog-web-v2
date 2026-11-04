package dowob.xyz.blog.infrastructure.idempotency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * {@code processed_events} 每日清理排程（PERF-19，Yuan 2026-11-04 決定：保留 30 天）。
 *
 * <p>冪等標記表只增不刪會讓 UNIQUE 索引與表持續膨脹。本排程每日刪除 {@code processed_at}
 * <b>嚴格早於</b> {@code now - retentionDays} 的列（恰為保留期邊界的列保留）。事件的重送視窗遠短於 30 天，
 * 超過保留期的標記不會再被用到。</p>
 *
 * <p><b>分批</b>：每批以 {@code idx_processed_events_processed_at} 挑出最舊的 {@code batchSize} 筆
 * （預設 5000）刪除，每批各自一個資料庫交易，避免單一長交易鎖表；迴圈到沒有過期列或達
 * {@code maxBatchesPerRun} 上限為止（剩餘的留給下次執行）。挑列時使用 {@code FOR UPDATE SKIP LOCKED}，
 * 多個實例同時執行時各自拿到不重疊的列，不互相阻塞也不死鎖。</p>
 *
 * <p><b>多副本安全</b>：不需要分散式鎖。DELETE 本身冪等——同一列被刪兩次只是第二次影響 0 列；
 * 多個實例同時跑最壞只是重複掃描，結果仍正確。</p>
 *
 * <p>時間來源為 Java {@code LocalDateTime.now()}，與 {@code IdempotencyService} 寫入
 * {@code processed_at} 的時鐘一致，不使用資料庫 {@code now()}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
public class ProcessedEventsCleanupJob {

    /** 預設 cron：每日 04:37（避開整點，並與既有 03:17~04:11 的每日校正排程及每小時第 7 分的補發排程錯開） */
    public static final String DEFAULT_CRON = "0 37 4 * * *";

    private static final String DELETE_BATCH_SQL =
            "DELETE FROM processed_events WHERE id IN ("
                    + "SELECT id FROM processed_events WHERE processed_at < ? "
                    + "ORDER BY processed_at LIMIT ? FOR UPDATE SKIP LOCKED)";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate tx;
    private final IdempotencyProperties properties;

    /**
     * 執行結果。
     *
     * @param deleted 共刪除的列數
     * @param batches 實際執行的批數（含最後一批未滿者）
     */
    public record Result(int deleted, int batches) {
    }

    /**
     * 建構子。
     *
     * @param jdbcTemplate JDBC 範本
     * @param tx           交易範本（每批各自開啟一個交易）
     * @param properties   清理設定
     */
    public ProcessedEventsCleanupJob(JdbcTemplate jdbcTemplate, TransactionTemplate tx,
                                     IdempotencyProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.tx = tx;
        this.properties = properties;
    }

    /** 排程入口：委派 {@link #run(LocalDateTime)}，例外只記錄、不外拋（下次排程會再清）。 */
    @Scheduled(cron = "${app.idempotency.cleanup-cron:" + DEFAULT_CRON + "}")
    void scheduledRun() {
        try {
            run(LocalDateTime.now());
        } catch (RuntimeException e) {
            log.error("processed_events 清理排程失敗", e);
        }
    }

    /**
     * 執行一次清理。
     *
     * @param now 「現在」；保留期的截止時間為 {@code now - retentionDays}（嚴格小於才刪）
     * @return 刪除筆數與批數
     */
    public Result run(LocalDateTime now) {
        long startNanos = System.nanoTime();
        LocalDateTime cutoff = now.minusDays(properties.retentionDays());
        int total = 0;
        int batches = 0;
        while (batches < properties.maxBatchesPerRun()) {
            Integer deleted = tx.execute(status ->
                    jdbcTemplate.update(DELETE_BATCH_SQL, cutoff, properties.batchSize()));
            int n = deleted == null ? 0 : deleted;
            if (n == 0) {
                break;
            }
            batches++;
            total += n;
            if (n < properties.batchSize()) {
                break;
            }
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("processed_events 清理完成：刪除 {} 筆、{} 批、耗時 {} ms（保留 {} 天，cutoff={}）",
                total, batches, elapsedMs, properties.retentionDays(), cutoff);
        return new Result(total, batches);
    }
}
