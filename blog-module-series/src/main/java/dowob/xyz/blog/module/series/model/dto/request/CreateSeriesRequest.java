package dowob.xyz.blog.module.series.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 建立 Series 請求。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class CreateSeriesRequest {
    @NotBlank
    @Size(max = 255)
    private String title;

    @NotBlank
    @Size(max = 255)
    @Pattern(regexp = "^[a-z0-9-]+$")
    private String slug;

    private String description;

    @Size(max = 512)
    private String coverImageUrl;
}
