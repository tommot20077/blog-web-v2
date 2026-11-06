package dowob.xyz.blog.module.tag.model.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新標籤請求 DTO
 *
 * <p>
 * 管理員更新標籤屬性時使用，所有欄位均為可選。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class UpdateTagRequest {

    /**
     * 標籤顏色（可為 null，表示不更新）；限 {@code #RRGGBB}，前端會把它綁進 {@code :style}，
     * 不限格式等於 CSS 注入面（SEC-24）
     */
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "color 須為 #RRGGBB 十六進位色碼")
    private String color;

    /**
     * 標籤圖示（可為 null，表示不更新）；上限對齊 {@code tags.icon VARCHAR(100)}
     */
    @Size(max = 100, message = "icon 長度不得超過 100 字")
    private String icon;

    /**
     * 標籤描述（可為 null，表示不更新）；{@code tags.description} 為 TEXT 無資料庫上限，
     * 沿用分類描述的 200 字規則
     */
    @Size(max = 200, message = "description 長度不得超過 200 字")
    private String description;
}
