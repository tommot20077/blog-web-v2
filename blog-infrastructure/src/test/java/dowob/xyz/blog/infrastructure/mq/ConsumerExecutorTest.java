package dowob.xyz.blog.infrastructure.mq;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * ConsumerExecutor 的單元測試。
 *
 * <p>驗證其行為與各 consumer 既有的手寫樣板完全一致
 * （成功 ack、失敗 nack 且不重新入列、nack 失敗不外拋），
 * 並驗證 Micrometer 計數。計數測試自行註冊 {@link SimpleMeterRegistry}
 * 到全域 registry 並於結束時移除，避免跨測試汙染。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ConsumerExecutor 測試")
class ConsumerExecutorTest {

    /** 測試用 consumer 名稱 */
    private static final String CONSUMER = "test-consumer";

    /** 測試用 delivery tag */
    private static final long TAG = 42L;

    /** 被測物件 */
    private final ConsumerExecutor executor = new ConsumerExecutor();

    /** 模擬的 AMQP channel */
    private final Channel channel = mock(Channel.class);

    /** 測試期間掛上全域 Metrics 的 registry */
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
     * 讀取指定指標在 {@link #CONSUMER} 上的計數，指標不存在時視為 0。
     *
     * @param metric 指標名稱
     * @return 計數值
     */
    private double count(String metric) {
        var counter = registry.find(metric).tag("consumer", CONSUMER).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("body 成功時 basicAck 且 processed 加一，不 nack、不計失敗")
    void successAcksAndCountsProcessed() throws Exception {
        executor.execute(CONSUMER, channel, TAG, () -> { });

        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        assertThat(count(ConsumerExecutor.PROCESSED_METRIC)).isEqualTo(1.0);
        assertThat(count(ConsumerExecutor.FAILED_METRIC)).isZero();
        assertThat(count(ConsumerExecutor.DEAD_LETTERED_METRIC)).isZero();
    }

    @Test
    @DisplayName("body 拋例外時 basicNack(requeue=false)，failed 與 dead_lettered 各加一，不 ack")
    void failureNacksWithoutRequeueAndCountsFailedAndDeadLettered() throws Exception {
        executor.execute(CONSUMER, channel, TAG, () -> {
            throw new IllegalStateException("boom");
        });

        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        assertThat(count(ConsumerExecutor.FAILED_METRIC)).isEqualTo(1.0);
        assertThat(count(ConsumerExecutor.DEAD_LETTERED_METRIC)).isEqualTo(1.0);
        assertThat(count(ConsumerExecutor.PROCESSED_METRIC)).isZero();
    }

    @Test
    @DisplayName("body 拋受檢例外（Exception）同樣走 nack 路徑")
    void checkedExceptionAlsoNacks() throws Exception {
        executor.execute(CONSUMER, channel, TAG, () -> {
            throw new IOException("checked");
        });

        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("nack 本身拋 IOException 時不往外拋，failed 仍加一，dead_lettered 不加（訊息未確實進 DLQ）")
    void nackIoExceptionIsSwallowed() throws Exception {
        doThrow(new IOException("channel closed")).when(channel).basicNack(TAG, false, false);

        assertThatCode(() -> executor.execute(CONSUMER, channel, TAG, () -> {
            throw new IllegalStateException("boom");
        })).doesNotThrowAnyException();

        verify(channel).basicNack(TAG, false, false);
        assertThat(count(ConsumerExecutor.FAILED_METRIC)).isEqualTo(1.0);
        assertThat(count(ConsumerExecutor.DEAD_LETTERED_METRIC)).isZero();
    }

    @Test
    @DisplayName("ack 本身拋 IOException 時比照既有樣板：視為失敗並 nack(requeue=false)")
    void ackIoExceptionFallsIntoNackPath() throws Exception {
        doThrow(new IOException("ack failed")).when(channel).basicAck(TAG, false);

        assertThatCode(() -> executor.execute(CONSUMER, channel, TAG, () -> { }))
                .doesNotThrowAnyException();

        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    @DisplayName("不同 consumer 名稱的計數互相獨立（以 consumer tag 區分）")
    void countersAreSeparatedByConsumerTag() throws Exception {
        executor.execute("consumer-a", channel, 1L, () -> { });
        executor.execute("consumer-b", channel, 2L, () -> { });
        executor.execute("consumer-b", channel, 3L, () -> { });

        assertThat(registry.find(ConsumerExecutor.PROCESSED_METRIC).tag("consumer", "consumer-a")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find(ConsumerExecutor.PROCESSED_METRIC).tag("consumer", "consumer-b")
                .counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("全域 registry 未掛任何子 registry 時不報錯，ack/nack 行為不受影響")
    void noChildRegistryDoesNotFail() throws Exception {
        Metrics.removeRegistry(registry);
        assertThat(Metrics.globalRegistry.getRegistries()).isEmpty();

        assertThatCode(() -> {
            executor.execute(CONSUMER, channel, 1L, () -> { });
            executor.execute(CONSUMER, channel, 2L, () -> {
                throw new IllegalStateException("boom");
            });
        }).doesNotThrowAnyException();

        verify(channel).basicAck(1L, false);
        verify(channel).basicNack(2L, false, false);
        verifyNoMoreInteractions(channel);
        Metrics.addRegistry(registry);
    }
}
