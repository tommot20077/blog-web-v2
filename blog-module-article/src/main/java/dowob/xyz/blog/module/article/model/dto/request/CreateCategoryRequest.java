package dowob.xyz.blog.module.article.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 建立分類請求 DTO（Admin Only）
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class CreateCategoryRequest {

    @NotBlank(message = "分類名稱不得為空")
    @Size(max = 50, message = "分類名稱長度不得超過 50 字")
    private String name;

    @NotBlank(message = "slug 不得為空")
    @Size(max = 60, message = "slug 長度不得超過 60 字")
    private String slug;

    @Size(max = 200, message = "描述長度不得超過 200 字")
    private String description;

    private int sortOrder = 0;
}
