package dowob.xyz.blog.module.comment.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 留言實體（comments 表映射）
 *
 * <p>巢狀深度限制 2 層：parent_id NULL 為 top-level，否則為 reply 且 reply 不可再被 reply。
 * 軟刪除採 deleted_at + deleted_by_role 標記，row 永遠不刪除。</p>
 *
 * @author Yuan
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("comments")
public class Comment {
    @Id
    private Long id;
    private UUID uuid;
    private Long articleId;
    private Long parentId;
    private Long userId;
    private String content;
    private String contentHtml;
    private Integer likeCount;
    private LocalDateTime editedAt;
    private LocalDateTime deletedAt;
    private String deletedByRole;     // "AUTHOR" | "ADMIN" | null
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
