package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.dto.response.ArticleArchiveResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.model.dto.response.EditorArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.TagSummaryResponse;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
class ArticleQuerySubService {

    private final ArticleRepository articleRepository;
    private final ArticleMapper articleMapper;
    private final ArticleEntityFinder entityFinder;
    private final ArticleResponseMapper responseMapper;

    ArticleResponse getArticleByUuid(UUID articleUuid, Long viewerId, Role viewerRole) {
        Article article = entityFinder.findByUuidOrThrow(articleUuid);
        checkReadPermission(article, viewerId, viewerRole);
        return responseMapper.toResponse(article);
    }

    ArticleResponse getArticleBySlug(String slug, Long viewerId, Role viewerRole) {
        Article article = articleRepository.findBySlug(slug)
                .orElseThrow(() -> new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND));
        checkReadPermission(article, viewerId, viewerRole);
        return responseMapper.toResponse(article);
    }

    EditorArticleResponse getArticleForEdit(UUID articleUuid, Long requesterId) {
        Article article = entityFinder.findByUuidOrThrow(articleUuid);
        if (!article.getAuthorId().equals(requesterId)) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_ACCESS_DENIED);
        }
        return responseMapper.toEditorResponse(article);
    }

    PageResult<ArticleSummaryResponse> getPublishedArticles(int page, int size) {
        long offset = (long) (page - 1) * size;
        List<Article> articles = articleMapper.findPublishedPage(offset, size);
        long total = articleMapper.countPublished();
        List<ArticleSummaryResponse> list = toSummaryResponses(articles);
        return PageResult.of(page, size, total, list);
    }

    PageResult<ArticleSummaryResponse> getPublishedArticlesByCategorySlug(String categorySlug, int page, int size) {
        long offset = (long) (page - 1) * size;
        List<Article> articles = articleMapper.findPublishedPageByCategorySlug(categorySlug, offset, size);
        long total = articleMapper.countPublishedByCategorySlug(categorySlug);
        List<ArticleSummaryResponse> list = toSummaryResponses(articles);
        return PageResult.of(page, size, total, list);
    }

    PageResult<ArticleSummaryResponse> getMyArticles(Long authorId, int page, int size, ArticleStatus status) {
        long offset = (long) (page - 1) * size;
        List<Article> articles;
        long total;
        if (status != null) {
            articles = articleMapper.findByAuthorIdAndStatus(authorId, status, offset, size);
            total = articleMapper.countByAuthorIdAndStatus(authorId, status);
        } else {
            articles = articleMapper.findByAuthorIdPaged(authorId, offset, size);
            total = articleMapper.countByAuthorId(authorId);
        }
        List<ArticleSummaryResponse> list = toSummaryResponses(articles);
        return PageResult.of(page, size, total, list);
    }

    PageResult<ArticleSummaryResponse> getPendingArticles(int page, int size) {
        long offset = (long) (page - 1) * size;
        List<Article> articles = articleMapper.findPendingReviewPage(offset, size);
        long total = articleMapper.countPendingReview();
        List<ArticleSummaryResponse> list = toSummaryResponses(articles);
        return PageResult.of(page, size, total, list);
    }

    @Transactional(readOnly = true)
    List<ArticleArchiveResponse> getArchive() {
        List<Article> articles = articleMapper.findAllPublished();
        if (articles.isEmpty()) {
            return List.of();
        }
        List<UUID> uuids = articles.stream().map(Article::getUuid).collect(Collectors.toList());
        Map<UUID, List<TagSummaryResponse>> tagMap = responseMapper.batchToTagResponsesMap(uuids);
        return articles.stream()
                .map(article -> ArticleArchiveResponse.builder()
                        .uuid(article.getUuid())
                        .title(article.getTitle())
                        .slug(article.getSlug())
                        .publishedAt(article.getPublishedAt())
                        .tags(tagMap.getOrDefault(article.getUuid(), List.of()).stream()
                                .map(TagSummaryResponse::getName)
                                .collect(Collectors.toList()))
                        .build())
                .collect(Collectors.toList());
    }

    Long findIdByUuid(UUID uuid) {
        return articleMapper.findIdByUuid(uuid);
    }

    List<ArticleSummaryResponse> getArticleSummariesByIds(List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return List.of();
        }
        // 批次載入，避免逐筆 findById + 逐篇 tag 查詢的 N+1（此端點含 series 詳情等公開讀取路徑）。
        Map<Long, Article> byId = new HashMap<>();
        articleRepository.findAllById(articleIds).forEach(a -> byId.put(a.getId(), a));
        // 依 input id 順序輸出（series reading order 依賴此順序），跳過查無的 id。
        List<Article> ordered = articleIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }
        List<UUID> uuids = ordered.stream().map(Article::getUuid).toList();
        Map<UUID, List<TagSummaryResponse>> tagMap = responseMapper.batchToTagResponsesMap(uuids);
        return ordered.stream()
                .map(article -> responseMapper.toSummaryResponse(article, tagMap))
                .collect(Collectors.toList());
    }

    List<Article> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Article> result = new ArrayList<>();
        articleRepository.findAllById(ids).forEach(result::add);
        return result;
    }

    Optional<Article> findByUuid(UUID uuid) {
        return articleRepository.findByUuid(uuid);
    }

    @Transactional(readOnly = true)
    List<Article> findBySeriesIdOrderByPosition(Long seriesId) {
        return articleMapper.findBySeriesIdOrderByPosition(seriesId);
    }

    Optional<Article> findById(Long id) {
        return articleRepository.findById(id);
    }

    private List<ArticleSummaryResponse> toSummaryResponses(List<Article> articles) {
        List<UUID> uuids = articles.stream().map(Article::getUuid).collect(Collectors.toList());
        Map<UUID, List<TagSummaryResponse>> tagMap = responseMapper.batchToTagResponsesMap(uuids);
        return articles.stream()
                .map(article -> responseMapper.toSummaryResponse(article, tagMap))
                .collect(Collectors.toList());
    }

    private void checkReadPermission(Article article, Long viewerId, Role viewerRole) {
        boolean isAdmin = Role.ADMIN == viewerRole;
        boolean isAuthor = Objects.equals(article.getAuthorId(), viewerId);
        boolean isPublished = article.getStatus().isPubliclyVisible();

        if (!isPublished && !isAdmin && !isAuthor) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
        }
    }
}
