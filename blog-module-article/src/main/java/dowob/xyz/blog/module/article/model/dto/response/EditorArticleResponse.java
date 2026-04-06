package dowob.xyz.blog.module.article.model.dto.response;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Editor 專用文章回應 DTO
 *
 * <p>
 * 僅包含編輯器所需欄位，不含 slug、contentHtml、viewCount、likeCount 等閱讀端欄位。
 * 用於 POST /api/v1/articles、PUT /api/v1/articles/{uuid}、GET /api/v1/articles/{uuid}/edit。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Builder
public class EditorArticleResponse {

    /**
     * 文章公開 UUID
     */
    private UUID uuid;

    /**
     * 文章標題
     */
    private String title;

    /**
     * 文章摘要
     */
    private String summary;

    /**
     * 文章 Markdown 原始內容
     */
    private String content;

    /**
     * 封面圖片 URL
     */
    private String coverImageUrl;

    /**
     * 文章狀態
     */
    private ArticleStatus status;

    /**
     * 文章分類列表
     */
    private List<CategoryResponse> categories;

    /**
     * 文章標籤列表
     */
    private List<TagSummaryResponse> tags;

    /**
     * 駁回原因
     */
    private String rejectReason;

    /**
     * 建立時間
     */
    private LocalDateTime createdAt;

    /**
     * 最後更新時間
     */
    private LocalDateTime updatedAt;
}
