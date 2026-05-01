package dowob.xyz.blog.module.comment.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("comment_likes")
public class CommentLike {
    @Id
    private Long id;
    private Long userId;
    private Long commentId;
    private LocalDateTime createdAt;
}
