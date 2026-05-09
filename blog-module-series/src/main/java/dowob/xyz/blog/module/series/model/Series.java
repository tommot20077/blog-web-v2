package dowob.xyz.blog.module.series.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 系列文 (Series) 實體（series 表映射）。
 *
 * <p>一個 series 對 N 篇 articles（articles.series_id FK）。articles 內按
 * series_position 排序。article_count 為反正規化欄位由 service 維護。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("series")
public class Series {
    @Id
    private Long id;

    private UUID uuid;

    private String title;

    private String slug;

    private String description;

    @Column("cover_image_url")
    private String coverImageUrl;

    @Column("author_id")
    private Long authorId;

    @Column("article_count")
    private Integer articleCount;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;
}
