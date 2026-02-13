package dowob.xyz.blog.module.article.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 文章發布事件
 *
 * <p>
 * 文章成功發布後，透過 RabbitMQ 廣播此事件，
 * 供搜尋模組索引、推薦模組更新等下游消費。
 * </p>
 *
 * @param articleUuid 文章公開 UUID
 * @param authorId    作者資料庫主鍵
 * @param title       文章標題
 * @param publishedAt 發布時間
 *
 * @author Yuan
 * @version 1.0
 */
public record ArticlePublishedEvent(
        UUID articleUuid,
        Long authorId,
        String title,
        LocalDateTime publishedAt) {
}
