package dowob.xyz.blog.module.user.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
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
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PasswordResetConsumer} 單元測試
 *
 * <p>驗證消費者在處理密碼重設事件後，是否正確呼叫 Manual Ack。M8 起事件帶 {@code userUuid} 與
 * {@code eventId}，consumer 以 {@link IdempotencyService}（依 eventId）去重，避免同一事件重送時
 * 重寄重設密碼信；舊格式 in-flight 訊息（{@code eventId} 為 null）照常寄送。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordResetConsumer 單元測試")
class PasswordResetConsumerTest {

    /** 交易管理員替身：用來觀察 commit／rollback（getTransaction 回傳簡易 status） */
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    /** 真實的 ack/nack/冪等共用包裝（非 Mock），搭配 Mock 的 IdempotencyService 與交易管理員 */
    private ConsumerExecutor consumerExecutor;

    /** 受測物件 */
    private PasswordResetConsumer consumer;

    /**
     * 建立被測物件：ConsumerExecutor 使用真實實例，冪等服務為 Mock、交易由 Mock 交易管理員模擬。
     */
    @BeforeEach
    void buildSubjectUnderTest() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        consumerExecutor = new ConsumerExecutor(new TransactionTemplate(txManager), idempotencyService);
        consumer = new PasswordResetConsumer(userMailService, consumerExecutor);
    }

    /** Mock RabbitMQ Channel */
    @Mock
    private Channel channel;

    /** Mock 郵件服務 */
    @Mock
    private UserMailService userMailService;

    /** Mock 冪等服務（預設視為第一次處理） */
    @Mock
    private IdempotencyService idempotencyService;

    /** 測試用事件 dedup key */
    private static final UUID EVENT_ID = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000011");

    /** 測試用使用者 uuid（與任何 Long 主鍵無數值關聯） */
    private static final UUID USER_UUID = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000012");

    /**
     * 預設第一次處理（tryMark 回 true）。
     */
    @BeforeEach
    void setUp() {
        lenient().when(idempotencyService.tryMark(any(), anyString())).thenReturn(true);
    }

    /**
     * 建立帶 eventId 與 userUuid 的測試事件。
     *
     * @return 新格式的密碼重設申請事件
     */
    private static UserPasswordResetRequestedEvent event() {
        return new UserPasswordResetRequestedEvent(EVENT_ID, USER_UUID, "test@example.com", "reset-token-abc");
    }

    /**
     * 驗證成功處理事件後，basicAck 被正確呼叫
     *
     * @throws IOException basicAck 可能拋出的 IO 例外
     */
    @Test
    @DisplayName("處理密碼重設事件後應呼叫 basicAck")
    void handlePasswordResetRequested_success_acksMessage() throws IOException {
        UserPasswordResetRequestedEvent event =
                event();
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
                event();
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
                event();
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
                event();
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
                event();
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
                        .contains("userUuid=" + USER_UUID)
                        .contains("eventId=" + EVENT_ID);
            });
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    /**
     * 情境：同一事件重送（tryMark 回 false）→ 不重寄重設密碼信，但仍 ack（不進 DLQ）
     *
     * @throws IOException basicAck 可能拋出的 IO 例外
     */
    @Test
    @DisplayName("handlePasswordResetRequested：eventId 已處理過（重送）→ 不重寄重設密碼信，仍 ack")
    void handlePasswordResetRequested_duplicateEventId_skipsMailButAcks() throws IOException {
        when(idempotencyService.tryMark(EVENT_ID, PasswordResetConsumer.CONSUMER_NAME)).thenReturn(false);

        consumer.handlePasswordResetRequested(event(), channel, 11L);

        verify(userMailService, never()).sendPasswordResetEmail(any());
        verify(channel).basicAck(11L, false);
        verify(channel, never()).basicNack(eq(11L), eq(false), eq(false));
    }

    /**
     * 情境：第一次處理 → 以 eventId 與 CONSUMER_NAME 標記並寄信
     *
     * @throws IOException basicAck 可能拋出的 IO 例外
     */
    @Test
    @DisplayName("handlePasswordResetRequested：第一次處理 → 以 (eventId, CONSUMER_NAME) 標記後寄信")
    void handlePasswordResetRequested_firstDelivery_marksEventIdThenSends() throws IOException {
        consumer.handlePasswordResetRequested(event(), channel, 12L);

        verify(idempotencyService).tryMark(EVENT_ID, PasswordResetConsumer.CONSUMER_NAME);
        verify(userMailService).sendPasswordResetEmail(event());
        verify(channel).basicAck(12L, false);
    }

    /**
     * 情境：舊格式 in-flight 訊息（eventId、userUuid 皆為 null）→ 不去重，照常寄信並 ack
     *
     * @throws IOException basicAck 可能拋出的 IO 例外
     */
    @Test
    @DisplayName("handlePasswordResetRequested：舊格式訊息（eventId 為 null）→ 不呼叫冪等服務，照常寄信並 ack")
    void handlePasswordResetRequested_legacyMessageWithoutEventId_sendsWithoutDedup() throws IOException {
        UserPasswordResetRequestedEvent legacy =
                new UserPasswordResetRequestedEvent(null, null, "test@example.com", "reset-token-abc");

        consumer.handlePasswordResetRequested(legacy, channel, 13L);

        verifyNoInteractions(idempotencyService);
        verify(userMailService).sendPasswordResetEmail(legacy);
        verify(channel).basicAck(13L, false);
    }

    /**
     * 情境：重複事件被跳過時，營運端必須看得到（INFO，帶 eventId、userUuid 與「重複事件已跳過」），
     * 否則 DLQ 人工重投或 ack 前當機造成的重投被靜默吞掉，無從追查。
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handlePasswordResetRequested：重複事件被跳過時以 INFO 記錄 eventId、userUuid 與「重複事件已跳過」")
    void handlePasswordResetRequested_duplicateEventId_logsSkipAtInfo() throws Exception {
        when(idempotencyService.tryMark(EVENT_ID, PasswordResetConsumer.CONSUMER_NAME)).thenReturn(false);

        List<LogEvent> logs = captureLogsOf(ConsumerExecutor.class, () -> consumer.handlePasswordResetRequested(event(), channel, 14L));

        assertThat(logs).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getMessage().getFormattedMessage())
                    .contains("重複事件已跳過")
                    .contains("eventId=" + EVENT_ID)
                    .contains("userUuid=" + USER_UUID);
        });
    }

    /**
     * 情境：寄信失敗 → 標記所在的交易 rollback、不 commit（寄信失敗不留下「已處理」標記），訊息 nack。
     *
     * @throws IOException basicNack 可能拋出的 IO 例外
     */
    @Test
    @DisplayName("handlePasswordResetRequested：寄信失敗 → 冪等標記所在交易 rollback（不 commit）並 nack")
    void handlePasswordResetRequested_onMailFailure_rollsBackMarkTransaction() throws IOException {
        UserPasswordResetRequestedEvent event = event();
        doThrow(new IllegalStateException("SMTP failed")).when(userMailService).sendPasswordResetEmail(event);

        consumer.handlePasswordResetRequested(event, channel, 21L);

        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
        verify(channel).basicNack(21L, false, false);
        verify(channel, never()).basicAck(21L, false);
    }

    /**
     * 情境：寄信成功 → 寄信完成之後標記才 commit，commit 之後才 ack（Yuan 2026-10-31：寄信成功後才標記）。
     *
     * @throws IOException basicAck 可能拋出的 IO 例外
     */
    @Test
    @DisplayName("handlePasswordResetRequested：寄信成功後標記才 commit，commit 之後才 ack")
    void handlePasswordResetRequested_onSuccess_commitsMarkOnlyAfterMailSent() throws IOException {
        UserPasswordResetRequestedEvent event = event();

        consumer.handlePasswordResetRequested(event, channel, 22L);

        org.mockito.InOrder order = inOrder(userMailService, txManager, channel);
        order.verify(userMailService).sendPasswordResetEmail(event);
        order.verify(txManager).commit(any());
        order.verify(channel).basicAck(22L, false);
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
        return captureLogsOf(ConsumerExecutor.class, action);
    }

    /**
     * 執行動作並捕捉指定類別的 logger 輸出的日誌事件。
     *
     * @param source 日誌來源類別
     * @param action 要執行的動作
     * @return 捕捉到的日誌事件
     * @throws Exception 動作拋出的例外
     */
    private static List<LogEvent> captureLogsOf(Class<?> source, ThrowingRunnable action) throws Exception {
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        Logger logger = (Logger) LogManager.getLogger(source);
        logger.addAppender(appender);
        Level original = logger.getLevel();
        /* ConsumerExecutor 所在套件的預設輸出等級較高；暫時調到 INFO 才捕捉得到「重複事件已跳過」 */
        logger.setLevel(Level.INFO);
        try {
            action.run();
        } finally {
            logger.setLevel(original);
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
