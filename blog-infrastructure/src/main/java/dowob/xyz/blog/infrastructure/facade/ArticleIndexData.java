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
 * @param authorNickname 作者暱稱
 * @param publishedAt    發布時間
 * @param viewCount      瀏覽次數
 * @param likeCount      按讚次數
 * @param tags           標籤列表
 * @param authorUuid     作者公開 UUID（直接取自 {@code articles.author_uuid}，供搜尋存入 author.uuid）。
 *                       M6-a 起作者的 Long 主鍵與登入帳號（username）已移除，不再外流。
 *                       例外：舊 10 參數建構子（僅供過渡期既有呼叫端 / 測試 mock，Z1 移除）產生者恆為 null
 *
 * @author Yuan
 * @version 2.0（M6-a：移除 Long 作者主鍵與作者帳號名稱）
 */
public record ArticleIndexData(
        UUID articleUuid,
        String title,
        String slug,
        String summary,
        String contentText,
        String authorNickname,
        LocalDateTime publishedAt,
        long viewCount,
        long likeCount,
        List<TagData> tags,
        UUID authorUuid) {

    /**
     * 舊 10 參數建構子，<b>僅供過渡期既有呼叫端使用，Z1 移除</b>。
     *
     * <p>委派到 canonical 建構子，{@code authorUuid} 為 null。</p>
     *
     * @param articleUuid    文章對外公開 UUID
     * @param title          文章標題
     * @param slug           URL slug
     * @param summary        文章摘要
     * @param contentText    Markdown 去格式後的純文字
     * @param authorNickname 作者暱稱
     * @param publishedAt    發布時間
     * @param viewCount      瀏覽次數
     * @param likeCount      按讚次數
     * @param tags           標籤列表
     */
    public ArticleIndexData(UUID articleUuid, String title, String slug, String summary, String contentText,
                            String authorNickname,
                            LocalDateTime publishedAt, long viewCount, long likeCount, List<TagData> tags) {
        this(articleUuid, title, slug, summary, contentText, authorNickname,
                publishedAt, viewCount, likeCount, tags, null);
    }

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
