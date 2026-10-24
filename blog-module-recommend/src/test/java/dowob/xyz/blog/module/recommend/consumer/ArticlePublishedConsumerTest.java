package dowob.xyz.blog.module.recommend.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.infrastructure.mq.ThrowingRunnable;
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
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArticlePublishedConsumer 單元測試
 *
 * <p>
 * 驗證處理文章發布事件時：<br>
 * 1. 正常情境：清除快取並呼叫 basicAck；<br>
 * 2. ACK 失敗情境：IOException 時呼叫 basicNack 防止訊息卡住。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticlePublishedConsumer 單元測試")
class ArticlePublishedConsumerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private Channel channel;

    private ArticlePublishedConsumer consumer;

    private static final UUID ARTICLE_UUID = UUID.randomUUID();
    private static final long DELIVERY_TAG = 42L;

    /**
     * 建立受測物件
     */
    @BeforeEach
    void setUp() {
        consumer = new ArticlePublishedConsumer(stringRedisTemplate, new ConsumerExecutor());
    }

    private ArticlePublishedEvent event() {
        return new ArticlePublishedEvent(
                ARTICLE_UUID, 1L, "Test Article", LocalDateTime.now(),
                "test-slug", "Test summary", "content text",
                "testuser", "Test User", List.of());
    }

    @Test
    @DisplayName("正常情境：清除快取並呼叫 basicAck")
    void handleArticlePublished_正常_清除快取並ACK() throws IOException {
        consumer.handleArticlePublished(event(), channel, DELIVERY_TAG);

        verify(stringRedisTemplate).delete("recommend:related:" + ARTICLE_UUID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("ACK 失敗時呼叫 basicNack 避免訊息卡住")
    void handleArticlePublished_ACK失敗_呼叫basicNack() throws IOException {
        doThrow(new IOException("ACK failed")).when(channel).basicAck(DELIVERY_TAG, false);

        consumer.handleArticlePublished(event(), channel, DELIVERY_TAG);

        verify(channel).basicNack(eq(DELIVERY_TAG), eq(false), eq(false));
    }

    @Test
    @DisplayName("Redis 拋出 RuntimeException 時仍應呼叫 basicNack")
    void handleArticlePublished_Redis拋出RuntimeException_呼叫basicNack() throws IOException {
        org.springframework.data.redis.RedisConnectionFailureException redisEx =
                new org.springframework.data.redis.RedisConnectionFailureException("連線失敗");
        doThrow(redisEx).when(stringRedisTemplate).delete(anyString());

        consumer.handleArticlePublished(event(), channel, DELIVERY_TAG);

        verify(channel).basicNack(eq(DELIVERY_TAG), eq(false), eq(false));
    }

    /**
     * 情境：成功處理 → 經 ConsumerExecutor 計入 processed 指標（consumer tag 為 CONSUMER_NAME）
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handleArticlePublished：成功時 ConsumerExecutor 計入 processed 指標")
    void handleArticlePublished_success_recordsProcessedMetric() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            consumer.handleArticlePublished(event(), channel, 5L);

            assertThat(count(registry, ConsumerExecutor.PROCESSED_METRIC, ArticlePublishedConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ArticlePublishedConsumer.CONSUMER_NAME)).isZero();
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
    @DisplayName("handleArticlePublished：失敗時計入 failed／dead_lettered 指標，ERROR 日誌保留業務上下文")
    void handleArticlePublished_onFailure_recordsMetricsAndLogsContext() throws Exception {
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("連線失敗"))
                .when(stringRedisTemplate).delete(anyString());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            List<LogEvent> logs = captureExecutorLogs(() -> consumer.handleArticlePublished(event(), channel, 6L));

            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ArticlePublishedConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.DEAD_LETTERED_METRIC, ArticlePublishedConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(logs).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getMessage().getFormattedMessage())
                        .contains(ARTICLE_UUID.toString());
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
