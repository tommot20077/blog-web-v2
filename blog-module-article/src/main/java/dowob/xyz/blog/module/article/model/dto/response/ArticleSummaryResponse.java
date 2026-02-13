package dowob.xyz.blog.module.article.model.dto.response;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 文章摘要回應 DTO
 *
 * <p>
 * 用於文章列表，不含完整內容以降低資料傳輸量。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Builder
public class ArticleSummaryResponse {

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
     * 封面圖片 URL
     */
    private String coverImageUrl;

    /**
     * 作者 UUID
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
}
