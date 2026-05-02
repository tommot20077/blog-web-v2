package dowob.xyz.blog.module.version.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文章版本快照（article_versions 表映射）。
 *
 * <p>3 種 type：
 * <ul>
 *   <li>AUTO — 自動快照，滾動 N 份保留</li>
 *   <li>MANUAL — 用戶手動快照，永久保留</li>
 *   <li>PUBLISHED — publish 時系統凍結，永久保留</li>
 * </ul>
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("article_versions")
public class ArticleVersion {
    @Id
    private Long id;

    private UUID uuid;

    @Column("article_id")
    private Long articleId;

    @Column("author_id")
    private Long authorId;

    private String type;

    private String title;

    private String slug;

    private String content;

    private String summary;

    @Column("category_id")
    private Long categoryId;

    @Column("cover_image_url")
    private String coverImageUrl;

    private String status;

    /** PG UUID[] — Spring Data JDBC 對 array 對應為 List<UUID>（PG JDBC driver 原生支援）*/
    private List<UUID> tags;

    private String note;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;
}
