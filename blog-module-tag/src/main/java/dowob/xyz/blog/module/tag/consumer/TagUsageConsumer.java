package dowob.xyz.blog.module.tag.consumer;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.tag.config.TagRabbitMqConfig;
import dowob.xyz.blog.infrastructure.event.ArticleTagEvent;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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
     * MQ consumer 共用執行包裝（失敗日誌、計數、冪等）
     */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理文章標籤事件，遞增對應標籤的使用計數
     *
     * <p>
     * 對事件中每個標籤 ID：查找標籤 → 遞增 usageCount → 儲存至資料庫 → 更新 Redis ZSet 分數。
     * 不存在的標籤 ID 將被靜默忽略。
     * 採用 AUTO ACK 模式：失敗日誌與計數統一交由 {@link ConsumerExecutor} 處理
     * （處理成功則正常返回、容器 ack；失敗則往外拋，由容器重試，耗盡後送 DLQ）。
     * </p>
     *
     * @param event       文章標籤事件
     */
    @RabbitListener(queues = TagRabbitMqConfig.QUEUE_TAG_ARTICLE_TAGGED)
    public void handleArticleTagged(ArticleTagEvent event) {
        consumerExecutor.executeIdempotent(CONSUMER_NAME, () -> "eventId=" + event.eventId(),
                event.eventId(), () -> {
                    /* Redis 無法隨交易 rollback：先完成全部 DB 寫入，才一次更新熱門分數，
                     * 避免第 N 個標籤的 DB 寫入失敗時，前 N-1 個標籤的分數已被加上而重送時重複加 */
                    List<UUID> savedTagIds = new ArrayList<>();
                    for (UUID tagId : event.tagIds()) {
                        Optional<Tag> tagOpt = tagRepository.findById(tagId);
                        if (tagOpt.isEmpty()) {
                            continue;
                        }
                        Tag tag = tagOpt.get();
                        tag.incrementUsage();
                        tagRepository.save(tag);
                        savedTagIds.add(tagId);
                    }
                    for (UUID tagId : savedTagIds) {
                        stringRedisTemplate.opsForZSet().incrementScore(RedisKeyConstant.TAG_HOT_KEY, tagId.toString(), 1.0);
                    }
                });
    }
}
