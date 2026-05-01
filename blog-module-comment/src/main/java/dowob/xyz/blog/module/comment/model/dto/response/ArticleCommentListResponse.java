package dowob.xyz.blog.module.comment.model.dto.response;

import dowob.xyz.blog.common.api.response.PageResult;
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
     * 文章下的留言總數（含軟刪除佔位）
     */
    private Integer totalCommentCount;
}
