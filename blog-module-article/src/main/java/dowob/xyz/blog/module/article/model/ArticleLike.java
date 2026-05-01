package dowob.xyz.blog.module.article.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 文章按讚紀錄（article_likes 表映射）。
 *
 * <p>採用 surrogate id + UNIQUE(user_id, article_id) 確保使用者對單一文章只能按一次讚。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("article_likes")
public class ArticleLike {
    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    @Column("article_id")
    private Long articleId;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;
}
