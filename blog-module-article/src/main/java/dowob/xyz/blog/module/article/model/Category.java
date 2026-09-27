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

    /**
     * slug 的合法格式：小寫英數字，以單一連字號分隔，不得以連字號開頭或結尾。
     *
     * <p>逗號是文章列表多值參數 {@code categorySlug} 的分隔符、大寫會被前端轉小寫後錯過，
     * 故兩者都不能出現在 slug 中（Yuan 2026-09-27 決定）。API 層由建立／更新請求的
     * {@code @Pattern} 驗證；資料庫層由 V23 的 {@code ck_categories_slug_format} CHECK 約束保證——
     * <b>兩處必須是同一條 regex</b>，修改其一須同步另一處（並新增 migration，不得改 V23）。</p>
     */
    public static final String SLUG_PATTERN = "^[a-z0-9]+(-[a-z0-9]+)*$";

    /** slug 格式不符時回給 client 的訊息 */
    public static final String SLUG_PATTERN_MESSAGE = "slug 只能包含小寫英數字與單一連字號，且不得以連字號開頭或結尾";

    /** 資料庫主鍵（內部使用） */
    @Id
    private Long id;

    /** 對外公開的 UUID 識別碼 */
    private UUID uuid;

    /** 分類名稱（唯一） */
    private String name;

    /** URL slug（唯一，用於 API 篩選；格式見 {@link #SLUG_PATTERN}） */
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
