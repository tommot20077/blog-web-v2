package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.util.SecurityUtils;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesNavigation;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.CategoryMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.CategoryWithArticleId;
import dowob.xyz.blog.module.article.model.TagWithArticleUuid;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.model.dto.response.CategoryResponse;
import dowob.xyz.blog.module.article.model.dto.response.EditorArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.TagSummaryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
class ArticleResponseMapper {

    private final ArticleMapper articleMapper;
    private final CategoryMapper categoryMapper;
    private final UserFacade userFacade;
    private final ViewCountService viewCountService;
    private final ArticleTocCodec articleTocCodec;

    ArticleResponse toResponse(Article article) {
        return toResponse(
                article,
                toTagSummaryResponses(article.getUuid()),
                toCategoryResponses(article.getId()),
                null,
                null,
                null,
                null);
    }

    ArticleResponse toResponse(Article article,
                               List<TagSummaryResponse> tags,
                               List<CategoryResponse> categories,
                               Boolean liked,
                               Boolean bookmarked,
                               BigDecimal lastReadProgress,
                               SeriesNavigation seriesNav) {
        return ArticleResponse.builder()
                .uuid(article.getUuid())
                .title(article.getTitle())
                .content(article.getContent())
                .contentHtml(article.getContentHtml())
                .summary(article.getSummary())
                .coverImageUrl(article.getCoverImageUrl())
                .authorUuid(resolveAuthorUuid(article.getAuthorId()))
                .authorNickname(resolveAuthorNickname(article.getAuthorId()))
                .status(article.getStatus())
                .viewCount(viewCountService.getViewCount(article.getUuid()))
                .createdAt(article.getCreatedAt())
                .updatedAt(article.getUpdatedAt())
                .categories(categories)
                .slug(article.getSlug())
                .likeCount(article.getLikeCount())
                .commentCount(article.getCommentCount())
                .publishedAt(article.getPublishedAt())
                .tags(tags)
                .rejectReason(resolveRejectReason(article))
                .liked(liked)
                .bookmarked(bookmarked)
                .lastReadProgress(lastReadProgress)
                .seriesNav(seriesNav)
                .toc(articleTocCodec.deserialize(article.getToc()))
                .build();
    }

    EditorArticleResponse toEditorResponse(Article article) {
        return toEditorResponse(article, toTagSummaryResponses(article.getUuid()), toCategoryResponses(article.getId()));
    }

    EditorArticleResponse toEditorResponse(Article article,
                                           List<TagSummaryResponse> tags,
                                           List<CategoryResponse> categories) {
        return EditorArticleResponse.builder()
                .uuid(article.getUuid())
                .title(article.getTitle())
                .summary(article.getSummary())
                .content(article.getContent())
                .coverImageUrl(article.getCoverImageUrl())
                .status(article.getStatus())
                .categories(categories)
                .tags(tags)
                .rejectReason(resolveRejectReason(article))
                .createdAt(article.getCreatedAt())
                .updatedAt(article.getUpdatedAt())
                .toc(articleTocCodec.deserialize(article.getToc()))
                .build();
    }

    ArticleSummaryResponse toSummaryResponse(Article article, Map<UUID, List<TagSummaryResponse>> tagMap) {
        return toSummaryResponse(article, tagMap.getOrDefault(article.getUuid(), List.of()));
    }

    ArticleSummaryResponse toSummaryResponse(Article article, List<TagSummaryResponse> tags) {
        return ArticleSummaryResponse.builder()
                .uuid(article.getUuid())
                .title(article.getTitle())
                .summary(article.getSummary())
                .coverImageUrl(article.getCoverImageUrl())
                .authorUuid(resolveAuthorUuid(article.getAuthorId()))
                .authorNickname(resolveAuthorNickname(article.getAuthorId()))
                .status(article.getStatus())
                .viewCount(article.getViewCount())
                .createdAt(article.getCreatedAt())
                .updatedAt(article.getUpdatedAt())
                .slug(article.getSlug())
                .likeCount(article.getLikeCount())
                .commentCount(article.getCommentCount())
                .publishedAt(article.getPublishedAt())
                .tags(tags)
                .rejectReason(resolveRejectReason(article))
                .seriesPosition(article.getSeriesPosition())
                .build();
    }

    List<TagSummaryResponse> toTagSummaryResponses(UUID articleUuid) {
        if (articleUuid == null) {
            return List.of();
        }
        return toTagSummaryResponses(articleMapper.findTagsByArticleUuid(articleUuid));
    }

    List<TagSummaryResponse> toTagSummaryResponses(List<TagInfo> tagInfos) {
        if (tagInfos == null || tagInfos.isEmpty()) {
            return List.of();
        }
        return tagInfos.stream()
                .map(tag -> TagSummaryResponse.builder()
                        .id(tag.id())
                        .name(tag.name())
                        .slug(tag.slug())
                        .build())
                .collect(Collectors.toList());
    }

    Map<UUID, List<TagSummaryResponse>> batchToTagResponsesMap(List<UUID> articleUuids) {
        if (articleUuids == null || articleUuids.isEmpty()) {
            return Map.of();
        }
        List<TagWithArticleUuid> all = articleMapper.findTagsByArticleUuids(articleUuids);
        return all.stream().collect(Collectors.groupingBy(
                TagWithArticleUuid::getArticleUuid,
                Collectors.mapping(t -> TagSummaryResponse.builder()
                                .id(t.getId())
                                .name(t.getName())
                                .slug(t.getSlug())
                                .build(),
                        Collectors.toList())));
    }

    List<CategoryResponse> toCategoryResponses(Long articleId) {
        if (articleId == null) {
            return List.of();
        }
        return batchToCategoryResponsesMap(List.of(articleId)).getOrDefault(articleId, List.of());
    }

    Map<Long, List<CategoryResponse>> batchToCategoryResponsesMap(List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return Map.of();
        }
        List<CategoryWithArticleId> all = categoryMapper.findCategoriesByArticleIds(articleIds);
        return all.stream().collect(Collectors.groupingBy(
                CategoryWithArticleId::getArticleId,
                Collectors.mapping(c -> CategoryResponse.builder()
                                .uuid(c.getUuid())
                                .name(c.getName())
                                .slug(c.getSlug())
                                .description(c.getDescription())
                                .sortOrder(c.getSortOrder())
                                .build(),
                        Collectors.toList())));
    }

    /**
     * 解析當前觀看者可見的 {@code rejectReason}（管理員撰寫的內部審核評語）。
     *
     * <p>
     * {@code rejectReason} 是 <b>admin 撰寫的內部審核評語</b>，屬 {@code ai-docs/security.md}「資料最小揭露」的範疇，
     * 只應對<b>文章作者本人</b>與 <b>ADMIN</b> 揭露；其餘觀看者（含匿名讀者與其他已登入使用者）
     * 一律得到 {@code null}。三個回應 DTO（{@code ArticleResponse} / {@code ArticleSummaryResponse} /
     * {@code EditorArticleResponse}）的 {@code rejectReason} 全部由本 mapper 填入，
     * 故遮蔽規則在此收斂為單一 choke point，涵蓋所有現有與未來的呼叫端
     * （包含 series 詳情、收藏列表等跨模組讀取路徑）。
     * </p>
     *
     * <p>
     * 觀看者身分取自當前 {@code SecurityContext}，與 {@code SecurityUtils.isAdmin()} 無參版
     * （comment / series / version 模組既有用法）以及 {@code ArticleQueryService} 填充
     * liked / bookmarked 的方式為同一慣例。無法識別觀看者（無認證、MQ consumer、排程等
     * 非請求執行緒）時一律遮蔽，確保 fail-closed。
     * </p>
     *
     * @param article 文章實體
     * @return 觀看者為作者本人或 ADMIN 時回傳原始評語，其餘一律回傳 {@code null}
     */
    private String resolveRejectReason(Article article) {
        if (article == null || article.getRejectReason() == null) {
            return null;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (SecurityUtils.isAdmin(authentication)) {
            return article.getRejectReason();
        }
        Long viewerId = resolveViewerId(authentication);
        if (viewerId != null && Objects.equals(article.getAuthorId(), viewerId)) {
            return article.getRejectReason();
        }
        return null;
    }

    /**
     * 從認證物件取出觀看者的資料庫主鍵。
     *
     * <p>Principal 由 {@code JwtAuthenticationFilter} 寫入為 {@link Long}（userId），
     * 與 {@code ArticleQueryService} 判斷 liked / bookmarked 的取值方式一致。
     * 未登入 / 匿名 / principal 非 Long 一律回傳 {@code null}，代表「無法識別的觀看者」。</p>
     *
     * @param authentication 當前認證物件（可為 null）
     * @return 觀看者 DB 主鍵，無法識別時回傳 {@code null}
     */
    private Long resolveViewerId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getPrincipal() instanceof Long viewerId ? viewerId : null;
    }

    UUID resolveAuthorUuid(Long authorId) {
        return userFacade.getUserUuidById(authorId).orElse(null);
    }

    String resolveAuthorNickname(Long authorId) {
        return userFacade.getUserNicknameById(authorId).orElse(null);
    }

}
