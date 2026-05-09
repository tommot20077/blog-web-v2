package dowob.xyz.blog.module.reading.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 閱讀進度（user_reading_progress 表映射）。
 *
 * <p>Redis 主要儲存，DB 為 5 分鐘 flush 後的備份。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("user_reading_progress")
public class UserReadingProgress {
    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    @Column("article_id")
    private Long articleId;

    private BigDecimal progress;

    @Column("last_heading_anchor")
    private String lastHeadingAnchor;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;
}
