package dowob.xyz.blog.infrastructure.mq;

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
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * {@link ConsumerExecutor#executeIdempotent} 的單元測試。
 *
 * <p>交易管理員與冪等服務皆為 Mockito 替身，驗證的是「呼叫順序與交易結局」
 * （先標記再執行 body 再 commit；失敗時 rollback 且把例外往外拋，交給容器重試）；標記與業務是否真的同生同滅由
 * {@code ConsumerExecutorIdempotencyIntegrationTest} 以真實 Postgres 證明。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ConsumerExecutor.executeIdempotent 單元測試")
class ConsumerExecutorIdempotentTest {

    /** 測試用 consumer 名稱 */
    private static final String CONSUMER = "test-idempotent-consumer";

    /** 模擬的交易管理員，用來觀察 commit／rollback */
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    /** 模擬的冪等服務 */
    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);

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
    @DisplayName("eventId 為 null（舊訊息）：照舊執行 body、不開交易、不標記，並記 WARN 帶 consumerName 與上下文")
    void nullEventId_runsBodyWithoutMarking_andWarns() throws Exception {
        AtomicInteger runs = new AtomicInteger();

        List<LogEvent> logs = captureLogs(() -> executor.executeIdempotent(
                CONSUMER, () -> "articleUuid=abc", null, runs::incrementAndGet));

        assertThat(runs).hasValue(1);
        verifyNoInteractions(idempotencyService);
        verifyNoInteractions(txManager);
        assertThat(count(ConsumerExecutor.PROCESSED_METRIC)).isEqualTo(1.0);
        assertThat(logs).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getMessage().getFormattedMessage()).contains(CONSUMER).contains("articleUuid=abc");
        });
    }

    @Test
    @DisplayName("首次處理：先標記、再執行 body、commit；正常返回，processed 加一")
    void firstTime_marksThenRunsBodyThenCommits() {
        UUID eventId = UUID.randomUUID();
        when(idempotencyService.tryMark(eventId, CONSUMER)).thenReturn(true);
        AtomicInteger runs = new AtomicInteger();

        executor.executeIdempotent(CONSUMER, null, eventId, runs::incrementAndGet);

        assertThat(runs).hasValue(1);
        InOrder order = inOrder(idempotencyService, txManager);
        order.verify(idempotencyService).tryMark(eventId, CONSUMER);
        order.verify(txManager).commit(any());
        verify(txManager, never()).rollback(any());
        assertThat(count(ConsumerExecutor.PROCESSED_METRIC)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("重複事件（INSERT 影響 0 列）：不執行 body、正常返回（容器會 ack）、記 INFO，並計入 processed")
    void duplicate_skipsBodyAndReturnsNormally_logsInfo() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(idempotencyService.tryMark(eventId, CONSUMER)).thenReturn(false);
        AtomicInteger runs = new AtomicInteger();

        List<LogEvent> logs = captureLogs(() -> executor.executeIdempotent(
                CONSUMER, () -> "articleUuid=abc", eventId, runs::incrementAndGet));

        assertThat(runs).hasValue(0);
        assertThat(count(ConsumerExecutor.PROCESSED_METRIC)).isEqualTo(1.0);
        assertThat(count(ConsumerExecutor.FAILED_METRIC)).isZero();
        assertThat(logs).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getMessage().getFormattedMessage())
                    .contains(CONSUMER).contains(eventId.toString()).contains("articleUuid=abc");
        });
    }

    @Test
    @DisplayName("body 拋例外：交易 rollback（不 commit），原始例外原樣往外拋，failed 加一")
    void bodyThrows_rollsBackAndRethrowsOriginal() {
        UUID eventId = UUID.randomUUID();
        when(idempotencyService.tryMark(eventId, CONSUMER)).thenReturn(true);
        IllegalStateException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> executor.executeIdempotent(CONSUMER, null, eventId, () -> {
            throw boom;
        })).isSameAs(boom);

        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
        assertThat(count(ConsumerExecutor.FAILED_METRIC)).isEqualTo(1.0);
        assertThat(count(ConsumerExecutor.PROCESSED_METRIC)).isZero();
    }

    @Test
    @DisplayName("body 拋受檢例外：rollback 並往外拋 ConsumerExecutionException（cause 為原始例外），ERROR 日誌帶原始例外")
    void bodyThrowsCheckedException_rollsBackAndLogsOriginalException() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(idempotencyService.tryMark(eventId, CONSUMER)).thenReturn(true);
        IOException original = new IOException("smtp down");

        List<LogEvent> logs = captureLogs(() -> assertThatThrownBy(() -> executor.executeIdempotent(
                CONSUMER, () -> "userUuid=u1", eventId, () -> {
                    throw original;
                })).isInstanceOf(ConsumerExecutionException.class).hasCause(original));

        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
        assertThat(logs).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getThrown()).isSameAs(original);
            assertThat(e.getMessage().getFormattedMessage()).contains(CONSUMER).contains("userUuid=u1");
        });
    }

    @Test
    @DisplayName("body 失敗後 rollback 本身也失敗：往外拋的例外與 ERROR 日誌的例外（含 suppressed）仍含原始 body 例外")
    void rollbackFails_originalBodyExceptionStaysInThrowable() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(idempotencyService.tryMark(eventId, CONSUMER)).thenReturn(true);
        doThrow(new TransactionSystemException("rollback failed")).when(txManager).rollback(any());
        IllegalStateException original = new IllegalStateException("original body failure");

        List<LogEvent> logs = captureLogs(() -> assertThatThrownBy(() -> executor.executeIdempotent(
                CONSUMER, null, eventId, () -> {
                    throw original;
                })).isInstanceOf(TransactionSystemException.class)
                .satisfies(thrown -> {
                    List<Throwable> related = new ArrayList<>();
                    collect(thrown, related);
                    assertThat(related).contains(original);
                }));

        assertThat(logs).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            Throwable thrown = e.getThrown();
            assertThat(thrown).isNotNull();
            List<Throwable> related = new ArrayList<>();
            collect(thrown, related);
            assertThat(related).contains(original);
        });
    }

    /**
     * 遞迴蒐集例外、其 cause 與 suppressed。
     *
     * @param t   起點例外
     * @param out 蒐集結果
     */
    private static void collect(Throwable t, List<Throwable> out) {
        if (t == null || out.contains(t)) {
            return;
        }
        out.add(t);
        collect(t.getCause(), out);
        for (Throwable s : t.getSuppressed()) {
            collect(s, out);
        }
    }

    @Test
    @DisplayName("標記本身失敗（資料庫例外）：不執行 body、rollback、例外往外拋")
    void markThrows_doesNotRunBody_rollsBackAndRethrows() {
        UUID eventId = UUID.randomUUID();
        IllegalStateException dbDown = new IllegalStateException("db down");
        when(idempotencyService.tryMark(eventId, CONSUMER)).thenThrow(dbDown);
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> executor.executeIdempotent(CONSUMER, null, eventId, runs::incrementAndGet))
                .isSameAs(dbDown);

        assertThat(runs).hasValue(0);
        verify(txManager).rollback(any());
    }

    @Test
    @DisplayName("commit 失敗：例外往外拋（容器會 nack 重試），failed 加一，processed 不加")
    void commitFails_rethrowsAndCountsFailed() {
        UUID eventId = UUID.randomUUID();
        when(idempotencyService.tryMark(eventId, CONSUMER)).thenReturn(true);
        IllegalStateException commitFailure = new IllegalStateException("commit failed");
        doThrow(commitFailure).when(txManager).commit(any());

        assertThatThrownBy(() -> executor.executeIdempotent(CONSUMER, null, eventId, () -> { }))
                .isSameAs(commitFailure);

        assertThat(count(ConsumerExecutor.FAILED_METRIC)).isEqualTo(1.0);
        assertThat(count(ConsumerExecutor.PROCESSED_METRIC)).isZero();
    }

    @Test
    @DisplayName("body 拋 InterruptedException：rollback、往外拋 ConsumerExecutionException，並還原執行緒 interrupt 旗標")
    void bodyThrowsInterruptedException_restoresInterruptFlag() {
        UUID eventId = UUID.randomUUID();
        when(idempotencyService.tryMark(eventId, CONSUMER)).thenReturn(true);
        try {
            assertThatThrownBy(() -> executor.executeIdempotent(CONSUMER, null, eventId, () -> {
                throw new InterruptedException("stop");
            })).isInstanceOf(ConsumerExecutionException.class).hasCauseInstanceOf(InterruptedException.class);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(txManager).rollback(any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("沒有交易／冪等基礎設施（無 datasource 模組）卻帶 eventId：不執行 body，明確失敗而非靜默略過去重")
    void noInfrastructureButEventIdPresent_failsWithoutRunningBody() {
        ConsumerExecutor bare = new ConsumerExecutor();
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> bare.executeIdempotent(CONSUMER, null, UUID.randomUUID(), runs::incrementAndGet))
                .isInstanceOf(IllegalStateException.class);

        assertThat(runs).hasValue(0);
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
