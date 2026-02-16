package dowob.xyz.blog.module.article.event;

/**
 * 標籤基本資訊
 *
 * <p>
 * 用於 {@link ArticlePublishedEvent} 傳遞標籤資訊，
 * 供下游消費者（如搜尋模組）建立索引。
 * </p>
 *
 * @param id   標籤資料庫主鍵
 * @param name 標籤名稱
 * @param slug 標籤 URL slug
 *
 * @author Yuan
 * @version 1.0
 */
public record TagInfo(Long id, String name, String slug) {
}
