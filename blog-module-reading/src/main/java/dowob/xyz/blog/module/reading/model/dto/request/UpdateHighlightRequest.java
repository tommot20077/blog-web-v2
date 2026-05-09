package dowob.xyz.blog.module.reading.model.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新 Highlight — color / note 皆 optional，僅當非 null 才更新。
 */
@Data
public class UpdateHighlightRequest {

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$")
    private String color;

    @Size(max = 2000)
    private String note;
}
