package dowob.xyz.blog.module.series.model.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 加文章到 Series 的請求 / 改 position。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class AddArticleToSeriesRequest {
    @NotNull
    @Min(1)
    private Integer position;
}
