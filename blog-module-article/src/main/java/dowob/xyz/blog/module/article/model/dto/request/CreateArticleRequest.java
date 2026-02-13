package dowob.xyz.blog.module.article.model.dto.request;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 建立文章請求 DTO
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class CreateArticleRequest {

    /**
     * 文章標題（必填）
     */
    @NotBlank(message = "標題不得為空")
    @Size(max = 200, message = "標題長度不得超過 200 字")
    private String title;

    /**
     * 文章 Markdown 內容（必填）
     */
    @NotBlank(message = "內容不得為空")
    private String content;

    /**
     * 文章摘要（可選）
     */
    @Size(max = 500, message = "摘要長度不得超過 500 字")
    private String summary;

    /**
     * 初始狀態（預設為 DRAFT）
     */
    private ArticleStatus status = ArticleStatus.DRAFT;
}
