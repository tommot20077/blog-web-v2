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
 * 文章收藏紀錄（user_bookmarks 表映射）。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("user_bookmarks")
public class UserBookmark {
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
