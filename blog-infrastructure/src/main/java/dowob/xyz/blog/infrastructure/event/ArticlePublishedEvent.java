package dowob.xyz.blog.infrastructure.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 文章發布事件（跨模組共用定義）
 *
 * <p>
 * 文章成功發布後，透過 RabbitMQ 廣播此事件，
 * 供推薦模組清快取、搜尋模組建立索引等下游消費。
 * 此事件定義放置於 blog-infrastructure 以供各模組引用，
 * 避免模組間直接相依。
 * </p>
 *
 * @param articleUuid 文章公開 UUID
 * @param authorId    作者資料庫主鍵
 * @param title       文章標題
 * @param publishedAt 發布時間
 * @author Yuan
 * @version 1.0
 */
public record ArticlePublishedEvent(
        UUID articleUuid,
        Long authorId,
        String title,
        LocalDateTime publishedAt
) {
}
