package dowob.xyz.blog.infrastructure.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link IdempotencyProperties} 設定綁定測試（PERF-19）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("IdempotencyProperties 設定綁定")
class IdempotencyPropertiesTest {

    private static IdempotencyProperties bind(Map<String, String> props) {
        return new Binder(new MapConfigurationPropertySource(props))
                .bindOrCreate("app.idempotency", IdempotencyProperties.class);
    }

    @Test
    @DisplayName("未設定 → 保留 30 天、每批 5000、單次最多 200 批")
    void defaults() {
        IdempotencyProperties p = bind(Map.of());

        assertThat(p.retentionDays()).isEqualTo(30);
        assertThat(p.batchSize()).isEqualTo(5000);
        assertThat(p.maxBatchesPerRun()).isEqualTo(200);
    }

    @Test
    @DisplayName("retention-days 可覆寫")
    void retentionDaysOverridable() {
        assertThat(bind(Map.of("app.idempotency.retention-days", "7")).retentionDays()).isEqualTo(7);
    }

    @ParameterizedTest(name = "retention-days={0}")
    @ValueSource(strings = {"0", "-1"})
    @DisplayName("retention-days < 1 → 綁定失敗")
    void retentionDaysBelowOneRejected(String value) {
        assertThatThrownBy(() -> bind(Map.of("app.idempotency.retention-days", value)))
                .hasStackTraceContaining("retention-days");
    }

    @ParameterizedTest(name = "batch-size={0}")
    @ValueSource(strings = {"0", "-5"})
    @DisplayName("batch-size < 1 → 綁定失敗")
    void batchSizeBelowOneRejected(String value) {
        assertThatThrownBy(() -> bind(Map.of("app.idempotency.batch-size", value)))
                .hasStackTraceContaining("batch-size");
    }

    @Test
    @DisplayName("max-batches-per-run < 1 → 綁定失敗")
    void maxBatchesBelowOneRejected() {
        assertThatThrownBy(() -> bind(Map.of("app.idempotency.max-batches-per-run", "0")))
                .hasStackTraceContaining("max-batches-per-run");
    }
}
