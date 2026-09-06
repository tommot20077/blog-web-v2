package dowob.xyz.blog.module.article.model.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新分類請求 DTO（Admin Only）
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class UpdateCategoryRequest {

    @Size(max = 50, message = "分類名稱長度不得超過 50 字")
    private String name;

    @Size(max = 60, message = "slug 長度不得超過 60 字")
    private String slug;

    @Size(max = 200, message = "描述長度不得超過 200 字")
    private String description;

    private Integer sortOrder;
}
