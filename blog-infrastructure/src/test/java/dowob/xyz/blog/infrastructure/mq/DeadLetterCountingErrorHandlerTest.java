package dowob.xyz.blog.infrastructure.mq;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DeadLetterCountingErrorHandler} 的單元測試：{@code mq.consumer.dead_lettered} 的唯一計數點。
 *
 * <p>只要容器即將以 requeue=false 拒絕訊息（重試耗盡的 recoverer、不可重試的轉換失敗、無 messageId 的一次失敗），
 * 就計一次；一般的、尚可重試的失敗不計。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("DeadLetterCountingErrorHandler：dead_lettered 計數")
class DeadLetterCountingErrorHandlerTest {

    private static final String QUEUE = "test.queue";

    private final DeadLetterCountingErrorHandler handler = new DeadLetterCountingErrorHandler();

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

    private static Message messageFrom(String queue) {
        MessageProperties properties = new MessageProperties();
        properties.setConsumerQueue(queue);
        return new Message(new byte[0], properties);
    }

    private double deadLettered(String queue) {
        var counter = registry.find(ConsumerExecutor.DEAD_LETTERED_METRIC).tag("queue", queue).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("重試耗盡（cause 鏈含 AmqpRejectAndDontRequeueException，即 recoverer 的結果）→ 計入該 queue 的 dead_lettered")
    void retryExhausted_countsDeadLetteredForQueue() {
        ListenerExecutionFailedException exhausted = new ListenerExecutionFailedException("Retry Policy Exhausted",
                new AmqpRejectAndDontRequeueException("exhausted", new IllegalStateException("boom")), messageFrom(QUEUE));

        assertThatCode(() -> handler.handleError(exhausted)).doesNotThrowAnyException();

        assertThat(deadLettered(QUEUE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("訊息轉換失敗（致命例外）→ 照 Spring 行為拋 AmqpRejectAndDontRequeueException，並計入 dead_lettered")
    void conversionFailure_isRejectedAndCounted() {
        ListenerExecutionFailedException conversion = new ListenerExecutionFailedException("conversion",
                new MessageConversionException("bad json"), messageFrom(QUEUE));

        assertThatThrownBy(() -> handler.handleError(conversion)).isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThat(deadLettered(QUEUE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("一般的、尚可重試的失敗 → 不拋、不計 dead_lettered")
    void ordinaryFailure_isNotCounted() {
        ListenerExecutionFailedException ordinary = new ListenerExecutionFailedException("ordinary",
                new IllegalStateException("boom"), messageFrom(QUEUE));

        assertThatCode(() -> handler.handleError(ordinary)).doesNotThrowAnyException();

        assertThat(deadLettered(QUEUE)).isZero();
    }

    @Test
    @DisplayName("取不到失敗訊息（沒有 failedMessage）→ 仍計數，queue tag 為 unknown")
    void withoutFailedMessage_usesUnknownQueueTag() {
        Throwable rejected = new AmqpRejectAndDontRequeueException("no message");

        assertThatCode(() -> handler.handleError(rejected)).doesNotThrowAnyException();

        assertThat(deadLettered("unknown")).isEqualTo(1.0);
    }
}
