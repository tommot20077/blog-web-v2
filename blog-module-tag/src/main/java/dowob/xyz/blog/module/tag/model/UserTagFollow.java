package dowob.xyz.blog.module.tag.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * 使用者標籤追蹤實體
 *
 * <p>
 * 對應資料庫 {@code user_tag_follows} 中間表，
 * 記錄使用者訂閱/追蹤標籤的關聯關係。
 * 以 user_id 作為主鍵供 Spring Data JDBC 識別，
 * 實際操作皆透過 {@link dowob.xyz.blog.module.tag.repository.UserTagFollowRepository} 的 @Query 方法執行。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Table("user_tag_follows")
public class UserTagFollow {

    /**
     * 使用者 ID（作為 Spring Data JDBC 識別用主鍵）
     */
    @Id
    @Column("user_id")
    private UUID userId;

    /**
     * 標籤 ID
     */
    @Column("tag_id")
    private UUID tagId;
}
