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
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("user_highlights")
public class UserHighlight {
    @Id
    private Long id;

    private UUID uuid;

    @Column("user_id")
    private Long userId;

    @Column("article_id")
    private Long articleId;

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
