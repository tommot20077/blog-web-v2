package dowob.xyz.blog.module.search.listener;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.module.search.config.SearchRabbitMqConfig;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.listener.dto.ArticleArchivedMessage;
import dowob.xyz.blog.module.search.listener.dto.ArticleDeletedMessage;
import dowob.xyz.blog.module.search.listener.dto.ArticlePublishedMessage;
import dowob.xyz.blog.module.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文章搜尋索引監聽器
 *
 * <p>
 * 消費 RabbitMQ 隊列，接收文章發布、更新、刪除、下架事件，
 * 同步維護 Elasticsearch 索引。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleSearchListener {

    /**
     * 搜尋服務
     */
    private final SearchService searchService;

    /**
     * 接收文章發布事件，建立 Elasticsearch 索引
     *
     * @param message     文章發布訊息
     * @param channel     RabbitMQ Channel
     * @param deliveryTag 訊息標籤
     * @throws IOException 處理 ACK/NACK 時的 IO 異常
     */
    @RabbitListener(queues = SearchRabbitMqConfig.QUEUE_SEARCH_INDEX, containerFactory = "rabbitListenerContainerFactory")
    public void onArticlePublished(ArticlePublishedMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        log.info("收到文章發布事件，開始建立索引：uuid={}", message.getArticleUuid());
        try {
            ArticleDocument document = toDocument(message);
            searchService.indexArticle(document);
            channel.basicAck(deliveryTag, false);
            log.debug("文章索引建立成功並已 ACK：uuid={}", message.getArticleUuid());
        } catch (Exception e) {
            log.error("文章索引失敗：uuid={}, error={}", message.getArticleUuid(), e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
        }
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
     * @param channel     RabbitMQ Channel
     * @param deliveryTag 訊息標籤
     * @throws IOException 處理 ACK/NACK 時的 IO 異常
     */
    @RabbitListener(queues = SearchRabbitMqConfig.QUEUE_SEARCH_INDEX_UPDATE, containerFactory = "rabbitListenerContainerFactory")
    public void onArticleUpdated(ArticlePublishedMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        log.info("收到文章更新事件，重新索引：uuid={}", message.getArticleUuid());
        try {
            ArticleDocument document = toDocument(message);
            searchService.indexArticle(document);
            channel.basicAck(deliveryTag, false);
            log.debug("文章索引更新成功並已 ACK：uuid={}", message.getArticleUuid());
        } catch (Exception e) {
            log.error("文章索引更新失敗：uuid={}, error={}", message.getArticleUuid(), e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * 接收文章刪除事件，從 Elasticsearch 移除索引
     *
     * @param message     文章刪除訊息
     * @param channel     RabbitMQ Channel
     * @param deliveryTag 訊息標籤
     * @throws IOException 處理 ACK/NACK 時的 IO 異常
     */
    @RabbitListener(queues = SearchRabbitMqConfig.QUEUE_SEARCH_INDEX_DELETE, containerFactory = "rabbitListenerContainerFactory")
    public void onArticleDeleted(ArticleDeletedMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        log.info("收到文章刪除事件，移除索引：uuid={}", message.getArticleUuid());
        try {
            searchService.deleteIndex(message.getArticleUuid().toString());
            channel.basicAck(deliveryTag, false);
            log.debug("文章索引移除成功並已 ACK：uuid={}", message.getArticleUuid());
        } catch (Exception e) {
            log.error("文章索引移除失敗：uuid={}, error={}", message.getArticleUuid(), e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
        }
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
     * @param channel     RabbitMQ Channel
     * @param deliveryTag 訊息標籤
     * @throws IOException 處理 ACK/NACK 時的 IO 異常
     */
    @RabbitListener(queues = SearchRabbitMqConfig.QUEUE_SEARCH_INDEX_ARCHIVE, containerFactory = "rabbitListenerContainerFactory")
    public void onArticleArchived(ArticleArchivedMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        log.info("收到文章下架事件，移除索引：uuid={}", message.getArticleUuid());
        try {
            searchService.deleteIndex(message.getArticleUuid().toString());
            channel.basicAck(deliveryTag, false);
            log.debug("已下架文章索引移除成功並已 ACK：uuid={}", message.getArticleUuid());
        } catch (Exception e) {
            log.error("已下架文章索引移除失敗：uuid={}, error={}", message.getArticleUuid(), e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
        }
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
                        message.getAuthorId(),
                        message.getAuthorUsername(),
                        message.getAuthorNickname()))
                .tags(tags)
                .publishedAt(message.getPublishedAt())
                .viewCount(0L)
                .likeCount(0L)
                .status("PUBLISHED")
                .build();
    }
}
