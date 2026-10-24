package dowob.xyz.blog.module.version.consumer;

import com.rabbitmq.client.Channel;
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

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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

    private ArticleVersionConsumer consumer;

    private static final long ARTICLE_ID = 100L;
    private static final long DELIVERY_TAG = 42L;

    @BeforeEach
    void setUp() {
        consumer = new ArticleVersionConsumer(versioningService, autoSnapshotPolicy, new ConsumerExecutor());
    }

    private ArticleContentChangedEvent event(Action action) {
        return new ArticleContentChangedEvent(
                UUID.randomUUID(), ARTICLE_ID, UUID.randomUUID(), 1L, null, action, Instant.now());
    }

    @Test
    @DisplayName("SAVED + shouldSnapshot=true: 寫入快照並 basicAck")
    void onContentChanged_savedAndShouldSnapshot_recordsAndAcks() throws IOException {
        when(autoSnapshotPolicy.shouldSnapshot(ARTICLE_ID)).thenReturn(true);

        consumer.onContentChanged(event(Action.SAVED), channel, DELIVERY_TAG);

        verify(versioningService).recordAutoSnapshot(ARTICLE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("SAVED + shouldSnapshot=false: 不寫入但仍 basicAck")
    void onContentChanged_savedButPolicyFalse_skipsButAcks() throws IOException {
        when(autoSnapshotPolicy.shouldSnapshot(ARTICLE_ID)).thenReturn(false);

        consumer.onContentChanged(event(Action.SAVED), channel, DELIVERY_TAG);

        verify(versioningService, never()).recordAutoSnapshot(ARTICLE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("PUBLISHED: 凍結發布快照並 basicAck")
    void onContentChanged_published_freezesAndAcks() throws IOException {
        consumer.onContentChanged(event(Action.PUBLISHED), channel, DELIVERY_TAG);

        verify(versioningService).freezePublished(ARTICLE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("RESTORED: no-op 但仍 basicAck")
    void onContentChanged_restored_isNoOpButAcks() throws IOException {
        consumer.onContentChanged(event(Action.RESTORED), channel, DELIVERY_TAG);

        verify(versioningService, never()).recordAutoSnapshot(ARTICLE_ID);
        verify(versioningService, never()).freezePublished(ARTICLE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("Service 拋例外: 呼叫 basicNack（不重新入隊）")
    void onContentChanged_serviceThrows_callsBasicNack() throws IOException {
        when(autoSnapshotPolicy.shouldSnapshot(ARTICLE_ID)).thenReturn(true);
        doThrow(new RuntimeException("DB error")).when(versioningService).recordAutoSnapshot(ARTICLE_ID);

        consumer.onContentChanged(event(Action.SAVED), channel, DELIVERY_TAG);

        verify(channel).basicNack(eq(DELIVERY_TAG), eq(false), eq(false));
    }

    @Test
    @DisplayName("ACK 失敗時呼叫 basicNack 避免訊息卡住")
    void onContentChanged_ackThrows_callsBasicNack() throws IOException {
        when(autoSnapshotPolicy.shouldSnapshot(ARTICLE_ID)).thenReturn(true);
        doThrow(new IOException("ACK failed")).when(channel).basicAck(DELIVERY_TAG, false);

        consumer.onContentChanged(event(Action.SAVED), channel, DELIVERY_TAG);

        verify(channel).basicNack(eq(DELIVERY_TAG), eq(false), eq(false));
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
        doThrow(new RuntimeException("DB error")).when(versioningService).freezePublished(ARTICLE_ID);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            List<LogEvent> logs = captureExecutorLogs(() -> consumer.onContentChanged(event(Action.PUBLISHED), channel, 6L));

            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ArticleVersionConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.DEAD_LETTERED_METRIC, ArticleVersionConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(logs).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getMessage().getFormattedMessage())
                        .contains("articleId=" + ARTICLE_ID)
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
