package dowob.xyz.blog.module.article.model.dto.request;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新文章請求 DTO
 *
 * <p>
 * 所有欄位均為可選，Service 層只更新非 null 欄位。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class UpdateArticleRequest {

    /**
     * 文章標題（可選）
     */
    @Size(max = 200, message = "標題長度不得超過 200 字")
    private String title;

    /**
     * 文章 Markdown 內容（可選）
     */
    private String content;

    /**
     * 文章摘要（可選）
     */
    @Size(max = 500, message = "摘要長度不得超過 500 字")
    private String summary;

    /**
     * 文章狀態（可選）
     */
    private ArticleStatus status;
}
