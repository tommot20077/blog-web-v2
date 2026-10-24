package dowob.xyz.blog.module.user.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.infrastructure.mq.ThrowingRunnable;
import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.service.UserMailService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PasswordResetConsumer} 單元測試
 *
 * <p>驗證消費者在處理密碼重設事件後，是否正確呼叫 Manual Ack。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordResetConsumer 單元測試")
class PasswordResetConsumerTest {

    /** 真實的 ack/nack 共用包裝（Spy 實例，非 Mock，使 ack/nack 與計數走真實路徑） */
    @Spy
    private ConsumerExecutor consumerExecutor = new ConsumerExecutor();

    /** 受測物件 */
    @InjectMocks
    private PasswordResetConsumer consumer;

    /** Mock RabbitMQ Channel */
    @Mock
    private Channel channel;

    /** Mock 郵件服務 */
    @Mock
    private UserMailService userMailService;

    /**
     * 驗證成功處理事件後，basicAck 被正確呼叫
     *
     * @throws IOException basicAck 可能拋出的 IO 例外
     */
    @Test
    @DisplayName("處理密碼重設事件後應呼叫 basicAck")
    void handlePasswordResetRequested_success_acksMessage() throws IOException {
        UserPasswordResetRequestedEvent event =
                new UserPasswordResetRequestedEvent(1L, "test@example.com", "reset-token-abc");
        long deliveryTag = 42L;

        consumer.handlePasswordResetRequested(event, channel, deliveryTag);

        verify(userMailService).sendPasswordResetEmail(event);
        verify(channel).basicAck(deliveryTag, false);
    }

    /**
     * 驗證寄送密碼重設信失敗時，應呼叫 basicNack 將訊息送至 DLQ
     *
     * @throws IOException basicNack 可能拋出的 IO 例外
     */
    @Test
    @DisplayName("寄送密碼重設信失敗時呼叫 basicNack")
    void handlePasswordResetRequested_onMailFailure_callsBasicNack() throws IOException {
        UserPasswordResetRequestedEvent event =
                new UserPasswordResetRequestedEvent(1L, "test@example.com", "reset-token-abc");
        long deliveryTag = 88L;

        doThrow(new IllegalStateException("SMTP failed")).when(userMailService).sendPasswordResetEmail(event);

        consumer.handlePasswordResetRequested(event, channel, deliveryTag);

        verify(channel).basicNack(eq(deliveryTag), eq(false), eq(false));
        verify(channel, never()).basicAck(deliveryTag, false);
    }

    /**
     * 驗證 basicAck 失敗時，應呼叫 basicNack 將訊息送至 DLQ
     *
     * @throws IOException basicNack 可能拋出的 IO 例外
     */
    @Test
    @DisplayName("basicAck 失敗時呼叫 basicNack")
    void handlePasswordResetRequested_onAckFailure_callsBasicNack() throws IOException {
        UserPasswordResetRequestedEvent event =
                new UserPasswordResetRequestedEvent(1L, "test@example.com", "reset-token-abc");
        long deliveryTag = 99L;

        doThrow(new IOException("ACK failed")).when(channel).basicAck(deliveryTag, false);

        consumer.handlePasswordResetRequested(event, channel, deliveryTag);

        verify(channel).basicNack(eq(deliveryTag), eq(false), eq(false));
    }

    /**
     * 情境：成功處理 → 經 ConsumerExecutor 計入 processed 指標（consumer tag 為 CONSUMER_NAME）
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handlePasswordResetRequested：成功時 ConsumerExecutor 計入 processed 指標")
    void handlePasswordResetRequested_success_recordsProcessedMetric() throws Exception {
        UserPasswordResetRequestedEvent event =
                new UserPasswordResetRequestedEvent(1L, "test@example.com", "reset-token-abc");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            consumer.handlePasswordResetRequested(event, channel, 5L);

            assertThat(count(registry, ConsumerExecutor.PROCESSED_METRIC, PasswordResetConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, PasswordResetConsumer.CONSUMER_NAME)).isZero();
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    /**
     * 情境：處理失敗 → failed／dead_lettered 指標加一，且 ConsumerExecutor 的 ERROR 日誌保留業務上下文
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handlePasswordResetRequested：失敗時計入 failed／dead_lettered 指標，ERROR 日誌保留業務上下文")
    void handlePasswordResetRequested_onFailure_recordsMetricsAndLogsContext() throws Exception {
        UserPasswordResetRequestedEvent event =
                new UserPasswordResetRequestedEvent(1L, "test@example.com", "reset-token-abc");
        doThrow(new IllegalStateException("SMTP failed")).when(userMailService).sendPasswordResetEmail(event);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            List<LogEvent> logs = captureExecutorLogs(() -> consumer.handlePasswordResetRequested(event, channel, 6L));

            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, PasswordResetConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.DEAD_LETTERED_METRIC, PasswordResetConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(logs).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getMessage().getFormattedMessage())
                        .contains("userId=1");
            });
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    /**
     * 讀取指定 consumer 的指標計數，指標不存在時視為 0。
     *
     * @param registry     測試用 registry
     * @param metric       指標名稱
     * @param consumerName consumer 識別名稱（指標的 consumer tag）
     * @return 計數值
     */
    private static double count(SimpleMeterRegistry registry, String metric, String consumerName) {
        Counter counter = registry.find(metric).tag("consumer", consumerName).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /**
     * 執行動作並捕捉 {@link ConsumerExecutor} 輸出的日誌事件。
     *
     * @param action 要執行的動作
     * @return 捕捉到的日誌事件
     * @throws Exception 動作拋出的例外
     */
    private static List<LogEvent> captureExecutorLogs(ThrowingRunnable action) throws Exception {
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        Logger logger = (Logger) LogManager.getLogger(ConsumerExecutor.class);
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
        return appender.events;
    }

    /** 捕捉日誌事件的測試用 appender（專案日誌實作為 log4j2） */
    private static final class CapturingAppender extends AbstractAppender {

        /** 捕捉到的日誌事件 */
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("consumer-executor-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
