package dowob.xyz.blog.module.tag.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 標籤領域實體
 *
 * <p>
 * 對應資料庫 {@code tags} 表，封裝標籤的核心業務邏輯，
 * 包含使用計數的遞增與遞減操作。
 * </p>
 *
 * <p>
 * 實作 {@link Persistable} 以支援手動賦值 UUID 主鍵——
 * Spring Data JDBC 預設以 ID 是否為 null 判斷新舊實體，
 * 手動設定 UUID 後需明確標記為新實體才能正確執行 INSERT。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Table("tags")
public class Tag implements Persistable<UUID> {

    /**
     * 資料庫主鍵（UUID，手動賦值）
     */
    @Id
    private UUID id;

    /**
     * 標記此實體是否為新建（控制 save() 使用 INSERT 或 UPDATE）
     */
    @Transient
    private boolean isNew = false;

    /**
     * 標籤名稱（唯一）
     */
    private String name;

    /**
     * 標籤 Slug（URL 友善格式，唯一）
     */
    private String slug;

    /**
     * 標籤顯示顏色（十六進位色碼或 CSS 顏色名稱，可為 null）
     */
    private String color;

    /**
     * 標籤圖示（圖示類名或 URL，可為 null）
     */
    private String icon;

    /**
     * 標籤描述（可為 null）
     */
    private String description;

    /**
     * 父標籤 ID（可為 null，表示頂層標籤）
     */
    private UUID parentId;

    /**
     * 已發布文章數（不可為負數）。
     *
     * <p>由 {@code TagUsageRecalculator} 依事件／每日校正重算寫入（DATA-01），對外即前台的 {@code articleCount}；
     * 不含草稿等未發布文章，所以<b>不可</b>用來判斷標籤是否仍被使用（見 {@code TagService#adminDeleteTag}）。</p>
     */
    private int usageCount = 0;

    /**
     * 建立時間
     */
    private LocalDateTime createdAt;

    /**
     * 增加使用計數
     *
     * <p>注意：正式流程已改為重算（{@code TagUsageRecalculator}），不再呼叫本方法；
     * 它只改記憶體中的欄位，不會寫回資料庫。</p>
     */
    public void incrementUsage() {
        this.usageCount++;
    }

    /**
     * 減少使用計數
     *
     * <p>注意：正式流程已改為重算（{@code TagUsageRecalculator}），不再呼叫本方法。</p>
     */
    public void decrementUsage() {
        this.usageCount = Math.max(0, this.usageCount - 1);
    }
}
