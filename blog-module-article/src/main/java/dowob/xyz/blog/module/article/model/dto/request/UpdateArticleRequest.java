package dowob.xyz.blog.module.article.model.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.module.article.model.ArticleContentLimits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

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
    @Size(max = 120, message = "標題長度須為 1-120 字元")
    private String title;

    /**
     * 文章 Markdown 內容（可選）
     */
    @Size(max = ArticleContentLimits.MAX_CONTENT_LENGTH, message = "內容長度不得超過 200000 字元")
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

    /**
     * 文章分類 UUID 列表（可選，null 表示不更新，空列表表示清除所有分類）
     */
    private List<UUID> categoryIds;

    /**
     * 封面圖片 URL（可選，null 表示不更新）
     */
    private String coverImageUrl;

    /**
     * 標籤名稱列表（可選，null 表示不更新，空列表表示清除所有標籤）
     */
    private List<String> tagNames;

    /**
     * 編輯器載入文章時取得的樂觀鎖版本號（可選）
     *
     * <p>有帶且與資料庫目前版本不一致時，代表文章在載入後已被其他地方修改，回 409
     * {@code ARTICLE_CONCURRENT_UPDATE}，內容不寫入。未帶時維持舊行為（僅防「讀取到存檔」之間的極短視窗），
     * 以相容前後端分開部署。只約束內容更新這條路徑。</p>
     */
    @Schema(description = "載入文章時取得的 version；與目前不一致回 409（A0208）。省略則不做版本比對（相容舊前端）",
            nullable = true, example = "3")
    @Min(value = 0, message = "version 不得為負數")
    private Long version;
}
