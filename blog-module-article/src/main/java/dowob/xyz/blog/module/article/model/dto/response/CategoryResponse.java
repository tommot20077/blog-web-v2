package dowob.xyz.blog.module.article.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * 分類回應 DTO
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Builder
public class CategoryResponse {

    /** 分類公開 UUID */
    private UUID uuid;

    /** 分類名稱 */
    private String name;

    /** URL slug */
    private String slug;

    /** 分類描述 */
    private String description;

    /** 排序權重 */
    private int sortOrder;
}
