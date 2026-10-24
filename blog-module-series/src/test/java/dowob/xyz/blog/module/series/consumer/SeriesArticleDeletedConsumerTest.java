package dowob.xyz.blog.module.series.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.infrastructure.mq.ThrowingRunnable;
import dowob.xyz.blog.module.article.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeriesArticleDeletedConsumerTest {

    @Mock private IdempotencyService idempotencyService;
    @Mock private SeriesMapper seriesMapper;
    /** 真實的 ack/nack 共用包裝（Spy 實例，非 Mock，使 ack/nack 與計數走真實路徑） */
    @Spy
    private ConsumerExecutor consumerExecutor = new ConsumerExecutor();

    @Mock private Channel channel;
    @InjectMocks private SeriesArticleDeletedConsumer consumer;

    private static final long DELIVERY_TAG = 42L;

    private ArticleDeletedEvent event(Long seriesId) {
        return new ArticleDeletedEvent(
            UUID.randomUUID(), 100L, UUID.randomUUID(), 1L,
            seriesId, List.of(), List.of(), Instant.now()
        );
    }

    @Test
    void onArticleDeleted_seriesIdNull_acksAndNoOp() throws Exception {
        ArticleDeletedEvent ev = event(null);

        consumer.onArticleDeleted(ev, channel, DELIVERY_TAG);

        verify(idempotencyService, never()).markProcessed(any(), any());
        verify(seriesMapper, never()).decrementArticleCount(any());
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void onArticleDeleted_firstTime_decrementsCountAndAcks() throws Exception {
        ArticleDeletedEvent ev = event(200L);
        when(idempotencyService.markProcessed(eq(ev.eventId()),
                eq(SeriesArticleDeletedConsumer.CONSUMER_NAME))).thenReturn(true);

        consumer.onArticleDeleted(ev, channel, DELIVERY_TAG);

        verify(seriesMapper, times(1)).decrementArticleCount(200L);
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void onArticleDeleted_replayed_skipsDecrementAndAcks() throws Exception {
        ArticleDeletedEvent ev = event(200L);
        when(idempotencyService.markProcessed(eq(ev.eventId()),
                eq(SeriesArticleDeletedConsumer.CONSUMER_NAME))).thenReturn(false);

        consumer.onArticleDeleted(ev, channel, DELIVERY_TAG);

        verify(seriesMapper, never()).decrementArticleCount(any());
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void onArticleDeleted_decrementThrows_nacksTowardsDlq() throws Exception {
        ArticleDeletedEvent ev = event(200L);
        when(idempotencyService.markProcessed(any(), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
            .when(seriesMapper).decrementArticleCount(200L);

        consumer.onArticleDeleted(ev, channel, DELIVERY_TAG);

        verify(channel, never()).basicAck(any(long.class), any(boolean.class));
        verify(channel, times(1)).basicNack(DELIVERY_TAG, false, false);
    }

    /**
     * 情境：成功處理 → 經 ConsumerExecutor 計入 processed 指標（consumer tag 為 CONSUMER_NAME）
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("onArticleDeleted：成功時 ConsumerExecutor 計入 processed 指標")
    void onArticleDeleted_success_recordsProcessedMetric() throws Exception {
        ArticleDeletedEvent ev = event(200L);
        when(idempotencyService.markProcessed(eq(ev.eventId()),
                eq(SeriesArticleDeletedConsumer.CONSUMER_NAME))).thenReturn(true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            consumer.onArticleDeleted(ev, channel, 5L);

            assertThat(count(registry, ConsumerExecutor.PROCESSED_METRIC, SeriesArticleDeletedConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, SeriesArticleDeletedConsumer.CONSUMER_NAME)).isZero();
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
    @DisplayName("onArticleDeleted：失敗時計入 failed／dead_lettered 指標，ERROR 日誌保留業務上下文")
    void onArticleDeleted_onFailure_recordsMetricsAndLogsContext() throws Exception {
        ArticleDeletedEvent ev = event(200L);
        when(idempotencyService.markProcessed(any(), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
            .when(seriesMapper).decrementArticleCount(200L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            List<LogEvent> logs = captureExecutorLogs(() -> consumer.onArticleDeleted(ev, channel, 6L));

            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, SeriesArticleDeletedConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.DEAD_LETTERED_METRIC, SeriesArticleDeletedConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(logs).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getMessage().getFormattedMessage())
                        .contains("articleId=" + ev.articleId())
                        .contains("seriesId=" + ev.seriesId())
                        .contains(ev.eventId().toString());
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
