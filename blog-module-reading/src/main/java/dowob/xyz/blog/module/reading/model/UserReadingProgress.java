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
import java.util.UUID;

/**
 * 閱讀進度（user_reading_progress 表映射）。
 *
 * <p>Redis 主要儲存，DB 為 5 分鐘 flush 後的備份。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("user_reading_progress")
public class UserReadingProgress {
    @Id
    private Long id;

    /** 讀者的公開 UUID（跨模組識別；V27 起無 FK） */
    @Column("user_uuid")
    private UUID userUuid;

    /** 文章公開 UUID（跨模組識別；V27 起無 FK，文章刪除由事件＋校正排程連動刪除） */
    @Column("article_uuid")
    private UUID articleUuid;

    private BigDecimal progress;

    @Column("last_heading_anchor")
    private String lastHeadingAnchor;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;
}
