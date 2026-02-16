package dowob.xyz.blog.module.tag.model.dto;

import lombok.Data;

import java.util.UUID;

/**
 * 標籤詳情回應 DTO
 *
 * <p>
 * 封裝標籤的完整資訊，供 API 回應使用。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class TagDetailResponse {

    /**
     * 標籤主鍵 UUID
     */
    private UUID id;

    /**
     * 標籤名稱
     */
    private String name;

    /**
     * URL 友善的 Slug
     */
    private String slug;

    /**
     * 標籤顏色（十六進位色碼或 CSS 顏色名稱）
     */
    private String color;

    /**
     * 標籤圖示
     */
    private String icon;

    /**
     * 標籤描述
     */
    private String description;

    /**
     * 文章使用次數
     */
    private int usageCount;
}
