package dowob.xyz.blog.infrastructure.event;

import java.util.List;
import java.util.UUID;

/**
 * 文章標籤事件（跨模組共用定義）
 *
 * <p>
 * 當文章被標記上標籤時，透過 RabbitMQ 廣播此事件，
 * 標籤模組消費後更新各標籤的使用計數。
 * 此事件定義放置於 blog-infrastructure 以供各模組引用，
 * 避免模組間直接相依。
 * </p>
 *
 * @param eventId   事件 dedup key（producer 每次 publish 時 random gen；舊訊息為 null）
 * @param articleId 文章公開 UUID
 * @param tagIds    被標記的標籤 UUID 列表
 *
 * @author Yuan
 * @version 1.0
 */
public record ArticleTagEvent(UUID eventId, UUID articleId, List<UUID> tagIds) {
}
