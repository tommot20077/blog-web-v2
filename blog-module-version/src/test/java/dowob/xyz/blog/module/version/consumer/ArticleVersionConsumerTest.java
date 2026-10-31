package dowob.xyz.blog.module.version.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.infrastructure.mq.ThrowingRunnable;
import dowob.xyz.blog.infrastructure.event.ArticleContentChangedEvent.Action;
import dowob.xyz.blog.infrastructure.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.version.service.AutoSnapshotPolicy;
import dowob.xyz.blog.module.version.service.VersioningService;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArticleVersionConsumer 單元測試。
 *
 * <p>驗證共用 manual ACK container factory 下的訊息確認流程：
 * 處理成功呼叫 basicAck；處理失敗或 ACK 失敗呼叫 basicNack（不重新入隊，送 DLQ）。
 * 與 ArticlePublishedConsumerTest 對齊以維持全站慣例。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleVersionConsumer 單元測試")
class ArticleVersionConsumerTest {

    @Mock
    private VersioningService versioningService;

    @Mock
    private AutoSnapshotPolicy autoSnapshotPolicy;

    @Mock
    private Channel channel;

    /** 冪等標記服務（Mock，預設視為首次處理） */
    @Mock
    private IdempotencyService idempotencyService;

    /** 交易管理員替身：用來觀察 commit／rollback */
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    private ArticleVersionConsumer consumer;

    private static final UUID ARTICLE_UUID = UUID.randomUUID();
    private static final long DELIVERY_TAG = 42L;

    @BeforeEach
    void setUp() {
        lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(idempotencyService.tryMark(any(), anyString())).thenReturn(true);
        consumer = new ArticleVersionConsumer(versioningService, autoSnapshotPolicy,
                new ConsumerExecutor(new TransactionTemplate(txManager), idempotencyService));
    }

    private ArticleContentChangedEvent event(Action action) {
        return event(action, ARTICLE_UUID);
    }

    private ArticleContentChangedEvent event(Action action, UUID articleUuid) {
        return new ArticleContentChangedEvent(
                UUID.randomUUID(), articleUuid, null, action, Instant.now());
    }

    @Test
    @DisplayName("SAVED + shouldSnapshot=true: 寫入快照並 basicAck")
    void onContentChanged_savedAndShouldSnapshot_recordsAndAcks() throws IOException {
        when(autoSnapshotPolicy.shouldSnapshot(ARTICLE_UUID)).thenReturn(true);

        consumer.onContentChanged(event(Action.SAVED), channel, DELIVERY_TAG);

        verify(versioningService).recordAutoSnapshot(ARTICLE_UUID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("SAVED + shouldSnapshot=false: 不寫入但仍 basicAck")
    void onContentChanged_savedButPolicyFalse_skipsButAcks() throws IOException {
        when(autoSnapshotPolicy.shouldSnapshot(ARTICLE_UUID)).thenReturn(false);

        consumer.onContentChanged(event(Action.SAVED), channel, DELIVERY_TAG);

        verify(versioningService, never()).recordAutoSnapshot(ARTICLE_UUID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("PUBLISHED: 凍結發布快照並 basicAck")
    void onContentChanged_published_freezesAndAcks() throws IOException {
        consumer.onContentChanged(event(Action.PUBLISHED), channel, DELIVERY_TAG);

        verify(versioningService).freezePublished(ARTICLE_UUID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("RESTORED: no-op 但仍 basicAck")
    void onContentChanged_restored_isNoOpButAcks() throws IOException {
        consumer.onContentChanged(event(Action.RESTORED), channel, DELIVERY_TAG);

        verify(versioningService, never()).recordAutoSnapshot(ARTICLE_UUID);
        verify(versioningService, never()).freezePublished(ARTICLE_UUID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("舊 payload 缺 articleUuid: 不觸發任何快照流程，仍 basicAck（重送也不會變好）")
    void onContentChanged_nullArticleUuid_skipsAndAcks() throws IOException {
        consumer.onContentChanged(event(Action.SAVED, null), channel, DELIVERY_TAG);
        consumer.onContentChanged(event(Action.PUBLISHED, null), channel, DELIVERY_TAG);

        verifyNoInteractions(versioningService, autoSnapshotPolicy);
        verify(channel, times(2)).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("Service 拋例外: 呼叫 basicNack（不重新入隊）")
    void onContentChanged_serviceThrows_callsBasicNack() throws IOException {
        when(autoSnapshotPolicy.shouldSnapshot(ARTICLE_UUID)).thenReturn(true);
        doThrow(new RuntimeException("DB error")).when(versioningService).recordAutoSnapshot(ARTICLE_UUID);

        consumer.onContentChanged(event(Action.SAVED), channel, DELIVERY_TAG);

        verify(channel).basicNack(eq(DELIVERY_TAG), eq(false), eq(false));
    }

    @Test
    @DisplayName("ACK 失敗時呼叫 basicNack 避免訊息卡住")
    void onContentChanged_ackThrows_callsBasicNack() throws IOException {
        when(autoSnapshotPolicy.shouldSnapshot(ARTICLE_UUID)).thenReturn(true);
        doThrow(new IOException("ACK failed")).when(channel).basicAck(DELIVERY_TAG, false);

        consumer.onContentChanged(event(Action.SAVED), channel, DELIVERY_TAG);

        verify(channel).basicNack(eq(DELIVERY_TAG), eq(false), eq(false));
    }

    @Test
    @DisplayName("SAVED/PUBLISHED 帶 eventId: 以 (eventId, CONSUMER_NAME) 標記，業務失敗時標記所在交易 rollback")
    void onContentChanged_serviceThrows_rollsBackMarkTransaction() throws IOException {
        ArticleContentChangedEvent ev = event(Action.PUBLISHED);
        doThrow(new RuntimeException("DB error")).when(versioningService).freezePublished(ARTICLE_UUID);

        consumer.onContentChanged(ev, channel, DELIVERY_TAG);

        verify(idempotencyService).tryMark(ev.eventId(), ArticleVersionConsumer.CONSUMER_NAME);
        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    @DisplayName("同一事件重送（tryMark=false）: 不再寫快照，仍 basicAck")
    void onContentChanged_duplicateEvent_skipsSnapshotButAcks() throws IOException {
        ArticleContentChangedEvent ev = event(Action.PUBLISHED);
        when(idempotencyService.tryMark(ev.eventId(), ArticleVersionConsumer.CONSUMER_NAME)).thenReturn(false);

        consumer.onContentChanged(ev, channel, DELIVERY_TAG);

        verify(versioningService, never()).freezePublished(ARTICLE_UUID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("RESTORED 與缺 articleUuid 是 no-op: 不寫 processed_events（沒有業務可去重）")
    void onContentChanged_noOpEvents_doNotTouchIdempotency() throws IOException {
        consumer.onContentChanged(event(Action.RESTORED), channel, DELIVERY_TAG);
        consumer.onContentChanged(event(Action.SAVED, null), channel, DELIVERY_TAG);

        verifyNoInteractions(idempotencyService);
        verify(channel, times(2)).basicAck(DELIVERY_TAG, false);
    }

    /**
     * 情境：成功處理 → 經 ConsumerExecutor 計入 processed 指標（consumer tag 為 CONSUMER_NAME）
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("onContentChanged：成功時 ConsumerExecutor 計入 processed 指標")
    void onContentChanged_success_recordsProcessedMetric() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            consumer.onContentChanged(event(Action.PUBLISHED), channel, 5L);

            assertThat(count(registry, ConsumerExecutor.PROCESSED_METRIC, ArticleVersionConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ArticleVersionConsumer.CONSUMER_NAME)).isZero();
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
    @DisplayName("onContentChanged：失敗時計入 failed／dead_lettered 指標，ERROR 日誌保留業務上下文")
    void onContentChanged_onFailure_recordsMetricsAndLogsContext() throws Exception {
        doThrow(new RuntimeException("DB error")).when(versioningService).freezePublished(ARTICLE_UUID);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            List<LogEvent> logs = captureExecutorLogs(() -> consumer.onContentChanged(event(Action.PUBLISHED), channel, 6L));

            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ArticleVersionConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.DEAD_LETTERED_METRIC, ArticleVersionConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(logs).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getMessage().getFormattedMessage())
                        .contains("articleUuid=" + ARTICLE_UUID)
                        .contains("action=PUBLISHED");
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
