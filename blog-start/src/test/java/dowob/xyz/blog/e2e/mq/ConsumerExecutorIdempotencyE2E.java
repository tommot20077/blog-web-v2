package dowob.xyz.blog.e2e.mq;

import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 正式 Spring context 中 {@link ConsumerExecutor} 冪等路徑可用性的 E2E 守衛。
 *
 * <p>{@code ConsumerExecutor} 的 {@code TransactionTemplate}／{@code IdempotencyService} 為 {@code @Nullable} 注入，
 * bean 缺失時會靜默注入 null，所有冪等 consumer 都會 nack 進 DLQ，而單元測試仍然全綠。
 * 本測試用完整 context（真實 Postgres）以「行為」證明冪等路徑真的接上了。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ConsumerExecutor 冪等路徑（完整 context）")
class ConsumerExecutorIdempotencyE2E extends AbstractE2ETest {

    /** 測試用 consumer 名稱 */
    private static final String CONSUMER = "e2e.idempotency-guard";

    @Autowired
    private ConsumerExecutor consumerExecutor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM processed_events WHERE consumer_name = ?", CONSUMER);
    }

    @Test
    @DisplayName("帶 eventId 呼叫 executeIdempotent：body 執行、正常返回、processed_events 出現該列；同一 eventId 再呼叫 body 不執行")
    void executeIdempotent_withRealContext_marksAndDeduplicates() throws Exception {
        UUID eventId = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();

        consumerExecutor.executeIdempotent(CONSUMER, null, eventId, runs::incrementAndGet);

        assertThat(runs).as("body 在首次執行").hasValue(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ? AND consumer_name = ?",
                Integer.class, eventId, CONSUMER)).isEqualTo(1);

        consumerExecutor.executeIdempotent(CONSUMER, null, eventId, runs::incrementAndGet);

        assertThat(runs).as("同一 eventId 重送，body 不再執行").hasValue(1);
    }
}
