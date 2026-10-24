package dowob.xyz.blog.infrastructure.mq;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
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
        assertThat(count(ConsumerExecutor.PROCESSED_METRIC)).isZero();
        assertThat(count(ConsumerExecutor.FAILED_METRIC)).isEqualTo(1.0);
        assertThat(count(ConsumerExecutor.DEAD_LETTERED_METRIC)).isEqualTo(1.0);
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
        try {
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
        } finally {
            Metrics.addRegistry(registry);
        }
    }

    @Test
    @DisplayName("計數失敗不影響失敗路徑：仍 nack 恰好一次且不外拋")
    void metricFailureDoesNotBreakFailurePath() throws Exception {
        BrokenRegistry broken = new BrokenRegistry();
        Metrics.addRegistry(broken);
        broken.arm();
        try {
            assertThatCode(() -> executor.execute(uniqueConsumerName(), channel, TAG, () -> {
                throw new IllegalStateException("boom");
            })).doesNotThrowAnyException();

            verify(channel, times(1)).basicNack(TAG, false, false);
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
        } finally {
            broken.disarm();
            Metrics.removeRegistry(broken);
            broken.close();
        }
    }

    @Test
    @DisplayName("計數失敗不影響成功路徑：只 ack 一次、絕不對已 ack 的 tag 再 nack")
    void metricFailureDoesNotBreakSuccessPath() throws Exception {
        BrokenRegistry broken = new BrokenRegistry();
        Metrics.addRegistry(broken);
        broken.arm();
        try {
            assertThatCode(() -> executor.execute(uniqueConsumerName(), channel, TAG, () -> { }))
                    .doesNotThrowAnyException();

            verify(channel, times(1)).basicAck(TAG, false);
            verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        } finally {
            broken.disarm();
            Metrics.removeRegistry(broken);
            broken.close();
        }
    }

    @Test
    @DisplayName("consumerName 為 null 導致計數失敗時，成功路徑仍只 ack 一次、不 nack")
    void nullConsumerNameDoesNotBreakAck() throws Exception {
        assertThatCode(() -> executor.execute(null, channel, TAG, () -> { }))
                .doesNotThrowAnyException();

        verify(channel, times(1)).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("body 拋 InterruptedException 時照常 nack，並還原執行緒的 interrupt 旗標")
    void interruptedExceptionRestoresInterruptFlag() throws Exception {
        try {
            executor.execute(CONSUMER, channel, TAG, () -> {
                throw new InterruptedException("interrupted");
            });

            verify(channel).basicNack(TAG, false, false);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("body 拋一般例外時不應設定 interrupt 旗標")
    void ordinaryExceptionLeavesInterruptFlagClear() throws Exception {
        executor.execute(CONSUMER, channel, TAG, () -> {
            throw new IllegalStateException("boom");
        });

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("帶 logContext 的多載：失敗時 ERROR 日誌含業務上下文，並照常 nack")
    void failureLogContainsBusinessContext() throws Exception {
        CapturingAppender appender = attachAppender();
        try {
            executor.execute(CONSUMER, () -> "articleUuid=abc-123, eventId=evt-9", channel, TAG, () -> {
                throw new IllegalStateException("boom");
            });
        } finally {
            detachAppender(appender);
        }

        verify(channel).basicNack(TAG, false, false);
        assertThat(appender.events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getMessage().getFormattedMessage())
                    .contains(CONSUMER)
                    .contains("articleUuid=abc-123")
                    .contains("eventId=evt-9");
        });
    }

    @Test
    @DisplayName("logContext 在成功路徑不會被呼叫（延遲求值）")
    void logContextNotEvaluatedOnSuccess() throws Exception {
        executor.execute(CONSUMER, () -> {
            throw new AssertionError("成功路徑不應求值 logContext");
        }, channel, TAG, () -> { });

        verify(channel).basicAck(TAG, false);
    }

    @Test
    @DisplayName("logContext 自身拋例外時被防護：退回不帶上下文，仍 nack 且不外拋")
    void throwingLogContextFallsBackWithoutContext() throws Exception {
        CapturingAppender appender = attachAppender();
        try {
            assertThatCode(() -> executor.execute(CONSUMER, () -> {
                throw new IllegalStateException("context boom");
            }, channel, TAG, () -> {
                throw new IllegalStateException("boom");
            })).doesNotThrowAnyException();
        } finally {
            detachAppender(appender);
        }

        verify(channel, times(1)).basicNack(TAG, false, false);
        assertThat(appender.events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getMessage().getFormattedMessage()).contains(CONSUMER);
        });
    }

    @Test
    @DisplayName("logContext 為 null 或回傳 null／空白時，行為等同 4 參數版")
    void nullOrBlankLogContextBehavesLikeFourArgVersion() throws Exception {
        executor.execute(CONSUMER, (java.util.function.Supplier<String>) null, channel, 1L, () -> {
            throw new IllegalStateException("boom");
        });
        executor.execute(CONSUMER, () -> null, channel, 2L, () -> {
            throw new IllegalStateException("boom");
        });
        executor.execute(CONSUMER, () -> "  ", channel, 3L, () -> {
            throw new IllegalStateException("boom");
        });

        verify(channel).basicNack(1L, false, false);
        verify(channel).basicNack(2L, false, false);
        verify(channel).basicNack(3L, false, false);
    }

    /**
     * 產生測試內唯一的 consumer 名稱。
     *
     * <p>全域 registry 會保留先前測試建立的 meter；只有「新的」meter 才會往子 registry
     * 註冊，所以要觸發 {@link BrokenRegistry} 的失敗，必須使用沒用過的名稱。</p>
     *
     * @return 唯一的 consumer 名稱
     */
    private String uniqueConsumerName() {
        return "consumer-" + java.util.UUID.randomUUID();
    }

    /**
     * 可開關的壞 registry：啟用（arm）後，建立 Counter 一律拋例外，用來模擬計數失敗。
     *
     * <p>必須先掛上全域 registry 再 arm、移除前先 disarm：全域 composite 會把既有 meter
     * 重播給新加入／移除時的子 registry，若一直處於壞狀態會讓其他測試的 setUp 連帶失敗。</p>
     */
    private static final class BrokenRegistry extends SimpleMeterRegistry {

        /** 是否處於壞狀態 */
        private final java.util.concurrent.atomic.AtomicBoolean armed =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        /** 進入壞狀態。 */
        void arm() {
            armed.set(true);
        }

        /** 恢復正常。 */
        void disarm() {
            armed.set(false);
        }

        @Override
        protected Counter newCounter(Meter.Id id) {
            if (armed.get()) {
                throw new IllegalStateException("metric boom");
            }
            return super.newCounter(id);
        }
    }

    /**
     * 掛上捕捉用 appender 以取得 {@link ConsumerExecutor} 的日誌事件。
     *
     * @return 已啟動並掛載的 appender
     */
    private CapturingAppender attachAppender() {
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        ((Logger) LogManager.getLogger(ConsumerExecutor.class)).addAppender(appender);
        return appender;
    }

    /**
     * 卸載測試用 appender，避免污染其他測試。
     *
     * @param appender 先前掛上的 appender
     */
    private void detachAppender(CapturingAppender appender) {
        ((Logger) LogManager.getLogger(ConsumerExecutor.class)).removeAppender(appender);
        appender.stop();
    }

    /**
     * 捕捉日誌事件的測試用 appender（本專案日誌實作為 log4j2）。
     */
    private static final class CapturingAppender extends AbstractAppender {

        /** 捕捉到的日誌事件 */
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("consumer-executor-test-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
