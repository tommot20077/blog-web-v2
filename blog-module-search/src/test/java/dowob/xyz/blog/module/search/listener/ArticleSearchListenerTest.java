package dowob.xyz.blog.module.search.listener;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.listener.dto.ArticleArchivedMessage;
import dowob.xyz.blog.module.search.listener.dto.ArticleDeletedMessage;
import dowob.xyz.blog.module.search.listener.dto.ArticlePublishedMessage;
import dowob.xyz.blog.module.search.service.SearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * ArticleSearchListener 單元測試
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleSearchListener 單元測試")
class ArticleSearchListenerTest {

    @Mock
    private SearchService searchService;

    @Mock
    private Channel channel;

    @InjectMocks
    private ArticleSearchListener listener;

    /**
     * 建立完整 ArticlePublishedMessage 測試資料
     */
    private ArticlePublishedMessage buildPublishedMessage() {
        ArticlePublishedMessage message = new ArticlePublishedMessage();
        message.setArticleUuid(UUID.randomUUID());
        message.setAuthorId(1L);
        message.setTitle("測試標題");
        message.setSummary("測試摘要");
        message.setContentText("測試內容純文字");
        message.setSlug("test-slug");
        message.setPublishedAt(LocalDateTime.now());
        message.setAuthorUsername("yuan");
        message.setAuthorNickname("Yuan");
        message.setTags(List.of());
        return message;
    }

    /**
     * 文章發布事件測試
     */
    @Nested
    @DisplayName("onArticlePublished")
    class OnArticlePublishedTests {

        @Test
        @DisplayName("正常：收到發布訊息時，應建立 ES 索引並 ACK")
        void onArticlePublished_shouldIndexAndAck() throws IOException {
            ArticlePublishedMessage message = buildPublishedMessage();

            listener.onArticlePublished(message, channel, 1L);

            verify(searchService).indexArticle(any(ArticleDocument.class));
            verify(channel).basicAck(1L, false);
        }

        @Test
        @DisplayName("正常：標籤為 null 時，應正常建立索引（不拋出例外）")
        void onArticlePublished_withNullTags_shouldIndexAndAck() throws IOException {
            ArticlePublishedMessage message = buildPublishedMessage();
            message.setTags(null);

            listener.onArticlePublished(message, channel, 2L);

            verify(searchService).indexArticle(any(ArticleDocument.class));
            verify(channel).basicAck(2L, false);
        }

        @Test
        @DisplayName("異常：ES 索引失敗時，應 NACK 至 DLQ（不重排隊）")
        void onArticlePublished_onEsException_shouldNack() throws IOException {
            ArticlePublishedMessage message = buildPublishedMessage();

            doThrow(new RuntimeException("ES cluster unavailable")).when(searchService).indexArticle(any());

            listener.onArticlePublished(message, channel, 3L);

            verify(channel).basicNack(3L, false, false);
        }

        @Test
        @DisplayName("異常：basicAck 拋出 IOException 時，應觸發 basicNack")
        void onArticlePublished_onAckIoException_shouldNack() throws IOException {
            ArticlePublishedMessage message = buildPublishedMessage();

            doThrow(new IOException("channel broken")).when(channel).basicAck(4L, false);

            listener.onArticlePublished(message, channel, 4L);

            verify(channel).basicNack(4L, false, false);
        }
    }

    /**
     * 文章更新事件測試
     */
    @Nested
    @DisplayName("onArticleUpdated")
    class OnArticleUpdatedTests {

        @Test
        @DisplayName("正常：收到更新訊息時，應重新索引文章")
        void onArticleUpdated_shouldReindex() throws IOException {
            ArticlePublishedMessage message = new ArticlePublishedMessage();
            message.setArticleUuid(UUID.randomUUID());
            message.setAuthorId(1L);
            message.setTitle("更新後標題");
            message.setSummary("更新後摘要");
            message.setContentText("更新後內容");
            message.setSlug("updated-slug");
            message.setPublishedAt(LocalDateTime.now());
            message.setAuthorUsername("yuan");
            message.setAuthorNickname("Yuan");
            message.setTags(List.of());

            listener.onArticleUpdated(message, channel, 1L);

            verify(searchService).indexArticle(any(ArticleDocument.class));
            verify(channel).basicAck(1L, false);
        }

        @Test
        @DisplayName("異常：重新索引失敗時，應 NACK 至 DLQ")
        void onArticleUpdated_onError_shouldNack() throws IOException {
            ArticlePublishedMessage message = new ArticlePublishedMessage();
            message.setArticleUuid(UUID.randomUUID());
            message.setAuthorId(1L);
            message.setTitle("會失敗的標題");
            message.setTags(List.of());

            doThrow(new RuntimeException("ES down")).when(searchService).indexArticle(any());

            listener.onArticleUpdated(message, channel, 2L);

            verify(channel).basicNack(2L, false, false);
        }
    }

    /**
     * 文章下架事件測試
     *
     * <p>下架（PUBLISHED → ARCHIVED）後文章不再公開，索引必須移除，
     * 否則搜尋結果仍會曝光已下架文章的標題與內文摘要。</p>
     */
    @Nested
    @DisplayName("onArticleArchived")
    class OnArticleArchivedTests {

        @Test
        @DisplayName("正常：收到下架訊息時，應移除該文章的 ES 索引並 ACK")
        void onArticleArchived_shouldDeleteIndexAndAck() throws IOException {
            UUID uuid = UUID.randomUUID();
            ArticleArchivedMessage message = new ArticleArchivedMessage();
            message.setEventId(UUID.randomUUID());
            message.setArticleUuid(uuid);
            message.setOccurredAt(Instant.now());

            listener.onArticleArchived(message, channel, 1L);

            verify(searchService).deleteIndex(uuid.toString());
            verify(channel).basicAck(1L, false);
        }

        @Test
        @DisplayName("異常：移除索引失敗時，應 NACK 至 DLQ（不 requeue）")
        void onArticleArchived_onError_shouldNack() throws IOException {
            UUID uuid = UUID.randomUUID();
            ArticleArchivedMessage message = new ArticleArchivedMessage();
            message.setEventId(UUID.randomUUID());
            message.setArticleUuid(uuid);
            message.setOccurredAt(Instant.now());

            doThrow(new RuntimeException("ES down")).when(searchService).deleteIndex(any());

            listener.onArticleArchived(message, channel, 7L);

            verify(channel).basicNack(7L, false, false);
        }

        /**
         * requeue=false 代表訊息直接進 DLQ，不會自動重試。
         * 下架是合規動作，這條路徑失敗＝文章仍留在搜尋索引裡，必須留下可告警、
         * 且足以人工補送的紀錄：ERROR 等級 + articleUuid（要刪哪一篇）+ eventId（對得回原事件）。
         */
        @Test
        @DisplayName("可觀測性：移除索引失敗時，ERROR 日誌須帶 articleUuid 與 eventId")
        void onArticleArchived_onError_logsErrorWithUuidAndEventId() throws IOException {
            UUID uuid = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            ArticleArchivedMessage message = new ArticleArchivedMessage();
            message.setEventId(eventId);
            message.setArticleUuid(uuid);
            message.setOccurredAt(Instant.now());

            doThrow(new RuntimeException("ES down")).when(searchService).deleteIndex(any());

            CapturingAppender appender = attachAppender();
            try {
                listener.onArticleArchived(message, channel, 7L);
            } finally {
                detachAppender(appender);
            }

            assertThat(appender.events).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getMessage().getFormattedMessage())
                        .contains(uuid.toString())
                        .contains(eventId.toString());
            });
        }
    }

    /**
     * 掛上捕捉用 appender 以取得 {@link ArticleSearchListener} 的日誌事件。
     *
     * @return 已啟動並掛載的 appender
     */
    private CapturingAppender attachAppender() {
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        ((Logger) LogManager.getLogger(ArticleSearchListener.class)).addAppender(appender);
        return appender;
    }

    /**
     * 卸載測試用 appender，避免污染其他測試。
     *
     * @param appender 先前掛上的 appender
     */
    private void detachAppender(CapturingAppender appender) {
        ((Logger) LogManager.getLogger(ArticleSearchListener.class)).removeAppender(appender);
        appender.stop();
    }

    /**
     * 捕捉日誌事件的測試用 appender（log4j2 為本專案的日誌實作，見 root pom 排除
     * spring-boot-starter-logging 並改用 log4j-slf4j2-impl）。
     */
    private static final class CapturingAppender extends AbstractAppender {

        /** 捕捉到的日誌事件 */
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("archive-test-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    /**
     * 文章刪除事件測試
     */
    @Nested
    @DisplayName("onArticleDeleted")
    class OnArticleDeletedTests {

        @Test
        @DisplayName("正常：收到刪除訊息時，應移除索引")
        void onArticleDeleted_shouldDeleteIndex() throws IOException {
            UUID uuid = UUID.randomUUID();
            ArticleDeletedMessage message = new ArticleDeletedMessage();
            message.setArticleUuid(uuid);
            message.setDeletedAt(Instant.now());

            listener.onArticleDeleted(message, channel, 1L);

            verify(searchService).deleteIndex(uuid.toString());
            verify(channel).basicAck(1L, false);
        }

        @Test
        @DisplayName("異常：移除索引失敗時，應 NACK 至 DLQ")
        void onArticleDeleted_onError_shouldNack() throws IOException {
            UUID uuid = UUID.randomUUID();
            ArticleDeletedMessage message = new ArticleDeletedMessage();
            message.setArticleUuid(uuid);
            message.setDeletedAt(Instant.now());

            doThrow(new RuntimeException("ES down")).when(searchService).deleteIndex(any());

            listener.onArticleDeleted(message, channel, 3L);

            verify(channel).basicNack(3L, false, false);
        }
    }
}
