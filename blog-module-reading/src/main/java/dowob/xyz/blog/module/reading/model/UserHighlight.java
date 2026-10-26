package dowob.xyz.blog.module.reading.model;

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
 * 文字劃線 + 註記（user_highlights 表映射）。
 *
 * <p>定位策略：snippet + prefix/suffix anchor — 文章編輯後 self-healing。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("user_highlights")
public class UserHighlight {
    @Id
    private Long id;

    private UUID uuid;

    /** 劃線者的公開 UUID（跨模組識別；V27 起無 FK） */
    @Column("user_uuid")
    private UUID userUuid;

    /** 文章公開 UUID（跨模組識別；V27 起無 FK，文章刪除由事件＋校正排程連動刪除） */
    @Column("article_uuid")
    private UUID articleUuid;

    private String snippet;
    private String prefix;
    private String suffix;
    private String color;
    private String note;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;
}
