package dowob.xyz.blog.infrastructure.mq;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
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
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * {@link ConsumerExecutor#executeIdempotent} 的單元測試。
 *
 * <p>交易管理員與冪等服務皆為 Mockito 替身，驗證的是「呼叫順序與交易結局」
 * （commit 先於 ack、失敗時 rollback 且 nack）；標記與業務是否真的同生同滅由
 * {@code ConsumerExecutorIdempotencyIntegrationTest} 以真實 Postgres 證明。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ConsumerExecutor.executeIdempotent 單元測試")
class ConsumerExecutorIdempotentTest {

    /** 測試用 consumer 名稱 */
    private static final String CONSUMER = "test-idempotent-consumer";

    /** 測試用 delivery tag */
    private static final long TAG = 7L;

    /** 模擬的交易管理員，用來觀察 commit／rollback */
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    /** 模擬的冪等服務 */
    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);

    /** 模擬的 AMQP channel */
    private final Channel channel = mock(Channel.class);

    /** 被測物件 */
    private ConsumerExecutor executor;

    /** 測試期間掛上全域 Metrics 的 registry */
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        executor = new ConsumerExecutor(new TransactionTemplate(txManager), idempotencyService);
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(registry);
        registry.close();
    }

    /**
     * 讀取指定指標在 {@link #CONSUMER} 上的計數，不存在時視為 0。
     *
     * @param metric 指標名稱
     * @return 計數值
     */
    private double count(String metric) {
        var counter = registry.find(metric).tag("consumer", CONSUMER).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("eventId 為 null（舊訊息）：照舊執行 body、不開交易、不標記、ack，並記 WARN 帶 consumerName 與上下文")
    void nullEventId_runsBodyWithoutMarking_andWarns() throws Exception {
        AtomicInteger runs = new AtomicInteger();

        List<LogEvent> logs = captureLogs(() -> executor.executeIdempotent(
                CONSUMER, () -> "articleUuid=abc", channel, TAG, null, runs::incrementAndGet));

        assertThat(runs).hasValue(1);
        verifyNoInteractions(idempotencyService);
        verifyNoInteractions(txManager);
        verify(channel).basicAck(TAG, false);
        assertThat(count(ConsumerExecutor.PROCESSED_METRIC)).isEqualTo(1.0);
        assertThat(logs).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getMessage().getFormattedMessage()).contains(CONSUMER).contains("articleUuid=abc");
        });
    }

    @Test
    @DisplayName("首次處理：先標記、再執行 body、commit 之後才 ack")
    void firstTime_marksThenRunsBodyThenCommitsThenAcks() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(idempotencyService.tryMark(eventId, CONSUMER)).thenReturn(true);
        AtomicInteger runs = new AtomicInteger();

        executor.executeIdempotent(CONSUMER, null, channel, TAG, eventId, runs::incrementAndGet);

        assertThat(runs).hasValue(1);
        InOrder order = inOrder(idempotencyService, txManager, channel);
        order.verify(idempotencyService).tryMark(eventId, CONSUMER);
        order.verify(txManager).commit(any());
        order.verify(channel).basicAck(TAG, false);
        verify(txManager, never()).rollback(any());
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        assertThat(count(ConsumerExecutor.PROCESSED_METRIC)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("重複事件（INSERT 影響 0 列）：不執行 body、ack、記 INFO，並計入 processed")
    void duplicate_skipsBodyAndAcks_logsInfo() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(idempotencyService.tryMark(eventId, CONSUMER)).thenReturn(false);
        AtomicInteger runs = new AtomicInteger();

        List<LogEvent> logs = captureLogs(() -> executor.executeIdempotent(
                CONSUMER, () -> "articleUuid=abc", channel, TAG, eventId, runs::incrementAndGet));

        assertThat(runs).hasValue(0);
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        assertThat(count(ConsumerExecutor.PROCESSED_METRIC)).isEqualTo(1.0);
        assertThat(count(ConsumerExecutor.FAILED_METRIC)).isZero();
        assertThat(logs).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getMessage().getFormattedMessage())
                    .contains(CONSUMER).contains(eventId.toString()).contains("articleUuid=abc");
        });
    }

    @Test
    @DisplayName("body 拋例外：交易 rollback（不 commit）、nack(requeue=false)、不 ack，failed 與 dead_lettered 各加一")
    void bodyThrows_rollsBackAndNacks() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(idempotencyService.tryMark(eventId, CONSUMER)).thenReturn(true);

        assertThatCode(() -> executor.executeIdempotent(CONSUMER, null, channel, TAG, eventId, () -> {
            throw new IllegalStateException("boom");
        })).doesNotThrowAnyException();

        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        assertThat(count(ConsumerExecutor.FAILED_METRIC)).isEqualTo(1.0);
        assertThat(count(ConsumerExecutor.DEAD_LETTERED_METRIC)).isEqualTo(1.0);
        assertThat(count(ConsumerExecutor.PROCESSED_METRIC)).isZero();
    }

    @Test
    @DisplayName("body 拋受檢例外：rollback 並 nack，ERROR 日誌帶的是原始例外而非包裝例外")
    void bodyThrowsCheckedException_rollsBackAndLogsOriginalException() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(idempotencyService.tryMark(eventId, CONSUMER)).thenReturn(true);
        IOException original = new IOException("smtp down");

        List<LogEvent> logs = captureLogs(() -> executor.executeIdempotent(
                CONSUMER, () -> "userUuid=u1", channel, TAG, eventId, () -> {
                    throw original;
                }));

        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
        verify(channel).basicNack(TAG, false, false);
        assertThat(logs).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getThrown()).isSameAs(original);
            assertThat(e.getMessage().getFormattedMessage()).contains(CONSUMER).contains("userUuid=u1");
        });
    }

    @Test
    @DisplayName("標記本身失敗（資料庫例外）：不執行 body、rollback、nack")
    void markThrows_doesNotRunBody_rollsBackAndNacks() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(idempotencyService.tryMark(eventId, CONSUMER)).thenThrow(new IllegalStateException("db down"));
        AtomicInteger runs = new AtomicInteger();

        executor.executeIdempotent(CONSUMER, null, channel, TAG, eventId, runs::incrementAndGet);

        assertThat(runs).hasValue(0);
        verify(txManager).rollback(any());
        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("commit 失敗：不 ack、改 nack（標記沒有 commit，body 的效果視其是否屬於同一交易）")
    void commitFails_nacksAndDoesNotAck() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(idempotencyService.tryMark(eventId, CONSUMER)).thenReturn(true);
        doThrow(new IllegalStateException("commit failed")).when(txManager).commit(any());

        assertThatCode(() -> executor.executeIdempotent(CONSUMER, null, channel, TAG, eventId, () -> { }))
                .doesNotThrowAnyException();

        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, times(1)).basicNack(TAG, false, false);
    }

    @Test
    @DisplayName("body 拋 InterruptedException：rollback、nack，並還原執行緒 interrupt 旗標")
    void bodyThrowsInterruptedException_restoresInterruptFlag() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(idempotencyService.tryMark(eventId, CONSUMER)).thenReturn(true);
        try {
            executor.executeIdempotent(CONSUMER, null, channel, TAG, eventId, () -> {
                throw new InterruptedException("stop");
            });

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(txManager).rollback(any());
            verify(channel).basicNack(TAG, false, false);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("沒有交易／冪等基礎設施（無 datasource 模組）卻帶 eventId：不執行 body，nack 而非靜默略過去重")
    void noInfrastructureButEventIdPresent_nacksWithoutRunningBody() throws Exception {
        ConsumerExecutor bare = new ConsumerExecutor();
        AtomicInteger runs = new AtomicInteger();

        assertThatCode(() -> bare.executeIdempotent(
                CONSUMER, null, channel, TAG, UUID.randomUUID(), runs::incrementAndGet))
                .doesNotThrowAnyException();

        assertThat(runs).hasValue(0);
        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    /**
     * 執行動作並捕捉 {@link ConsumerExecutor} 輸出的日誌事件。
     *
     * @param action 要執行的動作
     * @return 捕捉到的日誌事件
     * @throws Exception 動作拋出的例外
     */
    private static List<LogEvent> captureLogs(ThrowingRunnable action) throws Exception {
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        Logger logger = (Logger) LogManager.getLogger(ConsumerExecutor.class);
        logger.addAppender(appender);
        Level original = logger.getLevel();
        /* 測試環境沒有 log4j2 設定，預設只輸出 ERROR；暫時調到 INFO 才捕捉得到 WARN／INFO */
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
            super("idempotent-executor-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
