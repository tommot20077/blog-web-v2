package dowob.xyz.blog.module.article.model;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 分類實體
 *
 * <p>
 * 對應資料庫 categories 表，封裝廣義主題分類資料。
 * 分類由 Admin 統一管理，作者不可自建。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Table("categories")
public class Category {

    /** 資料庫主鍵（內部使用） */
    @Id
    private Long id;

    /** 對外公開的 UUID 識別碼 */
    private UUID uuid;

    /** 分類名稱（唯一） */
    private String name;

    /** URL slug（唯一，用於 API 篩選） */
    private String slug;

    /** 分類描述（可選） */
    private String description;

    /** 排序權重（越小越前） */
    @Column("sort_order")
    private int sortOrder;

    /** 建立時間 */
    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;
}
