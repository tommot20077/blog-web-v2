package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.dto.request.ArticleListQuery;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.response.ArticleArchiveResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryResponse;
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
    public EditorArticleResponse createArticle(UUID authorUuid, CreateArticleRequest request) {
        return commandSubService.createArticle(authorUuid, request);
    }

    @Override
    public EditorArticleResponse updateArticle(UUID operatorUuid, Role operatorRole, UUID articleUuid,
            UpdateArticleRequest request) {
        return commandSubService.updateArticle(operatorUuid, operatorRole, articleUuid, request);
    }

    @Override
    public void deleteArticle(UUID operatorUuid, Role operatorRole, UUID articleUuid) {
        commandSubService.deleteArticle(operatorUuid, operatorRole, articleUuid);
    }

    /**
     * {@inheritDoc}
     *
     * <p>刻意不標註 @Transactional：本方法在查詢後緊接 recordView（僅操作 Redis 並發送 MQ）；
     * 若在此開啟交易，viewed 事件會在交易作用域內送出（違反 code-standards §Transaction+MQ 時序），
     * 且會在 MQ I/O 期間持有 DB 連線。</p>
     */
    @Override
    public ArticleResponse getArticleByUuid(UUID articleUuid, UUID viewerUuid, Role viewerRole, String clientIp) {
        ArticleResponse response = querySubService.getArticleByUuid(articleUuid, viewerUuid, viewerRole);
        articleViewSubService.recordView(articleUuid, response.getStatus(), clientIp);
        return response;
    }

    @Override
    public EditorArticleResponse getArticleForEdit(UUID articleUuid, UUID requesterUuid) {
        return querySubService.getArticleForEdit(articleUuid, requesterUuid);
    }

    /**
     * {@inheritDoc}
     *
     * <p>刻意不標註 @Transactional：理由同 {@link #getArticleByUuid}。</p>
     */
    @Override
    public ArticleResponse getArticleBySlug(String slug, UUID viewerUuid, Role viewerRole, String clientIp) {
        ArticleResponse response = querySubService.getArticleBySlug(slug, viewerUuid, viewerRole);
        articleViewSubService.recordView(response.getUuid(), response.getStatus(), clientIp);
        return response;
    }

    @Override
    public PageResult<ArticleSummaryResponse> getPublishedArticles(ArticleListQuery query, int page, int size) {
        return querySubService.getPublishedArticles(query, page, size);
    }

    @Override
    public PageResult<ArticleSummaryResponse> getMyArticles(UUID authorUuid, int page, int size, ArticleStatus status) {
        return querySubService.getMyArticles(authorUuid, page, size, status);
    }

    @Override
    public ArticleResponse publishArticle(UUID operatorUuid, Role operatorRole, UUID articleUuid) {
        return commandSubService.publishArticle(operatorUuid, operatorRole, articleUuid);
    }

    @Override
    @Transactional
    public ArticleResponse rejectArticle(UUID operatorUuid, Role operatorRole, UUID articleUuid, String reason) {
        return commandSubService.rejectArticle(operatorUuid, operatorRole, articleUuid, reason);
    }

    /**
     * {@inheritDoc}
     *
     * <p>刻意不標註 @Transactional：下架在 DB 寫入後會發送 article.archived 事件，
     * 交易作用域沿呼叫鏈傳遞，若在此開啟交易，事件會在 commit 前送出
     * （違反 code-standards §Transaction+MQ 時序）。交易由
     * {@code ArticleCommandSubService} 內的 TransactionTemplate 自行收斂。</p>
     */
    @Override
    public ArticleResponse archiveArticle(UUID operatorUuid, Role operatorRole, UUID articleUuid) {
        return commandSubService.archiveArticle(operatorUuid, operatorRole, articleUuid);
    }

    @Override
    @Transactional
    public ArticleResponse unarchiveArticle(UUID operatorUuid, Role operatorRole, UUID articleUuid) {
        return commandSubService.unarchiveArticle(operatorUuid, operatorRole, articleUuid);
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
    public ArticleResponse submitForReview(UUID operatorUuid, Role operatorRole, UUID articleUuid) {
        return commandSubService.submitForReview(operatorUuid, operatorRole, articleUuid);
    }

    @Override
    @Transactional
    public ArticleResponse withdrawArticle(UUID operatorUuid, Role operatorRole, UUID articleUuid) {
        return commandSubService.withdrawArticle(operatorUuid, operatorRole, articleUuid);
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
    public void updateSeriesAssignment(Long articleId, UUID seriesUuid, Integer seriesPosition) {
        commandSubService.updateSeriesAssignment(articleId, seriesUuid, seriesPosition);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Article> findBySeriesUuidOrderByPosition(UUID seriesUuid) {
        return querySubService.findBySeriesUuidOrderByPosition(seriesUuid);
    }

    @Override
    public Optional<Article> findById(Long id) {
        return querySubService.findById(id);
    }
}
