package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.response.ArticleArchiveResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.model.dto.response.EditorArticleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ArticleServiceImpl implements ArticleService {

    private final ArticleViewSubService articleViewSubService;
    private final ArticleCommandSubService commandSubService;
    private final ArticleQuerySubService querySubService;

    @Override
    public EditorArticleResponse createArticle(Long authorId, CreateArticleRequest request) {
        return commandSubService.createArticle(authorId, request);
    }

    @Override
    public EditorArticleResponse updateArticle(Long operatorId, Role operatorRole, UUID articleUuid,
            UpdateArticleRequest request) {
        return commandSubService.updateArticle(operatorId, operatorRole, articleUuid, request);
    }

    @Override
    public void deleteArticle(Long operatorId, Role operatorRole, UUID articleUuid) {
        commandSubService.deleteArticle(operatorId, operatorRole, articleUuid);
    }

    /**
     * {@inheritDoc}
     *
     * <p>刻意不標註 @Transactional：本方法在查詢後緊接 recordView（僅操作 Redis 並發送 MQ）；
     * 若在此開啟交易，viewed 事件會在交易作用域內送出（違反 code-standards §Transaction+MQ 時序），
     * 且會在 MQ I/O 期間持有 DB 連線。</p>
     */
    @Override
    public ArticleResponse getArticleByUuid(UUID articleUuid, Long viewerId, Role viewerRole, String clientIp) {
        ArticleResponse response = querySubService.getArticleByUuid(articleUuid, viewerId, viewerRole);
        articleViewSubService.recordView(articleUuid, response.getStatus(), clientIp);
        return response;
    }

    @Override
    public EditorArticleResponse getArticleForEdit(UUID articleUuid, Long requesterId) {
        return querySubService.getArticleForEdit(articleUuid, requesterId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>刻意不標註 @Transactional：理由同 {@link #getArticleByUuid}。</p>
     */
    @Override
    public ArticleResponse getArticleBySlug(String slug, Long viewerId, Role viewerRole, String clientIp) {
        ArticleResponse response = querySubService.getArticleBySlug(slug, viewerId, viewerRole);
        articleViewSubService.recordView(response.getUuid(), response.getStatus(), clientIp);
        return response;
    }

    @Override
    public PageResult<ArticleSummaryResponse> getPublishedArticles(int page, int size) {
        return querySubService.getPublishedArticles(page, size);
    }

    @Override
    public PageResult<ArticleSummaryResponse> getPublishedArticlesByCategorySlug(String categorySlug, int page,
            int size) {
        return querySubService.getPublishedArticlesByCategorySlug(categorySlug, page, size);
    }

    @Override
    public PageResult<ArticleSummaryResponse> getMyArticles(Long authorId, int page, int size, ArticleStatus status) {
        return querySubService.getMyArticles(authorId, page, size, status);
    }

    @Override
    public ArticleResponse publishArticle(Long operatorId, Role operatorRole, UUID articleUuid) {
        return commandSubService.publishArticle(operatorId, operatorRole, articleUuid);
    }

    @Override
    @Transactional
    public ArticleResponse rejectArticle(Long operatorId, Role operatorRole, UUID articleUuid, String reason) {
        return commandSubService.rejectArticle(operatorId, operatorRole, articleUuid, reason);
    }

    @Override
    public PageResult<ArticleSummaryResponse> getPendingArticles(int page, int size) {
        return querySubService.getPendingArticles(page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArticleArchiveResponse> getArchive() {
        return querySubService.getArchive();
    }

    @Override
    @Transactional
    public ArticleResponse submitForReview(Long operatorId, Role operatorRole, UUID articleUuid) {
        return commandSubService.submitForReview(operatorId, operatorRole, articleUuid);
    }

    @Override
    public void incrementCommentCount(Long articleId) {
        commandSubService.incrementCommentCount(articleId);
    }

    @Override
    public void decrementCommentCount(Long articleId) {
        commandSubService.decrementCommentCount(articleId);
    }

    @Override
    public void incrementLikeCount(Long articleId) {
        commandSubService.incrementLikeCount(articleId);
    }

    @Override
    public void decrementLikeCount(Long articleId) {
        commandSubService.decrementLikeCount(articleId);
    }

    @Override
    public Long findIdByUuid(UUID uuid) {
        return querySubService.findIdByUuid(uuid);
    }

    @Override
    public List<ArticleSummaryResponse> getArticleSummariesByIds(List<Long> articleIds) {
        return querySubService.getArticleSummariesByIds(articleIds);
    }

    @Override
    public List<Article> findByIds(List<Long> ids) {
        return querySubService.findByIds(ids);
    }

    @Override
    public Optional<Article> findByUuid(UUID uuid) {
        return querySubService.findByUuid(uuid);
    }

    @Override
    @Transactional
    public void updateSeriesAssignment(Long articleId, Long seriesId, Integer seriesPosition) {
        commandSubService.updateSeriesAssignment(articleId, seriesId, seriesPosition);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Article> findBySeriesIdOrderByPosition(Long seriesId) {
        return querySubService.findBySeriesIdOrderByPosition(seriesId);
    }

    @Override
    public Optional<Article> findById(Long id) {
        return querySubService.findById(id);
    }
}
