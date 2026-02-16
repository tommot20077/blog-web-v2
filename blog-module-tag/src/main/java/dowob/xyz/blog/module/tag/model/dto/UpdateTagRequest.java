package dowob.xyz.blog.module.tag.model.dto;

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
     * 標籤顏色（可為 null，表示不更新）
     */
    private String color;

    /**
     * 標籤圖示（可為 null，表示不更新）
     */
    private String icon;

    /**
     * 標籤描述（可為 null，表示不更新）
     */
    private String description;
}
