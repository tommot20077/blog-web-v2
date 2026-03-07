package dowob.xyz.blog.module.article.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.module.article.event.ArticleViewedEvent;
import dowob.xyz.blog.module.article.service.ViewCountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 文章瀏覽計數 RabbitMQ 消費者
 *
 * <p>
 * 監聽 {@link ArticleRabbitMqConfig#QUEUE_VIEW_COUNT} 隊列，
 * 收到 {@link ArticleViewedEvent} 後呼叫 {@link ViewCountService} 增加 Redis 暫存計數，
 * 並手動發送 ACK 確認消息已處理。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ViewCountConsumer {

    /** 瀏覽計數服務 */
    private final ViewCountService viewCountService;

    /**
     * 處理文章被瀏覽事件
     *
     * @param event       文章瀏覽事件
     * @param channel     RabbitMQ Channel，用於手動 ACK
     * @param deliveryTag 消息投遞標籤
     */
    @RabbitListener(queues = ArticleRabbitMqConfig.QUEUE_VIEW_COUNT)
    public void handleArticleViewed(ArticleViewedEvent event,
                                    Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            viewCountService.incrementRedisViewCount(event.articleUuid());
            log.debug("文章 {} 瀏覽計數已增加", event.articleUuid());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("處理文章瀏覽事件失敗，訊息送往 DLQ，articleUuid={}", event.articleUuid(), e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException nackEx) {
                log.error("NACK 亦失敗，articleUuid={}", event.articleUuid(), nackEx);
            }
        }
    }
}
