package dowob.xyz.blog.infrastructure.facade.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Series 內文章導覽資訊（給 ArticleResponse.seriesNav 用）。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeriesNavigation {
    private UUID seriesUuid;
    private String seriesTitle;
    private String seriesSlug;
    private Integer position;
    private Integer totalCount;
    private SeriesArticleRef prev;
    private SeriesArticleRef next;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeriesArticleRef {
        private UUID uuid;
        private String title;
        private String slug;
    }
}
