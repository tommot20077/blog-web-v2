package dowob.xyz.blog.module.reading.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.infrastructure.mq.ThrowingRunnable;
import dowob.xyz.blog.module.reading.mapper.ReadingArticleCleanupMapper;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ReadingArticleDeletedConsumer} 單元測試。
 *
 * <p>{@link ConsumerExecutor} 使用真實實例（非 Mock），ack／nack 與日誌走真實路徑；
 * {@link TransactionTemplate} 使用真實實例搭配 Mock 的交易管理員，以驗證「同一交易、失敗回滾」。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReadingArticleDeletedConsumer")
class ReadingArticleDeletedConsumerTest {

    private static final long DELIVERY_TAG = 42L;

    @Mock private ReadingArticleCleanupMapper cleanupMapper;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;
    @Mock private Channel channel;

    private ReadingArticleDeletedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ReadingArticleDeletedConsumer(cleanupMapper,
                new TransactionTemplate(transactionManager), new ConsumerExecutor());
    }

    private static ArticleDeletedEvent event(UUID articleUuid) {
        return new ArticleDeletedEvent(UUID.randomUUID(), articleUuid, List.of(), List.of(), Instant.now(), null, null);
    }

    @Test
    @DisplayName("收到事件：在同一個交易內依序刪除按讚與收藏並 commit，最後 ack")
    void onArticleDeleted_deletesLikesThenBookmarksInOneTransactionAndAcks() throws Exception {
        UUID articleUuid = UUID.randomUUID();
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

        consumer.onArticleDeleted(event(articleUuid), channel, DELIVERY_TAG);

        InOrder order = inOrder(transactionManager, cleanupMapper, channel);
        order.verify(transactionManager).getTransaction(any());
        order.verify(cleanupMapper).deleteLikesByArticleUuid(articleUuid);
        order.verify(cleanupMapper).deleteBookmarksByArticleUuid(articleUuid);
        order.verify(transactionManager).commit(transactionStatus);
        order.verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("收到事件：同一交易內四張表（按讚、收藏、劃線、閱讀進度）依序刪除後才 commit 與 ack")
    void onArticleDeleted_deletesAllFourTablesInOneTransactionBeforeCommit() throws Exception {
        UUID articleUuid = UUID.randomUUID();
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

        consumer.onArticleDeleted(event(articleUuid), channel, DELIVERY_TAG);

        InOrder order = inOrder(transactionManager, cleanupMapper, channel);
        order.verify(transactionManager).getTransaction(any());
        order.verify(cleanupMapper).deleteLikesByArticleUuid(articleUuid);
        order.verify(cleanupMapper).deleteBookmarksByArticleUuid(articleUuid);
        order.verify(cleanupMapper).deleteHighlightsByArticleUuid(articleUuid);
        order.verify(cleanupMapper).deleteProgressByArticleUuid(articleUuid);
        order.verify(transactionManager).commit(transactionStatus);
        order.verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("最後一張表（閱讀進度）刪除拋例外：交易回滾（不 commit）、nack（requeue=false），不 ack")
    void onArticleDeleted_progressDeleteThrows_rollsBackAndNacks() throws Exception {
        UUID articleUuid = UUID.randomUUID();
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        doThrow(new RuntimeException("DB down")).when(cleanupMapper).deleteProgressByArticleUuid(articleUuid);

        consumer.onArticleDeleted(event(articleUuid), channel, DELIVERY_TAG);

        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, times(1)).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    @DisplayName("舊 payload 缺 articleUuid：不開交易、不刪除、仍 ack（避免無限重送）")
    void onArticleDeleted_nullArticleUuid_doesNotDeleteAndAcks() throws Exception {
        consumer.onArticleDeleted(event(null), channel, DELIVERY_TAG);

        verifyNoInteractions(cleanupMapper, transactionManager);
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("第二張表刪除拋例外：交易回滾（不 commit）、nack（requeue=false）送往 DLQ，不 ack")
    void onArticleDeleted_secondDeleteThrows_rollsBackAndNacks() throws Exception {
        UUID articleUuid = UUID.randomUUID();
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        doThrow(new RuntimeException("DB down")).when(cleanupMapper).deleteBookmarksByArticleUuid(articleUuid);

        consumer.onArticleDeleted(event(articleUuid), channel, DELIVERY_TAG);

        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, times(1)).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    @DisplayName("失敗時 ERROR 日誌帶 articleUuid 與 eventId")
    void onArticleDeleted_onFailure_logsArticleUuidAndEventId() throws Exception {
        UUID articleUuid = UUID.randomUUID();
        ArticleDeletedEvent ev = event(articleUuid);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        doThrow(new RuntimeException("DB down")).when(cleanupMapper).deleteLikesByArticleUuid(any());

        List<LogEvent> logs = captureExecutorLogs(() -> consumer.onArticleDeleted(ev, channel, DELIVERY_TAG));

        assertThat(logs).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getMessage().getFormattedMessage())
                    .contains("articleUuid=" + articleUuid)
                    .contains("eventId=" + ev.eventId());
        });
    }

    @Test
    @DisplayName("onArticleDeleted 訂閱 reading.article-deleted queue")
    void onArticleDeleted_listensOnReadingArticleDeletedQueue() throws Exception {
        RabbitListener listener = ReadingArticleDeletedConsumer.class
                .getMethod("onArticleDeleted", ArticleDeletedEvent.class, Channel.class, long.class)
                .getAnnotation(RabbitListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.queues()).containsExactly("reading.article-deleted");
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
            super("reading-consumer-executor-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
