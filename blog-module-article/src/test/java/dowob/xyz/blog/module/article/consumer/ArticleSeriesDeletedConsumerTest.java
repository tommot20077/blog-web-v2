package dowob.xyz.blog.module.article.consumer;

import dowob.xyz.blog.infrastructure.event.SeriesDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.infrastructure.mq.ThrowingRunnable;
import dowob.xyz.blog.module.article.mapper.ArticleSeriesCleanupMapper;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ArticleSeriesDeletedConsumer} 單元測試（M6-b）。
 *
 * <p>{@link ConsumerExecutor} 使用真實實例（非 Mock），ack／nack 與日誌走真實路徑。
 * consumer 只讀事件的 {@code seriesUuid}；清空歸屬是單一 {@code UPDATE ... WHERE series_uuid = ?}，
 * 天然冪等，不使用 IdempotencyService。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleSeriesDeletedConsumer")
class ArticleSeriesDeletedConsumerTest {

    @Mock private ArticleSeriesCleanupMapper cleanupMapper;

    private ArticleSeriesDeletedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ArticleSeriesDeletedConsumer(cleanupMapper, new ConsumerExecutor());
    }

    private static SeriesDeletedEvent event(UUID seriesUuid) {
        return new SeriesDeletedEvent(UUID.randomUUID(), seriesUuid, Instant.now());
    }

    @Test
    @DisplayName("收到事件：以 seriesUuid 清空成員文章的系列歸屬，然後正常返回")
    void onSeriesDeleted_clearsMembershipBySeriesUuidAndAcks() throws Exception {
        UUID seriesUuid = UUID.randomUUID();

        consumer.onSeriesDeleted(event(seriesUuid));

        verify(cleanupMapper).clearSeriesByUuid(seriesUuid);
    }

    @Test
    @DisplayName("沒有成員文章（清空 0 列）：視為成功，仍正常返回（重複事件不出錯）")
    void onSeriesDeleted_noMembers_stillAcks() throws Exception {
        UUID seriesUuid = UUID.randomUUID();
        org.mockito.Mockito.when(cleanupMapper.clearSeriesByUuid(seriesUuid)).thenReturn(0);

        consumer.onSeriesDeleted(event(seriesUuid));
    }

    @Test
    @DisplayName("缺 seriesUuid 的 payload：不更新任何資料、記 warn、仍正常返回（避免無限重送）")
    void onSeriesDeleted_nullSeriesUuid_warnsAndAcksWithoutUpdating() throws Exception {
        SeriesDeletedEvent ev = event(null);

        List<LogEvent> logs = captureLogs(ArticleSeriesDeletedConsumer.class,
                () -> consumer.onSeriesDeleted(ev));

        verifyNoInteractions(cleanupMapper);
        assertThat(logs).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getMessage().getFormattedMessage()).contains("eventId=" + ev.eventId());
        });
    }

    @Test
    @DisplayName("更新拋例外：例外往外拋（交由容器重試，耗盡進 DLQ）")
    void onSeriesDeleted_updateThrows_nacksToDlq() throws Exception {
        UUID seriesUuid = UUID.randomUUID();
        doThrow(new RuntimeException("DB down")).when(cleanupMapper).clearSeriesByUuid(seriesUuid);

        assertThatThrownBy(() -> consumer.onSeriesDeleted(event(seriesUuid))).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("失敗時 ERROR 日誌帶 seriesUuid 與 eventId")
    void onSeriesDeleted_onFailure_logsSeriesUuidAndEventId() throws Exception {
        UUID seriesUuid = UUID.randomUUID();
        SeriesDeletedEvent ev = event(seriesUuid);
        doThrow(new RuntimeException("DB down")).when(cleanupMapper).clearSeriesByUuid(seriesUuid);

        List<LogEvent> logs = captureLogs(ConsumerExecutor.class,
                () -> assertThatThrownBy(() -> consumer.onSeriesDeleted(ev)).isInstanceOf(RuntimeException.class));

        assertThat(logs).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getMessage().getFormattedMessage())
                    .contains("seriesUuid=" + seriesUuid)
                    .contains("eventId=" + ev.eventId());
        });
    }

    @Test
    @DisplayName("onSeriesDeleted 訂閱 article.series-deleted queue")
    void onSeriesDeleted_listensOnArticleSeriesDeletedQueue() throws Exception {
        RabbitListener listener = ArticleSeriesDeletedConsumer.class
                .getMethod("onSeriesDeleted", SeriesDeletedEvent.class)
                .getAnnotation(RabbitListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.queues()).containsExactly("article.series-deleted");
    }

    /**
     * 執行動作並捕捉指定類別輸出的日誌事件（測試環境沒有 log4j2 設定檔時預設只輸出 ERROR 以上，
     * 因此明確放寬該 logger 到 ALL，才不依賴環境就能分辨 warn 與 error）。
     *
     * @param source 輸出日誌的類別
     * @param action 要執行的動作
     * @return 捕捉到的日誌事件
     * @throws Exception 動作拋出的例外
     */
    private static List<LogEvent> captureLogs(Class<?> source, ThrowingRunnable action) throws Exception {
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        Logger logger = (Logger) LogManager.getLogger(source);
        Level original = logger.getLevel();
        Configurator.setLevel(source.getName(), Level.ALL);
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.removeAppender(appender);
            appender.stop();
            Configurator.setLevel(source.getName(), original);
        }
        return appender.events;
    }

    /** 捕捉日誌事件的測試用 appender（專案日誌實作為 log4j2） */
    private static final class CapturingAppender extends AbstractAppender {

        /** 捕捉到的日誌事件 */
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("article-series-consumer-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
