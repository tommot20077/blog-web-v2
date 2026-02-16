package dowob.xyz.blog.module.recommend.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent;
import dowob.xyz.blog.module.recommend.config.RecommendRabbitMqConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 文章發布事件消費者（推薦模組）
 *
 * <p>
 * 監聽 {@link RecommendRabbitMqConfig#QUEUE_RECOMMEND_ARTICLE_PUBLISHED} 隊列，
 * 當文章發布後清除該文章的相關文章推薦快取（{@code recommend:related:{articleUuid}}），
 * 確保下次請求時重新計算推薦結果。
 * 採用手動 ACK 模式：處理成功則 basicAck；ACK 失敗則 basicNack（不重新入隊，送 DLQ）。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticlePublishedConsumer {

    /**
     * Redis 快取操作模板
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 推薦相關文章快取 Key 前綴
     */
    private static final String RELATED_CACHE_KEY_PREFIX = "recommend:related:";

    /**
     * 處理文章發布事件，清除相關文章推薦快取
     *
     * @param event      文章發布事件
     * @param channel    RabbitMQ Channel，用於手動 ACK
     * @param deliveryTag 消息遞送標籤
     */
    @RabbitListener(
            queues = RecommendRabbitMqConfig.QUEUE_RECOMMEND_ARTICLE_PUBLISHED,
            containerFactory = RecommendRabbitMqConfig.MANUAL_ACK_CONTAINER_FACTORY)
    public void handleArticlePublished(
            ArticlePublishedEvent event,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {

        try {
            String cacheKey = RELATED_CACHE_KEY_PREFIX + event.articleUuid();
            stringRedisTemplate.delete(cacheKey);
            log.debug("已清除文章推薦快取，articleUuid={}", event.articleUuid());
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.error("ACK 失敗，訊息送往 DLQ，articleUuid={}", event.articleUuid(), e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException nackException) {
                log.error("NACK 亦失敗，articleUuid={}", event.articleUuid(), nackException);
            }
        }
    }
}
