package dowob.xyz.blog.module.tag.event;

import java.util.List;
import java.util.UUID;

/**
 * 文章標籤事件（從文章模組消費）
 *
 * <p>
 * 當文章被標記上標籤時，透過 RabbitMQ 廣播此事件，
 * 標籤模組消費後更新各標籤的使用計數。
 * </p>
 *
 * @param articleId 文章資料庫主鍵
 * @param tagIds    被標記的標籤 ID 列表
 *
 * @author Yuan
 * @version 1.0
 */
public record ArticleTagEvent(UUID articleId, List<UUID> tagIds) {
}
