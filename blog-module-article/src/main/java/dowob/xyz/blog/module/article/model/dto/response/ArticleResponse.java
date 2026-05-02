package dowob.xyz.blog.module.article.model.dto.response;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesNavigation;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文章完整回應 DTO
 *
 * <p>
 * 用於取得單篇文章，包含完整內容。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Builder
public class ArticleResponse {

    /**
     * 文章公開 UUID
     */
    private UUID uuid;

    /**
     * 文章標題
     */
    private String title;

    /**
     * 文章 Markdown 完整內容
     */
    private String content;

    /**
     * 文章 HTML 預渲染內容
     */
    private String contentHtml;

    /**
     * 文章摘要
     */
    private String summary;

    /**
     * 封面圖片 URL
     */
    private String coverImageUrl;

    /**
     * 作者 UUID（對外公開，不暴露內部 Long ID）
     */
    private UUID authorUuid;

    /**
     * 作者暱稱
     */
    private String authorNickname;

    /**
     * 文章狀態
     */
    private ArticleStatus status;

    /**
     * 瀏覽次數
     */
    private long viewCount;

    /**
     * 建立時間
     */
    private LocalDateTime createdAt;

    /**
     * 最後更新時間
     */
    private LocalDateTime updatedAt;

    /**
     * 文章分類列表
     */
    private List<CategoryResponse> categories;

    /**
     * URL slug（SEO 友善網址）
     */
    private String slug;

    /**
     * 按讚次數
     */
    private long likeCount;

    /**
     * 留言數量
     */
    private int commentCount;

    /**
     * 發布時間
     */
    private LocalDateTime publishedAt;

    /**
     * 文章標籤列表
     */
    private List<TagSummaryResponse> tags;

    /**
     * 駁回原因（Phase 3 實作後才有值）
     */
    private String rejectReason;

    /**
     * 當前登入使用者是否已對此文章按讚（未登入時為 false）
     */
    private Boolean liked;

    /**
     * 當前登入使用者是否已收藏此文章（未登入時為 false）
     */
    private Boolean bookmarked;

    /**
     * 當前登入使用者對此文章的最後閱讀進度（未登入或無紀錄時為 null）
     */
    private BigDecimal lastReadProgress;

    /**
     * 文章在系列中的導覽資訊（prev/next），不在 series 中時為 null
     */
    private SeriesNavigation seriesNav;
}
