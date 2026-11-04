package dowob.xyz.blog.infrastructure.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 冪等標記表（{@code processed_events}）清理設定，前綴 {@code app.idempotency}。
 *
 * <p>三個數值皆必須 &gt;= 1，否則在綁定時拋出 {@link IllegalArgumentException}（應用程式啟動失敗），
 * 避免誤設 0 或負數而讓清理永遠不刪或刪掉全部。</p>
 *
 * @param retentionDays    保留天數，早於此天數的列會被清除；預設 30
 * @param batchSize        每批刪除筆數；預設 5000
 * @param maxBatchesPerRun 單次執行最多批數（上限保護，避免異常大量資料讓單次執行無限延長）；預設 200
 * @author Yuan
 * @version 1.0
 */
@ConfigurationProperties(prefix = "app.idempotency")
public record IdempotencyProperties(
        @DefaultValue("30") int retentionDays,
        @DefaultValue("5000") int batchSize,
        @DefaultValue("200") int maxBatchesPerRun) {

    /** 驗證三個數值皆 &gt;= 1。 */
    public IdempotencyProperties {
        requirePositive("app.idempotency.retention-days", retentionDays);
        requirePositive("app.idempotency.batch-size", batchSize);
        requirePositive("app.idempotency.max-batches-per-run", maxBatchesPerRun);
    }

    private static void requirePositive(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " 必須 >= 1，實際為 " + value);
        }
    }
}
