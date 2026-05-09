package dowob.xyz.blog.module.version.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 使用者偏好設定（user_preferences 表映射）。
 *
 * <p>通用 K-V 結構，key 以 dot separator 分組（如 version.auto.retain）。
 * 未來其他模組（bookmark / reading）也可塞同一張表。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("user_preferences")
public class UserPreference {
    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    @Column("pref_key")
    private String prefKey;

    @Column("pref_value")
    private String prefValue;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;
}
