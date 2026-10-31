package dowob.xyz.blog.module.comment.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.infrastructure.mq.ThrowingRunnable;
import dowob.xyz.blog.module.comment.mapper.CommentArticleCleanupMapper;
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
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link CommentArticleDeletedConsumer} 單元測試。
 *
 * <p>{@link ConsumerExecutor} 使用真實實例（{@code @Spy}，非 Mock），ack／nack 與日誌走真實路徑。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommentArticleDeletedConsumer")
class CommentArticleDeletedConsumerTest {

    @Mock private CommentArticleCleanupMapper cleanupMapper;
    /** 真實的 ack/nack 共用包裝（Spy 實例，非 Mock） */
    @Spy private ConsumerExecutor consumerExecutor = new ConsumerExecutor();
    @InjectMocks private CommentArticleDeletedConsumer consumer;

    private static ArticleDeletedEvent event(UUID articleUuid) {
        return new ArticleDeletedEvent(UUID.randomUUID(), articleUuid, List.of(), List.of(), Instant.now(), null, null);
    }

    @Test
    @DisplayName("收到事件：以 articleUuid 刪除該文章留言並正常返回")
    void onArticleDeleted_deletesCommentsByArticleUuidAndAcks() throws Exception {
        UUID articleUuid = UUID.randomUUID();

        consumer.onArticleDeleted(event(articleUuid));

        verify(cleanupMapper, times(1)).deleteByArticleUuid(articleUuid);
    }

    @Test
    @DisplayName("舊 payload 缺 articleUuid：不刪除、仍正常返回（避免無限重送）")
    void onArticleDeleted_nullArticleUuid_doesNotDeleteAndAcks() throws Exception {
        consumer.onArticleDeleted(event(null));

        verifyNoInteractions(cleanupMapper);
    }

    @Test
    @DisplayName("刪除拋例外：例外往外拋（交由容器重試，耗盡進 DLQ）")
    void onArticleDeleted_mapperThrows_nacksTowardsDlq() throws Exception {
        UUID articleUuid = UUID.randomUUID();
        doThrow(new RuntimeException("DB down")).when(cleanupMapper).deleteByArticleUuid(articleUuid);

        assertThatThrownBy(() -> consumer.onArticleDeleted(event(articleUuid))).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("失敗時 ERROR 日誌帶 articleUuid 與 eventId")
    void onArticleDeleted_onFailure_logsArticleUuidAndEventId() throws Exception {
        UUID articleUuid = UUID.randomUUID();
        ArticleDeletedEvent ev = event(articleUuid);
        doThrow(new RuntimeException("DB down")).when(cleanupMapper).deleteByArticleUuid(any());

        List<LogEvent> logs = captureExecutorLogs(() -> assertThatThrownBy(() -> consumer.onArticleDeleted(ev)).isInstanceOf(RuntimeException.class));

        assertThat(logs).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getMessage().getFormattedMessage())
                    .contains("articleUuid=" + articleUuid)
                    .contains("eventId=" + ev.eventId());
        });
    }

    @Test
    @DisplayName("onArticleDeleted 訂閱 comment.article-deleted queue")
    void onArticleDeleted_listensOnCommentArticleDeletedQueue() throws Exception {
        RabbitListener listener = CommentArticleDeletedConsumer.class
                .getMethod("onArticleDeleted", ArticleDeletedEvent.class)
                .getAnnotation(RabbitListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.queues()).containsExactly("comment.article-deleted");
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
            super("comment-consumer-executor-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
