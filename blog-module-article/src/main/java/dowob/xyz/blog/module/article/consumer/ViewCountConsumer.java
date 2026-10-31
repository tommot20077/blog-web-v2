package dowob.xyz.blog.module.article.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.module.article.event.ArticleViewedEvent;
import dowob.xyz.blog.module.article.service.ViewCountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 文章瀏覽計數 RabbitMQ 消費者
 *
 * <p>
 * 監聽 {@link ArticleRabbitMqConfig#QUEUE_VIEW_COUNT} 隊列，
 * 收到 {@link ArticleViewedEvent} 後呼叫 {@link ViewCountService} 增加 Redis 暫存計數，
 * ack／nack、失敗計數與 {@code eventId} 冪等去重統一交由
 * {@link ConsumerExecutor#executeIdempotent} 處理（業務只寫 Redis，標記在計數成功後才 commit；
 * 計數成功但 commit 失敗時重送會多計一次，屬 at-least-once 的可接受誤差）。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ViewCountConsumer {

    /** 冪等消費 consumer 識別名（用於 (event_id, consumer_name) dedup） */
    public static final String CONSUMER_NAME = "article.view-count";

    /** 瀏覽計數服務 */
    private final ViewCountService viewCountService;

    /** MQ consumer 共用 ack/nack 包裝與計數 */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理文章被瀏覽事件
     *
     * @param event       文章瀏覽事件
     * @param channel     RabbitMQ Channel，交由 {@link ConsumerExecutor} 執行 ack／nack
     * @param deliveryTag 消息投遞標籤
     */
    @RabbitListener(queues = ArticleRabbitMqConfig.QUEUE_VIEW_COUNT)
    public void handleArticleViewed(ArticleViewedEvent event,
                                    Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        consumerExecutor.executeIdempotent(CONSUMER_NAME,
                () -> "articleUuid=" + event.articleUuid() + ", eventId=" + event.eventId(),
                channel, deliveryTag, event.eventId(), () -> {
                    viewCountService.incrementRedisViewCount(event.articleUuid());
                    log.debug("文章 {} 瀏覽計數已增加", event.articleUuid());
                });
    }
}
