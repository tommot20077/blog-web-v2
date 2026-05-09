package dowob.xyz.blog.module.series.model.dto.response;

import dowob.xyz.blog.common.api.dto.AuthorSummary;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Series 詳情 response。articles 按 series_position 排序。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class SeriesDetailResponse {
    private UUID uuid;
    private String title;
    private String slug;
    private String description;
    private String coverImageUrl;
    private AuthorSummary author;
    private Integer articleCount;
    private List<ArticleSummaryResponse> articles;
    private MyProgress myProgress;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
