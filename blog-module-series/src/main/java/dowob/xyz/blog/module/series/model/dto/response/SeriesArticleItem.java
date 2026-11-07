package dowob.xyz.blog.module.series.model.dto.response;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryResponse;
import dowob.xyz.blog.infrastructure.facade.dto.TagSummaryResponse;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 系列詳情中的一篇文章。
 *
 * <p>ARCH-26 / 決策 C6：series 不再把 infrastructure 的 {@link ArticleSummaryResponse}（文章列表 API 共用的 DTO）
 * 直接放進自己的回應，改持有自己的型別，文章列表 DTO 日後調整欄位不會連動改到系列詳情的對外契約。</p>
 *
 * <p><b>JSON 形狀與改版前完全相同</b>：欄位名稱、型別、null 是否輸出都不變，前端型別不需修改。
 * 因此這裡保留了系列詳情目前實際輸出的全部 22 個欄位——包含登入者相關的 {@code liked}／{@code bookmarked}／
 * {@code lastReadProgress}（由 article facade 依目前登入者補值），以及 {@code rejectReason} 等系列頁用不到、
 * 但目前仍會輸出（對公開可見文章為 null）的欄位。哪些欄位可以瘦身是日後的契約變更，需連同前端一起改，不在此次範圍。
 * 形狀由 {@code SeriesArticleItemJsonShapeTest} 與改版前錄下的 key 集合比對固定。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Builder
public class SeriesArticleItem {
    private UUID uuid;
    private String title;
    private String summary;
    private String coverImageUrl;
    private UUID authorUuid;
    private String authorNickname;
    private ArticleStatus status;
    private long viewCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String slug;
    private long likeCount;
    private int commentCount;
    private LocalDateTime publishedAt;
    private List<TagSummaryResponse> tags;
    private String rejectReason;
    private Boolean liked;
    private Boolean bookmarked;
    private BigDecimal lastReadProgress;
    private UUID seriesUuid;
    private String seriesTitle;
    private Integer seriesPosition;

    /**
     * 由 article facade 回傳的文章摘要轉成系列詳情用的項目。
     *
     * @param source 文章摘要
     * @return 系列詳情用的項目
     */
    public static SeriesArticleItem from(ArticleSummaryResponse source) {
        return SeriesArticleItem.builder()
                .uuid(source.getUuid())
                .title(source.getTitle())
                .summary(source.getSummary())
                .coverImageUrl(source.getCoverImageUrl())
                .authorUuid(source.getAuthorUuid())
                .authorNickname(source.getAuthorNickname())
                .status(source.getStatus())
                .viewCount(source.getViewCount())
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
                .slug(source.getSlug())
                .likeCount(source.getLikeCount())
                .commentCount(source.getCommentCount())
                .publishedAt(source.getPublishedAt())
                .tags(source.getTags())
                .rejectReason(source.getRejectReason())
                .liked(source.getLiked())
                .bookmarked(source.getBookmarked())
                .lastReadProgress(source.getLastReadProgress())
                .seriesUuid(source.getSeriesUuid())
                .seriesTitle(source.getSeriesTitle())
                .seriesPosition(source.getSeriesPosition())
                .build();
    }
}
