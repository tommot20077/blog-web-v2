package dowob.xyz.blog.module.search.listener;

import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.search.config.SearchRabbitMqConfig;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.listener.dto.ArticleArchivedMessage;
import dowob.xyz.blog.module.search.listener.dto.ArticleDeletedMessage;
import dowob.xyz.blog.module.search.listener.dto.ArticlePublishedMessage;
import dowob.xyz.blog.module.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 文章搜尋索引監聽器
 *
 * <p>
 * 消費 RabbitMQ 隊列，接收文章發布、更新、刪除、下架事件，
 * 同步維護 Elasticsearch 索引。
 * 失敗日誌與計數統一交由 {@link ConsumerExecutor} 處理；失敗往外拋，由容器重試、耗盡後進 DLQ。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleSearchListener {

    /** 發布事件 consumer 識別名（ConsumerExecutor 指標的 consumer tag） */
    public static final String CONSUMER_NAME_INDEX = "search.index";

    /** 更新事件 consumer 識別名（ConsumerExecutor 指標的 consumer tag） */
    public static final String CONSUMER_NAME_INDEX_UPDATE = "search.index-update";

    /** 刪除事件 consumer 識別名（ConsumerExecutor 指標的 consumer tag） */
    public static final String CONSUMER_NAME_INDEX_DELETE = "search.index-delete";

    /** 下架事件 consumer 識別名（ConsumerExecutor 指標的 consumer tag） */
    public static final String CONSUMER_NAME_INDEX_ARCHIVE = "search.index-archive";

    /**
     * 搜尋服務
     */
    private final SearchService searchService;

    /**
     * MQ consumer 共用執行包裝（失敗日誌、計數、冪等）
     */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 接收文章發布事件，建立 Elasticsearch 索引
     *
     * @param message     文章發布訊息
     */
    @RabbitListener(queues = SearchRabbitMqConfig.QUEUE_SEARCH_INDEX, containerFactory = "rabbitListenerContainerFactory")
    public void onArticlePublished(ArticlePublishedMessage message) {
        log.info("收到文章發布事件，開始建立索引：uuid={}, eventId={}", message.getArticleUuid(), message.getEventId());
        consumerExecutor.execute(CONSUMER_NAME_INDEX, () -> "articleUuid=" + message.getArticleUuid() + ", eventId=" + message.getEventId(),
                () -> {
                    ArticleDocument document = toDocument(message);
                    searchService.indexArticle(document);
                    log.debug("文章索引建立成功：uuid={}", message.getArticleUuid());
                });
    }

    /**
     * 接收文章更新事件，重新索引已發布文章
     *
     * <p>
     * 使用與 {@link #onArticlePublished} 相同的 DTO 結構（ArticlePublishedMessage），
     * 因為更新事件攜帶完整文章資料。
     * </p>
     *
     * @param message     文章更新訊息（結構同發布訊息）
     */
    @RabbitListener(queues = SearchRabbitMqConfig.QUEUE_SEARCH_INDEX_UPDATE, containerFactory = "rabbitListenerContainerFactory")
    public void onArticleUpdated(ArticlePublishedMessage message) {
        log.info("收到文章更新事件，重新索引：uuid={}, eventId={}", message.getArticleUuid(), message.getEventId());
        consumerExecutor.execute(CONSUMER_NAME_INDEX_UPDATE, () -> "articleUuid=" + message.getArticleUuid() + ", eventId=" + message.getEventId(),
                () -> {
                    ArticleDocument document = toDocument(message);
                    searchService.indexArticle(document);
                    log.debug("文章索引更新成功：uuid={}", message.getArticleUuid());
                });
    }

    /**
     * 接收文章刪除事件，從 Elasticsearch 移除索引
     *
     * @param message     文章刪除訊息
     */
    @RabbitListener(queues = SearchRabbitMqConfig.QUEUE_SEARCH_INDEX_DELETE, containerFactory = "rabbitListenerContainerFactory")
    public void onArticleDeleted(ArticleDeletedMessage message) {
        log.info("收到文章刪除事件，移除索引：uuid={}, eventId={}", message.getArticleUuid(), message.getEventId());
        consumerExecutor.execute(CONSUMER_NAME_INDEX_DELETE, () -> "articleUuid=" + message.getArticleUuid() + ", eventId=" + message.getEventId(),
                () -> {
                    searchService.deleteIndex(message.getArticleUuid().toString());
                    log.debug("文章索引移除成功：uuid={}", message.getArticleUuid());
                });
    }

    /**
     * 接收文章下架事件，從 Elasticsearch 移除索引
     *
     * <p>
     * 下架（PUBLISHED → ARCHIVED）代表文章不再公開，索引若不移除，
     * 搜尋結果仍會曝光已下架文章的標題與內文摘要。處置與刪除相同（移除 document），
     * 但走獨立的 routing key／queue——{@code article.deleted} 另有 series 模組訂閱並遞減
     * {@code series.article_count}，共用會使該計數被誤扣（見
     * {@code SearchRabbitMqConfig#ROUTING_KEY_ARCHIVED}）。
     * </p>
     *
     * <p>
     * {@code deleteById} 對不存在的 document 為 no-op，故本處理天然冪等，重送不會出錯。
     * 文章若之後被復原並重新發布，索引會由 {@code article.published} 事件重新建立。
     * </p>
     *
     * @param message     文章下架訊息
     */
    @RabbitListener(queues = SearchRabbitMqConfig.QUEUE_SEARCH_INDEX_ARCHIVE, containerFactory = "rabbitListenerContainerFactory")
    public void onArticleArchived(ArticleArchivedMessage message) {
        log.info("收到文章下架事件，移除索引：uuid={}", message.getArticleUuid());
        /*
         * 失敗會由容器重試（最多 3 次），耗盡才進 DLQ。下架是合規動作，這條路徑最終失敗
         * 代表文章仍留在搜尋索引裡，故失敗日誌（由 ConsumerExecutor 輸出）必須帶足人工補送所需的上下文：
         * articleUuid（要刪哪一篇）與 eventId（對得回 producer 端的那筆事件）。
         */
        consumerExecutor.execute(CONSUMER_NAME_INDEX_ARCHIVE,
                () -> "articleUuid=" + message.getArticleUuid() + ", eventId=" + message.getEventId(),
                () -> {
                    searchService.deleteIndex(message.getArticleUuid().toString());
                    log.debug("已下架文章索引移除成功：uuid={}", message.getArticleUuid());
                });
    }

    /**
     * 將訊息 DTO 轉換為 Elasticsearch Document
     *
     * @param message 文章發布/更新訊息
     * @return ArticleDocument
     */
    private ArticleDocument toDocument(ArticlePublishedMessage message) {
        List<ArticleDocument.TagInfo> tags = message.getTags() == null ? List.of()
                : message.getTags().stream()
                        .map(t -> new ArticleDocument.TagInfo(t.getId(), t.getName(), t.getSlug()))
                        .collect(Collectors.toList());

        return ArticleDocument.builder()
                .id(message.getArticleUuid().toString())
                .title(message.getTitle())
                .summary(message.getSummary())
                .content(message.getContentText())
                .slug(message.getSlug())
                .author(new ArticleDocument.AuthorInfo(
                        message.getAuthorUuid() == null ? null : message.getAuthorUuid().toString(),
                        message.getAuthorNickname()))
                .tags(tags)
                .publishedAt(message.getPublishedAt())
                .viewCount(0L)
                .likeCount(0L)
                .status("PUBLISHED")
                .build();
    }
}
