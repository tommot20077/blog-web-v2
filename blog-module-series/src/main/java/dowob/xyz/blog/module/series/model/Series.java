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
 * <p>一個 series 對 N 篇 articles（articles.series_uuid，V32 起無 FK；系列刪除時由 article 訂閱 SeriesDeletedEvent 清空歸屬）。articles 內按
 * series_position 排序。article_count 為反正規化欄位由 service 維護。</p>
 *
 * <p>M4-b 起作者以 {@code author_uuid} 識別（V29），表上沒有指向其他模組的 Long 欄位與 FK。</p>
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

    /** 作者的公開 UUID（不建 FK：跨模組識別一律 UUID，設計 §0；使用者只軟刪除） */
    @Column("author_uuid")
    private UUID authorUuid;

    @Column("article_count")
    private Integer articleCount;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;
}
