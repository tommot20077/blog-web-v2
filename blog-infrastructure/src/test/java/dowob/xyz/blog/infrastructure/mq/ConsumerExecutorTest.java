package dowob.xyz.blog.infrastructure.mq;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ConsumerExecutor 的單元測試。
 *
 * <p>驗證 Z3-b 之後的契約：成功正常返回（ack 由容器負責）、失敗<b>往外拋</b>
 * （RuntimeException 原樣、受檢例外包成 {@link ConsumerExecutionException}）交給容器與 retry interceptor，
 * 並驗證 Micrometer 計數（processed／failed；dead_lettered 不在此計）。
 * 計數測試自行註冊 {@link SimpleMeterRegistry} 到全域 registry 並於結束時移除，避免跨測試汙染。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ConsumerExecutor 測試")
class ConsumerExecutorTest {

    /** 測試用 consumer 名稱 */
    private static final String CONSUMER = "test-consumer";

    /** 被測物件 */
    private final ConsumerExecutor executor = new ConsumerExecutor();

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
    @DisplayName("body 成功時正常返回，processed 加一，不計失敗、不計 dead_lettered")
    void successReturnsNormallyAndCountsProcessed() {
        assertThatCode(() -> executor.execute(CONSUMER, () -> { })).doesNotThrowAnyException();

        assertThat(count(ConsumerExecutor.PROCESSED_METRIC)).isEqualTo(1.0);
        assertThat(count(ConsumerExecutor.FAILED_METRIC)).isZero();
        assertThat(count(ConsumerExecutor.DEAD_LETTERED_METRIC)).isZero();
    }

    @Test
    @DisplayName("body 拋 RuntimeException：原樣（同一個實例）往外拋，failed 加一；dead_lettered 不由本類計（由 recoverer 計）")
    void runtimeExceptionIsRethrownAsIs_andCountsFailedOnly() {
        IllegalStateException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> executor.execute(CONSUMER, () -> {
            throw boom;
        })).isSameAs(boom);

        assertThat(count(ConsumerExecutor.FAILED_METRIC)).isEqualTo(1.0);
        assertThat(count(ConsumerExecutor.DEAD_LETTERED_METRIC)).isZero();
        assertThat(count(ConsumerExecutor.PROCESSED_METRIC)).isZero();
    }

    @Test
    @DisplayName("body 拋受檢例外：包成 ConsumerExecutionException 往外拋，原始例外為 cause")
    void checkedExceptionIsWrappedWithOriginalAsCause() {
        IOException checked = new IOException("checked");

        assertThatThrownBy(() -> executor.execute(CONSUMER, () -> {
            throw checked;
        }))
                .isInstanceOf(ConsumerExecutionException.class)
                .hasCause(checked);

        assertThat(count(ConsumerExecutor.FAILED_METRIC)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("每次嘗試失敗都計一次 failed（重試 3 次即 3）")
    void everyFailedAttemptIsCounted() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> executor.execute(CONSUMER, () -> {
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class);
        }

        assertThat(count(ConsumerExecutor.FAILED_METRIC)).isEqualTo(3.0);
    }

    @Test
    @DisplayName("不同 consumer 名稱的計數互相獨立（以 consumer tag 區分）")
    void countersAreSeparatedByConsumerTag() {
        executor.execute("consumer-a", () -> { });
        executor.execute("consumer-b", () -> { });
        executor.execute("consumer-b", () -> { });

        assertThat(registry.find(ConsumerExecutor.PROCESSED_METRIC).tag("consumer", "consumer-a")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find(ConsumerExecutor.PROCESSED_METRIC).tag("consumer", "consumer-b")
                .counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("全域 registry 未掛任何子 registry 時不報錯：成功正常返回、失敗仍往外拋原始例外")
    void noChildRegistryDoesNotFail() {
        Metrics.removeRegistry(registry);
        try {
            assertThat(Metrics.globalRegistry.getRegistries()).isEmpty();
            IllegalStateException boom = new IllegalStateException("boom");

            assertThatCode(() -> executor.execute(CONSUMER, () -> { })).doesNotThrowAnyException();
            assertThatThrownBy(() -> executor.execute(CONSUMER, () -> {
                throw boom;
            })).isSameAs(boom);
        } finally {
            Metrics.addRegistry(registry);
        }
    }

    @Test
    @DisplayName("計數失敗不遮蔽失敗路徑：往外拋的仍是業務原始例外，不是計數例外")
    void metricFailureDoesNotMaskBusinessException() {
        BrokenRegistry broken = new BrokenRegistry();
        Metrics.addRegistry(broken);
        broken.arm();
        try {
            IllegalStateException boom = new IllegalStateException("boom");

            assertThatThrownBy(() -> executor.execute(uniqueConsumerName(), () -> {
                throw boom;
            })).isSameAs(boom);
        } finally {
            broken.disarm();
            Metrics.removeRegistry(broken);
            broken.close();
        }
    }

    @Test
    @DisplayName("計數失敗不影響成功路徑：不往外拋（否則已完成的業務會被誤判失敗而重試）")
    void metricFailureDoesNotBreakSuccessPath() {
        BrokenRegistry broken = new BrokenRegistry();
        Metrics.addRegistry(broken);
        broken.arm();
        try {
            assertThatCode(() -> executor.execute(uniqueConsumerName(), () -> { })).doesNotThrowAnyException();
        } finally {
            broken.disarm();
            Metrics.removeRegistry(broken);
            broken.close();
        }
    }

    @Test
    @DisplayName("consumerName 為 null 導致計數失敗時，成功路徑仍不往外拋")
    void nullConsumerNameDoesNotBreakSuccess() {
        assertThatCode(() -> executor.execute(null, () -> { })).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("body 拋 InterruptedException：往外拋 ConsumerExecutionException，並還原執行緒的 interrupt 旗標")
    void interruptedExceptionRestoresInterruptFlag() {
        try {
            assertThatThrownBy(() -> executor.execute(CONSUMER, () -> {
                throw new InterruptedException("interrupted");
            })).isInstanceOf(ConsumerExecutionException.class).hasCauseInstanceOf(InterruptedException.class);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("body 拋一般例外時不應設定 interrupt 旗標")
    void ordinaryExceptionLeavesInterruptFlagClear() {
        assertThatThrownBy(() -> executor.execute(CONSUMER, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("帶 logContext 的多載：失敗時 ERROR 日誌含業務上下文，並往外拋")
    void failureLogContainsBusinessContext() {
        CapturingAppender appender = attachAppender();
        try {
            assertThatThrownBy(() -> executor.execute(CONSUMER, () -> "articleUuid=abc-123, eventId=evt-9", () -> {
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class);
        } finally {
            detachAppender(appender);
        }

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
    void logContextNotEvaluatedOnSuccess() {
        assertThatCode(() -> executor.execute(CONSUMER, () -> {
            throw new AssertionError("成功路徑不應求值 logContext");
        }, () -> { })).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("logContext 自身拋例外時被防護：退回不帶上下文，往外拋的仍是業務原始例外")
    void throwingLogContextFallsBackWithoutContext() {
        CapturingAppender appender = attachAppender();
        IllegalStateException boom = new IllegalStateException("boom");
        try {
            assertThatThrownBy(() -> executor.execute(CONSUMER, () -> {
                throw new IllegalStateException("context boom");
            }, () -> {
                throw boom;
            })).isSameAs(boom);
        } finally {
            detachAppender(appender);
        }

        assertThat(appender.events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getMessage().getFormattedMessage()).contains(CONSUMER);
        });
    }

    @Test
    @DisplayName("logContext 為 null 或回傳 null／空白時，行為等同不帶 logContext 的版本（仍往外拋）")
    void nullOrBlankLogContextBehavesLikeWithoutContext() {
        IllegalStateException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> executor.execute(CONSUMER, (java.util.function.Supplier<String>) null, () -> {
            throw boom;
        })).isSameAs(boom);
        assertThatThrownBy(() -> executor.execute(CONSUMER, () -> null, () -> {
            throw boom;
        })).isSameAs(boom);
        assertThatThrownBy(() -> executor.execute(CONSUMER, () -> "  ", () -> {
            throw boom;
        })).isSameAs(boom);
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
