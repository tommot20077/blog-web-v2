package dowob.xyz.blog.module.tag.consumer;

import dowob.xyz.blog.module.tag.config.TagRabbitMqConfig;
import dowob.xyz.blog.module.tag.event.ArticleTagEvent;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

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
@Component
@RequiredArgsConstructor
public class TagUsageConsumer {

    /**
     * 標籤資料存取物件
     */
    private final TagRepository tagRepository;

    /**
     * Redis 操作模板（String 類型）
     */
    private final RedisTemplate<String, String> stringRedisTemplate;

    /**
     * 熱門標籤 Redis ZSet 鍵名
     */
    private static final String HOT_TAGS_KEY = "tag:hot";

    /**
     * 處理文章標籤事件，遞增對應標籤的使用計數
     *
     * <p>
     * 對事件中每個標籤 ID：查找標籤 → 遞增 usageCount → 儲存至資料庫 → 更新 Redis ZSet 分數。
     * 不存在的標籤 ID 將被靜默忽略。
     * </p>
     *
     * @param event 文章標籤事件
     */
    @RabbitListener(queues = TagRabbitMqConfig.QUEUE_TAG_ARTICLE_TAGGED)
    public void handleArticleTagged(ArticleTagEvent event) {
        for (UUID tagId : event.tagIds()) {
            Optional<Tag> tagOpt = tagRepository.findById(tagId);
            if (tagOpt.isEmpty()) {
                continue;
            }
            Tag tag = tagOpt.get();
            tag.incrementUsage();
            tagRepository.save(tag);
            stringRedisTemplate.opsForZSet().incrementScore(HOT_TAGS_KEY, tagId.toString(), 1.0);
        }
    }
}
