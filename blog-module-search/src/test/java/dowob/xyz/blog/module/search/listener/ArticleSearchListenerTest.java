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
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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
