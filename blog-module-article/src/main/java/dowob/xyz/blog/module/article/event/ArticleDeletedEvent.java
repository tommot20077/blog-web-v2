package dowob.xyz.blog.module.article.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 文章刪除事件
 *
 * <p>
 * 文章被刪除後，透過 RabbitMQ 廣播此事件，
 * 供搜尋模組移除索引、推薦模組清除快取等下游消費。
 * </p>
 *
 * @param articleUuid 文章公開 UUID
 * @param deletedAt   刪除時間戳
 *
 * @author Yuan
 * @version 1.0
 */
public record ArticleDeletedEvent(UUID articleUuid, Instant deletedAt) {
}
