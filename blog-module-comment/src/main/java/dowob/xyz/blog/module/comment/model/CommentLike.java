package dowob.xyz.blog.module.comment.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 留言按讚紀錄（comment_likes 表映射）
 *
 * <p>採用 surrogate id + UNIQUE(user_uuid, comment_id) 確保使用者對單一留言只能按一次讚。</p>
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

    /** 按讚者的公開 UUID（跨模組識別；V25 起取代舊的 Long 使用者欄位，沒有 FK） */
    @Column("user_uuid")
    private UUID userUuid;

    @Column("comment_id")
    private Long commentId;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;
}
