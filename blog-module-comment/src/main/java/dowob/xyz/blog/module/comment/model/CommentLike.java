package dowob.xyz.blog.module.comment.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 留言按讚紀錄（comment_likes 表映射）
 *
 * <p>採用 surrogate id + UNIQUE(user_id, comment_id) 確保使用者對單一留言只能按一次讚。</p>
 *
 * @author Yuan
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("comment_likes")
public class CommentLike {
    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    @Column("comment_id")
    private Long commentId;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;
}
