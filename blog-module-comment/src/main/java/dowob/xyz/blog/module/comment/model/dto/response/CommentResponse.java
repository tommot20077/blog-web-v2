package dowob.xyz.blog.module.comment.model.dto.response;

import dowob.xyz.blog.common.api.dto.AuthorSummary;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 留言回應 DTO，包含巢狀回覆與軟刪除佔位資訊
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class CommentResponse {

    /**
     * 留言 UUID
     */
    private UUID uuid;

    /**
     * 父留言 UUID；null 表示頂層留言
     */
    private UUID parentUuid;

    /**
     * 原始 Markdown 內容
     */
    private String content;

    /**
     * 經過 sanitize 的 HTML 內容
     */
    private String contentHtml;

    /**
     * 作者摘要；deleted=true 時為 null
     */
    private AuthorSummary author;

    /**
     * 按讚數
     */
    private Integer likeCount;

    /**
     * 當前使用者是否已按讚
     */
    private Boolean liked;

    /**
     * 留言建立時間
     */
    private LocalDateTime createdAt;

    /**
     * 最後編輯時間；null 表示未曾編輯
     */
    private LocalDateTime editedAt;

    /**
     * 是否為軟刪除佔位（true = 已刪除）
     */
    private Boolean deleted;

    /**
     * 刪除者角色："AUTHOR" | "ADMIN" | null
     */
    private String deletedByRole;

    /**
     * 未刪除回覆的總數（僅頂層留言有意義；回覆與新建立的留言為 0）。
     * 前端以 {@code replyCount > replies.size()} 判斷是否顯示「載入更多」。
     */
    private Integer replyCount = 0;

    /**
     * 子回覆列表；僅頂層留言帶此欄位，列表 API 只帶前 3 則，其餘請用回覆分頁端點
     */
    private List<CommentResponse> replies;
}
