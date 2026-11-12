package dowob.xyz.blog.infrastructure.persistence;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BatchedQuery 的打點測試。
 *
 * <p>
 * 切批解決的是「不會炸」，不是「知道發生什麼」——沒有打點時，輸入是 300 筆還是
 * 3,000 筆完全無法分辨，重評門檻只能靠人記得去看 JavaDoc（BUG-2026-003 的根因之一）。
 * 打在 {@link BatchedQuery} 一處即涵蓋全部呼叫端，是把切批抽成共用原語的附帶紅利。
 * </p>
 *
 * <p>本測試自行註冊 {@link SimpleMeterRegistry} 到全域 registry 並於結束時移除，
 * 避免跨測試汙染。正式環境的綁定來自 Spring Boot 的
 * {@code management.metrics.use-global-registry}（3.5.9 預設為 true）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("BatchedQuery 打點測試")
class BatchedQueryMetricsTest {

    /** 測試期間掛上的 registry */
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(registry);
        registry.close();
    }

    /**
     * 產生 1..count 的連續 id 清單。
     *
     * @param count 筆數
     * @return id 清單
     */
    private List<Integer> ids(int count) {
        return IntStream.rangeClosed(1, count).boxed().toList();
    }

    @Test
    @DisplayName("記錄的是輸入總大小，不是批次大小——那才是重評門檻要看的數字")
    void recordsTotalInputSizeNotBatchSize() {
        int total = BatchedQuery.BATCH_SIZE + 100;

        BatchedQuery.queryInBatches(ids(total), batch -> List.of());

        DistributionSummary summary = registry.find(BatchedQuery.INPUT_SIZE_METRIC).summary();
        assertThat(summary).isNotNull();
        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.totalAmount()).isEqualTo(total);
        assertThat(summary.max()).isEqualTo(total);
    }

    @Test
    @DisplayName("以呼叫端類別為 tag，使 p99 偏高時查得出是哪條路徑")
    void tagsMeasurementWithCallerClass() {
        BatchedQuery.queryInBatches(ids(10), batch -> List.of());

        DistributionSummary summary = registry.find(BatchedQuery.INPUT_SIZE_METRIC).summary();
        assertThat(summary).isNotNull();
        assertThat(summary.getId().getTag("caller")).isEqualTo("BatchedQueryMetricsTest");
    }

    @Test
    @DisplayName("空輸入不打點，與其不發查詢的行為一致")
    void emptyInputRecordsNothing() {
        BatchedQuery.queryInBatches(List.of(), batch -> List.of());
        BatchedQuery.queryInBatches(null, batch -> List.of());

        assertThat(registry.find(BatchedQuery.INPUT_SIZE_METRIC).summary()).isNull();
    }

    @Test
    @DisplayName("多次呼叫累積成分佈，而非覆蓋")
    void multipleCallsAccumulateIntoDistribution() {
        BatchedQuery.queryInBatches(ids(10), batch -> List.of());
        BatchedQuery.queryInBatches(ids(30), batch -> List.of());

        DistributionSummary summary = registry.find(BatchedQuery.INPUT_SIZE_METRIC).summary();
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.totalAmount()).isEqualTo(40);
        assertThat(summary.max()).isEqualTo(30);
    }
}
