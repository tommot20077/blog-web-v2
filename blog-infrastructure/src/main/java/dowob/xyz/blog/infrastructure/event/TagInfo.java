package dowob.xyz.blog.infrastructure.event;

import java.util.UUID;

/**
 * 標籤基本資訊
 *
 * <p>
 * 用於 {@link ArticlePublishedEvent} 傳遞標籤資訊，
 * 供下游消費者（如搜尋模組）建立索引。
 * </p>
 *
 * @param id   標籤公開 UUID
 * @param name 標籤名稱
 * @param slug 標籤 URL slug
 *
 * @author Yuan
 * @version 1.0
 */
public record TagInfo(UUID id, String name, String slug) {
}
