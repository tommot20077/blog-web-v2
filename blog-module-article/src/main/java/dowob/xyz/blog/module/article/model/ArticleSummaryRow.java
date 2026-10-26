package dowob.xyz.blog.module.article.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;
/**
 * 文章摘要查詢結果原始資料
 *
 * <p>
 * 供 ArticleRecommendMapper 使用的中間結果物件，
 * 包含文章基本欄位及作者 UUID（M6-a 起不再關聯 users 表）。
 * 由 ArticleFacadeImpl 以整頁一次的 UserFacade 批次查詢補上作者暱稱，進一步組裝為 ArticleSummaryInfo。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class ArticleSummaryRow {

    /**
     * 文章資料庫主鍵（用於批次查詢標籤）
     */
    private Long id;

    /**
     * 文章公開 UUID
     */
    private String uuid;

    /**
     * 文章標題
     */
    private String title;

    /**
     * 文章 URL slug
     */
    private String slug;

    /**
     * 文章摘要
     */
    private String summary;

    /**
     * 作者公開 UUID（取自 articles.author_uuid；暱稱由 facade 以 UserFacade 整頁批次解析，不再關聯 users 表）
     */
    private UUID authorUuid;

    /**
     * 瀏覽次數
     */
    private long viewCount;

    /**
     * 按讚次數
     */
    private long likeCount;

    /**
     * 發布時間
     */
    private LocalDateTime publishedAt;
}
