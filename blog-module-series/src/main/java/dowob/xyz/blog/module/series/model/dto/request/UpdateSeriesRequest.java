package dowob.xyz.blog.module.series.model.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Series 部分更新請求；所有欄位 optional，僅當非 null 才更新。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class UpdateSeriesRequest {
    @Size(max = 255)
    private String title;

    @Size(max = 255)
    @Pattern(regexp = "^[a-z0-9-]+$")
    private String slug;

    private String description;

    @Size(max = 512)
    private String coverImageUrl;
}
