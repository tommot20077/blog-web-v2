package dowob.xyz.blog.module.article.consumer;

import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.infrastructure.mq.ThrowingRunnable;
import dowob.xyz.blog.module.article.event.ArticleViewedEvent;
import dowob.xyz.blog.module.article.service.ViewCountService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ViewCountConsumer} 單元測試
 *
 * <p>驗證 RabbitMQ 消費者正確呼叫計數服務並 ACK 消息，且對重送具冪等性。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ViewCountConsumer 單元測試")
class ViewCountConsumerTest {

    /** 瀏覽計數服務（Mock） */
    @Mock
    private ViewCountService viewCountService;

    /** 冪等處理服務（Mock） */
    @Mock
    private IdempotencyService idempotencyService;

    /** RabbitMQ Channel（Mock） */

    /** 交易管理員替身：用來觀察 commit／rollback（getTransaction 回傳簡易 status） */
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    /** 真實的 ack/nack/冪等共用包裝（非 Mock），搭配 Mock 的 IdempotencyService 與交易管理員 */
    private ConsumerExecutor consumerExecutor;

    /** 待測消費者 */
    private ViewCountConsumer viewCountConsumer;

    /**
     * 建立被測物件：ConsumerExecutor 使用真實實例，冪等服務為 Mock、交易由 Mock 交易管理員模擬。
     */
    @BeforeEach
    void buildSubjectUnderTest() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        consumerExecutor = new ConsumerExecutor(new TransactionTemplate(txManager), idempotencyService);
        viewCountConsumer = new ViewCountConsumer(viewCountService, consumerExecutor);
    }

    /**
     * 情境：收到 ArticleViewedEvent → 增加計數並 ACK
     */
    @Test
    @DisplayName("handleArticleViewed：成功增加計數並正常返回")
    void handleArticleViewed_success_incrementsAndAcks() throws IOException {
        UUID uuid = UUID.randomUUID();
        ArticleViewedEvent event = new ArticleViewedEvent(UUID.randomUUID(), uuid, Instant.now());
        long deliveryTag = 42L;
        when(idempotencyService.tryMark(event.eventId(), ViewCountConsumer.CONSUMER_NAME)).thenReturn(true);

        viewCountConsumer.handleArticleViewed(event);

        verify(viewCountService).incrementRedisViewCount(uuid);
    }

    /**
     * 情境：處理失敗（Redis 拋出例外）→ 呼叫 basicNack
     */
    @Test
    @DisplayName("handleArticleViewed：處理失敗時讓例外往外拋")
    void handleArticleViewed_onFailure_callsBasicNack() throws IOException {
        UUID uuid = UUID.randomUUID();
        ArticleViewedEvent event = new ArticleViewedEvent(UUID.randomUUID(), uuid, Instant.now());
        long deliveryTag = 99L;
        when(idempotencyService.tryMark(event.eventId(), ViewCountConsumer.CONSUMER_NAME)).thenReturn(true);
        doThrow(new RuntimeException("Redis error")).when(viewCountService).incrementRedisViewCount(uuid);

        assertThatThrownBy(() -> viewCountConsumer.handleArticleViewed(event)).isInstanceOf(RuntimeException.class);
    }

    /**
     * 情境：同一事件重送兩次（相同 eventId）→ 計數只增加一次，兩次皆 ACK。
     *
     * <p>at-least-once 語意下 broker 可能重送；計數為非冪等 +1 操作，
     * 若不去重則會多算。以 IdempotencyService 對 eventId 去重。</p>
     */
    @Test
    @DisplayName("handleArticleViewed：同一事件重送 → 計數只加一次（冪等）")
    void handleArticleViewed_duplicateEvent_incrementsOnlyOnce() throws IOException {
        UUID uuid = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        ArticleViewedEvent event = new ArticleViewedEvent(eventId, uuid, Instant.now());
        // 第一次 tryMark 回 true（首次處理），第二次回 false（已處理過要 skip）
        when(idempotencyService.tryMark(eventId, ViewCountConsumer.CONSUMER_NAME))
                .thenReturn(true, false);

        viewCountConsumer.handleArticleViewed(event);
        viewCountConsumer.handleArticleViewed(event);

        verify(viewCountService, times(1)).incrementRedisViewCount(uuid);
    }

    /**
     * 情境：舊訊息（eventId 為 null）→ 照舊處理，不呼叫 idempotencyService。
     *
     * <p>上線瞬間佇列中的舊 payload 無 eventId，反序列化為 null；
     * 此路徑須直接處理,不可因 null dedup key 進 DLQ。</p>
     */
    @Test
    @DisplayName("handleArticleViewed：eventId 為 null（舊訊息）→ 照舊處理不做去重")
    void handleArticleViewed_nullEventId_processesWithoutIdempotency() throws IOException {
        UUID uuid = UUID.randomUUID();
        ArticleViewedEvent event = new ArticleViewedEvent(null, uuid, Instant.now());

        viewCountConsumer.handleArticleViewed(event);

        verify(viewCountService).incrementRedisViewCount(uuid);
        verify(idempotencyService, never()).tryMark(any(), any());
    }

    /**
     * 情境：成功處理 → 經 ConsumerExecutor 計入 processed 指標（consumer tag 為 CONSUMER_NAME）
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handleArticleViewed：成功時 ConsumerExecutor 計入 processed 指標")
    void handleArticleViewed_success_recordsProcessedMetric() throws Exception {
        ArticleViewedEvent event = new ArticleViewedEvent(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        when(idempotencyService.tryMark(event.eventId(), ViewCountConsumer.CONSUMER_NAME)).thenReturn(true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            viewCountConsumer.handleArticleViewed(event);

            assertThat(count(registry, ConsumerExecutor.PROCESSED_METRIC, ViewCountConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ViewCountConsumer.CONSUMER_NAME)).isZero();
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
    @DisplayName("handleArticleViewed：失敗時計入 failed 指標，ERROR 日誌保留業務上下文")
    void handleArticleViewed_onFailure_recordsMetricsAndLogsContext() throws Exception {
        UUID uuid = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        ArticleViewedEvent event = new ArticleViewedEvent(eventId, uuid, Instant.now());
        when(idempotencyService.tryMark(eventId, ViewCountConsumer.CONSUMER_NAME)).thenReturn(true);
        doThrow(new RuntimeException("Redis error")).when(viewCountService).incrementRedisViewCount(uuid);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            List<LogEvent> logs = captureExecutorLogs(() -> assertThatThrownBy(() -> viewCountConsumer.handleArticleViewed(event)).isInstanceOf(RuntimeException.class));

            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ViewCountConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.DEAD_LETTERED_METRIC, ViewCountConsumer.CONSUMER_NAME)).as("dead_lettered 由容器層的 DeadLetterCountingErrorHandler 計，ConsumerExecutor 不計").isZero();
            assertThat(logs).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getMessage().getFormattedMessage())
                        .contains(uuid.toString())
                        .contains(eventId.toString());
            });
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    /**
     * 情境：業務（Redis 計數）失敗 → 標記所在的交易 rollback、不 commit，訊息 nack。
     *
     * @throws IOException basicNack 可能拋出的 IO 例外
     */
    @Test
    @DisplayName("handleArticleViewed：業務失敗 → 冪等標記所在交易 rollback（不 commit）並往外拋例外")
    void handleArticleViewed_onBodyFailure_rollsBackMarkTransaction() throws IOException {
        UUID uuid = UUID.randomUUID();
        ArticleViewedEvent event = new ArticleViewedEvent(UUID.randomUUID(), uuid, Instant.now());
        when(idempotencyService.tryMark(event.eventId(), ViewCountConsumer.CONSUMER_NAME)).thenReturn(true);
        doThrow(new RuntimeException("Redis error")).when(viewCountService).incrementRedisViewCount(uuid);

        assertThatThrownBy(() -> viewCountConsumer.handleArticleViewed(event)).isInstanceOf(RuntimeException.class);

        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
    }

    /**
     * 情境：成功 → 先標記、再計數、commit 之後才 ack。
     *
     * @throws IOException basicAck 可能拋出的 IO 例外
     */
    @Test
    @DisplayName("handleArticleViewed：成功 → tryMark、計數、commit 依序發生")
    void handleArticleViewed_success_marksThenCountsThenCommitsThenAcks() throws IOException {
        UUID uuid = UUID.randomUUID();
        ArticleViewedEvent event = new ArticleViewedEvent(UUID.randomUUID(), uuid, Instant.now());
        when(idempotencyService.tryMark(event.eventId(), ViewCountConsumer.CONSUMER_NAME)).thenReturn(true);

        viewCountConsumer.handleArticleViewed(event);

        org.mockito.InOrder order = inOrder(idempotencyService, viewCountService, txManager);
        order.verify(idempotencyService).tryMark(event.eventId(), ViewCountConsumer.CONSUMER_NAME);
        order.verify(viewCountService).incrementRedisViewCount(uuid);
        order.verify(txManager).commit(any());
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
