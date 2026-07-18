package dowob.xyz.blog.module.tag.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.module.tag.config.TagRabbitMqConfig;
import dowob.xyz.blog.module.tag.event.ArticleTagEvent;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * 標籤使用計數消費者
 *
 * <p>
 * 監聽文章標籤事件（{@link ArticleTagEvent}），
 * 對事件中每個標籤 ID 執行使用計數遞增，並同步更新 Redis 熱門標籤分數。
 * 若標籤 ID 不存在，則靜默跳過，不拋出例外。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TagUsageConsumer {

    /** 冪等消費 consumer 識別名（用於 (event_id, consumer_name) dedup） */
    public static final String CONSUMER_NAME = "tag.usage-count";

    /**
     * 標籤資料存取物件
     */
    private final TagRepository tagRepository;

    /**
     * Redis 操作模板（String 類型）
     */
    private final RedisTemplate<String, String> stringRedisTemplate;

    /**
     * MQ event 冪等處理服務
     */
    private final IdempotencyService idempotencyService;

    /**
     * 處理文章標籤事件，遞增對應標籤的使用計數
     *
     * <p>
     * 對事件中每個標籤 ID：查找標籤 → 遞增 usageCount → 儲存至資料庫 → 更新 Redis ZSet 分數。
     * 不存在的標籤 ID 將被靜默忽略。
     * 採用手動 ACK 模式：處理成功則 basicAck；失敗則 basicNack（送 DLQ）。
     * </p>
     *
     * @param event       文章標籤事件
     * @param channel     RabbitMQ Channel，用於手動 ACK
     * @param deliveryTag 消息投遞標籤
     */
    @RabbitListener(queues = TagRabbitMqConfig.QUEUE_TAG_ARTICLE_TAGGED)
    public void handleArticleTagged(ArticleTagEvent event,
                                    Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            /* 冪等 check：舊訊息 eventId 為 null 則跳過去重照舊處理；重送（markProcessed=false）則 skip */
            if (event.eventId() != null
                    && !idempotencyService.markProcessed(event.eventId(), CONSUMER_NAME)) {
                log.debug("標籤事件 {} 已處理過，skip", event.eventId());
                channel.basicAck(deliveryTag, false);
                return;
            }
            for (UUID tagId : event.tagIds()) {
                Optional<Tag> tagOpt = tagRepository.findById(tagId);
                if (tagOpt.isEmpty()) {
                    continue;
                }
                Tag tag = tagOpt.get();
                tag.incrementUsage();
                tagRepository.save(tag);
                stringRedisTemplate.opsForZSet().incrementScore(RedisKeyConstant.TAG_HOT_KEY, tagId.toString(), 1.0);
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("處理文章標籤事件失敗，訊息送往 DLQ: {}", e.getMessage(), e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException nackEx) {
                log.error("NACK 亦失敗", nackEx);
            }
        }
    }
}
