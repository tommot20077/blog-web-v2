package dowob.xyz.blog.module.comment.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Comment 加上 author 資訊的查詢結果（用於 listComments 的 JOIN users）。
 *
 * <p>對應 CommentMapper 的 SELECT 結果欄位（snake_case → camelCase 由 MyBatis underscore-to-camel 自動映射）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class CommentWithAuthor {
    // Comment fields
    private Long id;
    private UUID uuid;
    private Long articleId;
    private Long parentId;
    private UUID parentUuid;     // 由 self-join 取得（reply 帶 parent uuid 用於前端表達）
    private Long userId;
    private String content;
    private String contentHtml;
    private Integer likeCount;
    private LocalDateTime editedAt;
    private LocalDateTime deletedAt;
    private String deletedByRole;
    private LocalDateTime createdAt;

    // Author fields (from JOIN users)
    private UUID authorUuid;
    private String authorNickname;
    private String authorAvatarUrl;
}
