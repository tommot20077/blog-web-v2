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

    /** 所屬文章的公開 UUID（V28 起取代舊的 Long 文章欄位；無 FK，文章刪除由 {@code VersionArticleDeletedConsumer} 與每日校正清除） */
    @Column("article_uuid")
    private UUID articleUuid;

    /** 快照當時文章作者的公開 UUID（V28 起取代舊的 Long 作者欄位；無 FK） */
    @Column("author_uuid")
    private UUID authorUuid;

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
