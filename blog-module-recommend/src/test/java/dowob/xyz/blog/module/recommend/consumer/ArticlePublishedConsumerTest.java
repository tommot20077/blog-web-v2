package dowob.xyz.blog.module.recommend.consumer;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private ArticlePublishedConsumer consumer;

    private static final UUID ARTICLE_UUID = UUID.randomUUID();
    /**
     * 建立受測物件
     */
    @BeforeEach
    void setUp() {
        consumer = new ArticlePublishedConsumer(stringRedisTemplate, new ConsumerExecutor());
    }

    private ArticlePublishedEvent event() {
        return new ArticlePublishedEvent(
                UUID.randomUUID(), ARTICLE_UUID, UUID.randomUUID(), "Test Article", LocalDateTime.now(),
                "test-slug", "Test summary", "content text", "Test User", List.of());
    }

    @Test
    @DisplayName("正常情境：清除快取並正常返回")
    void handleArticlePublished_正常_清除快取並ACK() throws IOException {
        consumer.handleArticlePublished(event());

        verify(stringRedisTemplate).delete("recommend:related:" + ARTICLE_UUID);
    }

    @Test
    @DisplayName("Redis 拋出 RuntimeException 時仍應例外往外拋")
    void handleArticlePublished_Redis拋出RuntimeException_呼叫basicNack() throws IOException {
        org.springframework.data.redis.RedisConnectionFailureException redisEx =
                new org.springframework.data.redis.RedisConnectionFailureException("連線失敗");
        doThrow(redisEx).when(stringRedisTemplate).delete(anyString());

        assertThatThrownBy(() -> consumer.handleArticlePublished(event())).isInstanceOf(RuntimeException.class);
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
            consumer.handleArticlePublished(event());

            assertThat(count(registry, ConsumerExecutor.PROCESSED_METRIC, ArticlePublishedConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ArticlePublishedConsumer.CONSUMER_NAME)).isZero();
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    /**
     * 情境：處理失敗 → failed 指標加一，且 ConsumerExecutor 的 ERROR 日誌保留業務上下文
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handleArticlePublished：失敗時計入 failed 指標，ERROR 日誌保留業務上下文")
    void handleArticlePublished_onFailure_recordsMetricsAndLogsContext() throws Exception {
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("連線失敗"))
                .when(stringRedisTemplate).delete(anyString());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            List<LogEvent> logs = captureExecutorLogs(() -> assertThatThrownBy(() -> consumer.handleArticlePublished(event())).isInstanceOf(RuntimeException.class));

            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ArticlePublishedConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.DEAD_LETTERED_METRIC, ArticlePublishedConsumer.CONSUMER_NAME)).as("dead_lettered 由容器層的 DeadLetterCountingErrorHandler 計，ConsumerExecutor 不計").isZero();
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
