package dowob.xyz.blog.module.series.model.dto.response;

import dowob.xyz.blog.common.api.dto.AuthorSummary;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Series 列表用 summary。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class SeriesSummaryResponse {
    private UUID uuid;
    private String title;
    private String slug;
    private String description;
    private String coverImageUrl;
    private AuthorSummary author;
    private Integer articleCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
