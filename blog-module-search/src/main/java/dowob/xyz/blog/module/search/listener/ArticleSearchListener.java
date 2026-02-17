package dowob.xyz.blog.module.search.listener;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.module.search.config.SearchRabbitMqConfig;
import dowob.xyz.blog.module.search.document.ArticleDocument;
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
 * 消費 RabbitMQ {@value SearchRabbitMqConfig#QUEUE_SEARCH_INDEX} Queue，
 * 接收文章發布事件後，將文章資料索引至 Elasticsearch。
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
     * <p>
     * 訊息由 {@code article.events} Exchange 透過 {@code article.published} routing key
     * 路由，
     * Jackson2JsonMessageConverter 自動反序列化為 {@link ArticlePublishedMessage}。
     * 使用手動確認機制 (MANUAL ACK) 確保索引建立成功。
     * </p>
     *
     * @param message     文章發布訊息
     * @param channel     RabbitMQ Channel
     * @param deliveryTag 訊息標籤
     * @throws IOException 處理 ACK/NACK 時的 IO 異常
     */
    @RabbitListener(queues = SearchRabbitMqConfig.QUEUE_SEARCH_INDEX)
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
            // 發生異常時拒絕訊息並不重新入隊 (requeue=false)，讓它進入 DLQ
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * 將訊息 DTO 轉換為 Elasticsearch Document
     *
     * @param message 文章發布訊息
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
