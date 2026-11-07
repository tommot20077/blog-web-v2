package dowob.xyz.blog.module.comment.model.dto.response;

import dowob.xyz.blog.common.api.response.PageResult;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文章留言列表回應 DTO，包含頂層留言分頁與總留言數
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleCommentListResponse {

    /**
     * 頂層留言分頁結果（每筆附帶 replies）
     */
    private PageResult<CommentResponse> topLevels;

    /**
     * 文章下的「未刪除留言數」（頂層加回覆合計；<b>不含任何已刪除留言，包括頂層墓碑佔位</b>）。
     * 與文章的 {@code commentCount} 同一口徑。注意它可能小於列表實際顯示的列數（墓碑仍會出現在列表中）。
     */
    @Schema(description = "文章的未刪除留言數（頂層加回覆合計，不含已刪除留言與頂層墓碑佔位）；與文章 commentCount 同一口徑")
    private Integer totalCommentCount;
}
