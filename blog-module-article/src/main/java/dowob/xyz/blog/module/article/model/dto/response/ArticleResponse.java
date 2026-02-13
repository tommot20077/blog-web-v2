package dowob.xyz.blog.module.article.model.dto.response;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
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
}
