package dowob.xyz.blog.module.reading.service;

import io.micrometer.core.instrument.Metrics;

/**
 * 閱讀進度「寫入被拒」的指標。
 *
 * <p>單調寫入（Redis Lua {@code WRITE_IF_NEWER}、DB {@code WHERE updated_at <= EXCLUDED.updated_at}）
 * 拒絕較舊的寫入時不會報錯，若完全沒有聲音，時鐘偏移造成的靜默丟棄就無從察覺。
 * 以 {@link Metrics#globalRegistry} 計數（比照 {@code OrphanReconciliationJob} 的單參數建構子做法，
 * 不要求注入 {@code MeterRegistry}：沒有 actuator 的模組不會有該 bean；正式環境 Spring Boot 會把
 * 自己的 registry 加進全域 registry）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class ReadingProgressMetrics {

    /** counter 名稱 */
    public static final String WRITE_REJECTED = "reading.progress.write_rejected";

    /** Redis 單調寫入（{@code update}）被拒 */
    public static final String PATH_CACHE_UPDATE = "cache_update";

    /** {@code get()} 快取回填被拒 */
    public static final String PATH_CACHE_BACKFILL = "cache_backfill";

    /** 完讀路徑 DB upsert 被單調守衛擋下 */
    public static final String PATH_DB_COMPLETION = "db_completion";

    /** flush 的 DB upsert 被單調守衛擋下 */
    public static final String PATH_DB_FLUSH = "db_flush";

    private ReadingProgressMetrics() {
    }

    /**
     * 記一次寫入被拒。
     *
     * @param path 被拒的路徑，取 {@code PATH_*} 常數
     */
    public static void writeRejected(String path) {
        Metrics.counter(WRITE_REJECTED, "path", path).increment();
    }
}
