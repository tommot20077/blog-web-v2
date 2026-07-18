package dowob.xyz.blog.module.article.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 文章瀏覽事件
 *
 * <p>當已發布文章被瀏覽時（通過 Redis 防刷後），發送至 RabbitMQ。</p>
 *
 * @author Yuan
 * @version 1.0
 * @param eventId     事件 dedup key（producer 每次 publish 時 random gen；舊訊息為 null）
 * @param articleUuid 文章公開 UUID
 * @param viewedAt    瀏覽時間戳
 */
public record ArticleViewedEvent(UUID eventId, UUID articleUuid, Instant viewedAt) {}
