package dowob.xyz.blog.module.recommend.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.recommend.config.RecommendRabbitMqConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 文章發布事件消費者（推薦模組）
 *
 * <p>
 * 監聽 {@link RecommendRabbitMqConfig#QUEUE_RECOMMEND_ARTICLE_PUBLISHED} 隊列，
 * 當文章發布後清除該文章的相關文章推薦快取（{@code recommend:related:{articleUuid}}），
 * 確保下次請求時重新計算推薦結果。
 * 採用手動 ACK 模式：ack／nack 與失敗計數統一交由 {@link ConsumerExecutor} 處理
 * （處理成功則 ack；任何失敗則 nack，不重新入隊，送 DLQ）。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticlePublishedConsumer {

    /** consumer 識別名（作為 ConsumerExecutor 指標與日誌的 consumer tag） */
    public static final String CONSUMER_NAME = "recommend.article-published";

    /**
     * Redis 快取操作模板
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * MQ consumer 共用 ack/nack 包裝與計數
     */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理文章發布事件，清除相關文章推薦快取
     *
     * @param event      文章發布事件
     * @param channel    RabbitMQ Channel，交由 {@link ConsumerExecutor} 執行 ack／nack
     * @param deliveryTag 消息遞送標籤
     */
    @RabbitListener(queues = RecommendRabbitMqConfig.QUEUE_RECOMMEND_ARTICLE_PUBLISHED)
    public void handleArticlePublished(
            ArticlePublishedEvent event,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {

        consumerExecutor.execute(CONSUMER_NAME, () -> "articleUuid=" + event.articleUuid(),
                channel, deliveryTag, () -> {
                    String cacheKey = RedisKeyConstant.getRelatedKey(event.articleUuid().toString());
                    stringRedisTemplate.delete(cacheKey);
                    log.debug("已清除文章推薦快取，articleUuid={}", event.articleUuid());
                });
    }
}
