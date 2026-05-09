package dowob.xyz.blog.module.reading.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 文章按讚紀錄（user_article_likes 表映射）。
 *
 * <p>採用 surrogate id + UNIQUE(user_id, article_id) 確保使用者對單一文章只能按一次讚。</p>
 *
 * <p>V15 migration 將表名從 article_likes RENAME 為 user_article_likes，
 * 並從 article 模組搬到 reading 模組統一管理。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("user_article_likes")
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
