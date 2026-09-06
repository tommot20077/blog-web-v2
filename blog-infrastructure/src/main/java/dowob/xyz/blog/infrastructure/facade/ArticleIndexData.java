package dowob.xyz.blog.infrastructure.facade;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文章索引資料傳輸物件
 *
 * <p>
 * 供 {@link ArticleFacade} 回傳，攜帶 Elasticsearch 索引所需的完整文章資料。
 * 使用者為搜尋模組，透過 Facade 取得全量資料後進行批次重建索引。
 * </p>
 *
 * @param articleUuid    文章對外公開 UUID
 * @param title          文章標題
 * @param slug           URL slug
 * @param summary        文章摘要
 * @param contentText    Markdown 去格式後的純文字（供全文搜尋）
 * @param authorId       作者資料庫主鍵
 * @param authorUsername 作者帳號名稱
 * @param authorNickname 作者暱稱
 * @param publishedAt    發布時間
 * @param viewCount      瀏覽次數
 * @param likeCount      按讚次數
 * @param tags           標籤列表
 *
 * @author Yuan
 * @version 1.0
 */
public record ArticleIndexData(
        UUID articleUuid,
        String title,
        String slug,
        String summary,
        String contentText,
        Long authorId,
        String authorUsername,
        String authorNickname,
        LocalDateTime publishedAt,
        long viewCount,
        long likeCount,
        List<TagData> tags) {

    /**
     * 文章標籤簡要資訊
     *
     * @param id   標籤公開 UUID
     * @param name 標籤名稱
     * @param slug 標籤 URL slug
     */
    public record TagData(UUID id, String name, String slug) {
    }
}
