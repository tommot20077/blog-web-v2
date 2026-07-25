package dowob.xyz.blog.module.article.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import dowob.xyz.blog.module.article.model.dto.response.TocEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
    private final ObjectMapper objectMapper;

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
                .rejectReason(article.getRejectReason())
                .liked(liked)
                .bookmarked(bookmarked)
                .lastReadProgress(lastReadProgress)
                .seriesNav(seriesNav)
                .toc(deserializeToc(article.getToc()))
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
                .rejectReason(article.getRejectReason())
                .createdAt(article.getCreatedAt())
                .updatedAt(article.getUpdatedAt())
                .toc(deserializeToc(article.getToc()))
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
                .rejectReason(article.getRejectReason())
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

    UUID resolveAuthorUuid(Long authorId) {
        return userFacade.getUserUuidById(authorId).orElse(null);
    }

    String resolveAuthorNickname(Long authorId) {
        return userFacade.getUserNicknameById(authorId).orElse(null);
    }

    /**
     * 將持久化於 {@code articles.toc} 的 JSON 字串反序列化為結構化的 {@code List<TocEntry>}。
     *
     * <p>與 {@code ArticleCommandSubService#serializeToc} 對稱：該處序列化寫入，此處讀回。
     * 空值（null/空字串）或解析失敗一律回傳空陣列，不拋例外——TOC 資料缺失或損毀不應使
     * 文章本體無法讀取。</p>
     *
     * @param tocJson 資料庫欄位原始值，可能為 null 或空字串
     * @return 反序列化後的章節條目清單，恆非 null
     */
    private List<TocEntry> deserializeToc(String tocJson) {
        if (tocJson == null || tocJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tocJson, new TypeReference<List<TocEntry>>() {});
        } catch (JsonProcessingException e) {
            log.warn("TOC 反序列化失敗，改回空陣列：{}", e.getMessage());
            return List.of();
        }
    }
}
