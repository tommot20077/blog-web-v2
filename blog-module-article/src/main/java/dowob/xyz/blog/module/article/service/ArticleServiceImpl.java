package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.model.dto.response.EditorArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.TagSummaryResponse;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文章服務實作
 *
 * <p>
 * 實作所有文章業務邏輯，包含 CRUD、權限控管與狀態機轉換。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleServiceImpl implements ArticleService {

    /**
     * 文章 Repository（簡單 CRUD）
     */
    private final ArticleRepository articleRepository;

    /**
     * 文章 Mapper（複雜查詢）
     */
    private final ArticleMapper articleMapper;

    private final ArticleEntityFinder articleEntityFinder;

    private final ArticleResponseMapper articleResponseMapper;

    private final ArticleViewSubService articleViewSubService;

    private final ArticleCommandSubService commandSubService;


    /**
     * 建立文章
     *
     * @param authorId 作者資料庫主鍵
     * @param request  建立文章請求
     * @return 建立後的文章完整資訊
     */
    @Override
    public EditorArticleResponse createArticle(Long authorId, CreateArticleRequest request) {
        return commandSubService.createArticle(authorId, request);
    }

    /**
     * 更新文章
     *
     * @param operatorId   操作者資料庫主鍵
     * @param operatorRole 操作者角色
     * @param articleUuid  文章公開 UUID
     * @param request      更新請求
     * @return 更新後的文章完整資訊
     */
    @Override
    public EditorArticleResponse updateArticle(Long operatorId, Role operatorRole, UUID articleUuid,
            UpdateArticleRequest request) {
        return commandSubService.updateArticle(operatorId, operatorRole, articleUuid, request);
    }

    /**
     * 刪除文章
     *
     * @param operatorId   操作者資料庫主鍵
     * @param operatorRole 操作者角色
     * @param articleUuid  文章公開 UUID
     */
    @Override
    public void deleteArticle(Long operatorId, Role operatorRole, UUID articleUuid) {
        commandSubService.deleteArticle(operatorId, operatorRole, articleUuid);
    }

    /**
     * 根據 UUID 取得文章詳情
     *
     * <p>
     * 已發布文章同 IP 5 分鐘內只計算一次瀏覽數（Redis 防刷）。
     * </p>
     *
     * @param articleUuid 文章公開 UUID
     * @param viewerId    觀看者 ID（匿名為 null）
     * @param viewerRole  觀看者角色（匿名為 null）
     * @param clientIp    客戶端 IP（用於防刷）
     * @return 文章完整資訊
     */
    @Override
    @Transactional
    public ArticleResponse getArticleByUuid(UUID articleUuid, Long viewerId, Role viewerRole, String clientIp) {
        Article article = articleEntityFinder.findByUuidOrThrow(articleUuid);
        return processArticleView(article, viewerId, viewerRole, clientIp);
    }

    /**
     * 取得文章供 Editor 編輯（僅作者本人）
     *
     * @param articleUuid 文章公開 UUID
     * @param requesterId 請求者資料庫主鍵
     * @return Editor 用文章資訊
     */
    @Override
    public EditorArticleResponse getArticleForEdit(UUID articleUuid, Long requesterId) {
        Article article = articleEntityFinder.findByUuidOrThrow(articleUuid);
        if (!article.getAuthorId().equals(requesterId)) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_ACCESS_DENIED);
        }
        return articleResponseMapper.toEditorResponse(article);
    }

    /**
     * 根據 slug 取得文章詳情
     *
     * @param slug       文章 URL slug
     * @param viewerId   觀看者 ID（匿名為 null）
     * @param viewerRole 觀看者角色（匿名為 null）
     * @param clientIp   客戶端 IP（用於防刷）
     * @return 文章完整資訊
     */
    @Override
    @Transactional
    public ArticleResponse getArticleBySlug(String slug, Long viewerId, Role viewerRole, String clientIp) {
        Article article = articleRepository.findBySlug(slug)
                .orElseThrow(() -> new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND));
        return processArticleView(article, viewerId, viewerRole, clientIp);
    }

    /**
     * 處理文章存取：驗證可見性、計算瀏覽數、回傳回應
     *
     * @param article    文章實體
     * @param viewerId   觀看者 ID（匿名為 null）
     * @param viewerRole 觀看者角色（匿名為 null）
     * @param clientIp   客戶端 IP
     * @return 文章完整資訊
     */
    private ArticleResponse processArticleView(Article article, Long viewerId, Role viewerRole, String clientIp) {
        boolean isAdmin = Role.ADMIN == viewerRole;
        boolean isAuthor = Objects.equals(article.getAuthorId(), viewerId);
        boolean isPublished = article.getStatus().isPubliclyVisible();

        if (!isPublished && !isAdmin && !isAuthor) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
        }

        /** 增加瀏覽次數（Redis 防刷：同 IP 5 分鐘內只計算一次，使用原子性 setIfAbsent 防競態） */
        articleViewSubService.recordView(article.getUuid(), article.getStatus(), clientIp);

        return articleResponseMapper.toResponse(article);
    }

    /**
     * 分頁取得已發布文章列表
     *
     * @param page 頁碼（從 1 開始）
     * @param size 每頁筆數
     * @return 分頁文章摘要列表
     */
    @Override
    public PageResult<ArticleSummaryResponse> getPublishedArticles(int page, int size) {
        long offset = (long) (page - 1) * size;
        List<Article> articles = articleMapper.findPublishedPage(offset, size);
        long total = articleMapper.countPublished();
        List<UUID> uuids = articles.stream().map(Article::getUuid).collect(Collectors.toList());
        Map<UUID, List<TagSummaryResponse>> tagMap = articleResponseMapper.batchToTagResponsesMap(uuids);
        List<ArticleSummaryResponse> list = articles.stream()
                .map(a -> articleResponseMapper.toSummaryResponse(a, tagMap))
                .collect(Collectors.toList());
        return PageResult.of(page, size, total, list);
    }

    /**
     * 根據分類 slug 分頁取得已發布文章列表
     *
     * @param categorySlug 分類 slug
     * @param page         頁碼（從 1 開始）
     * @param size         每頁筆數
     * @return 分頁文章摘要列表
     */
    @Override
    public PageResult<ArticleSummaryResponse> getPublishedArticlesByCategorySlug(
            String categorySlug, int page, int size) {
        long offset = (long) (page - 1) * size;
        List<Article> articles = articleMapper.findPublishedPageByCategorySlug(categorySlug, offset, size);
        long total = articleMapper.countPublishedByCategorySlug(categorySlug);
        List<UUID> uuids = articles.stream().map(Article::getUuid).collect(Collectors.toList());
        Map<UUID, List<TagSummaryResponse>> tagMap = articleResponseMapper.batchToTagResponsesMap(uuids);
        List<ArticleSummaryResponse> list = articles.stream()
                .map(a -> articleResponseMapper.toSummaryResponse(a, tagMap))
                .collect(Collectors.toList());
        return PageResult.of(page, size, total, list);
    }

    /**
     * 分頁取得當前登入用戶的文章列表
     *
     * @param authorId 作者資料庫主鍵
     * @param page  頁碼（從 1 開始）
     * @param size 每頁筆數
     * @param status   文章狀態篩選（null 表示查詢全部）
     * @return 分頁文章摘要列表
     */
    @Override
    public PageResult<ArticleSummaryResponse> getMyArticles(Long authorId, int page, int size, ArticleStatus status) {
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
        List<UUID> uuids = articles.stream().map(Article::getUuid).collect(Collectors.toList());
        Map<UUID, List<TagSummaryResponse>> tagMap = articleResponseMapper.batchToTagResponsesMap(uuids);
        List<ArticleSummaryResponse> list = articles.stream()
                .map(a -> articleResponseMapper.toSummaryResponse(a, tagMap))
                .collect(Collectors.toList());
        return PageResult.of(page, size, total, list);
    }

    /**
     * 發布文章
     *
     * @param operatorId   操作者資料庫主鍵
     * @param operatorRole 操作者角色
     * @param articleUuid  文章公開 UUID
     * @return 發布後的文章完整資訊
     */
    @Override
    public ArticleResponse publishArticle(Long operatorId, Role operatorRole, UUID articleUuid) {
        return commandSubService.publishArticle(operatorId, operatorRole, articleUuid);
    }

    /**
     * 駁回文章（僅 ADMIN）
     *
     * @param operatorId   操作者資料庫主鍵
     * @param operatorRole 操作者角色
     * @param articleUuid  文章公開 UUID
     * @param reason       駁回原因
     * @return 駁回後的文章完整資訊
     */
    @Override
    @Transactional
    public ArticleResponse rejectArticle(Long operatorId, Role operatorRole, UUID articleUuid, String reason) {
        return commandSubService.rejectArticle(operatorId, operatorRole, articleUuid, reason);
    }

    /**
     * 分頁取得待審文章列表（僅 ADMIN）
     *
     * @param page 頁碼（從 1 開始）
     * @param size 每頁筆數
     * @return 分頁文章摘要列表
     */
    @Override
    public PageResult<ArticleSummaryResponse> getPendingArticles(int page, int size) {
        long offset = (long) (page - 1) * size;
        List<Article> articles = articleMapper.findPendingReviewPage(offset, size);
        long total = articleMapper.countPendingReview();
        List<UUID> uuids = articles.stream().map(Article::getUuid).collect(Collectors.toList());
        Map<UUID, List<TagSummaryResponse>> tagMap = articleResponseMapper.batchToTagResponsesMap(uuids);
        List<ArticleSummaryResponse> list = articles.stream()
                .map(a -> articleResponseMapper.toSummaryResponse(a, tagMap))
                .collect(Collectors.toList());
        return PageResult.of(page, size, total, list);
    }

    /**
     * 提交文章審核（DRAFT → PENDING_REVIEW）
     *
     * @param operatorId   操作者資料庫主鍵
     * @param operatorRole 操作者角色
     * @param articleUuid  文章公開 UUID
     * @return 提交審核後的文章完整資訊
     */
    @Override
    @Transactional
    public ArticleResponse submitForReview(Long operatorId, Role operatorRole, UUID articleUuid) {
        return commandSubService.submitForReview(operatorId, operatorRole, articleUuid);
    }







    /**
     * 原子性遞增文章留言計數
     *
     * @param articleId 文章資料庫主鍵
     */
    @Override
    public void incrementCommentCount(Long articleId) {
        commandSubService.incrementCommentCount(articleId);
    }

    /**
     * 原子性遞減文章留言計數（守衛 > 0，防 underflow）
     *
     * @param articleId 文章資料庫主鍵
     */
    @Override
    public void decrementCommentCount(Long articleId) {
        commandSubService.decrementCommentCount(articleId);
    }

    /**
     * 原子性遞增文章按讚計數
     *
     * @param articleId 文章資料庫主鍵
     */
    @Override
    public void incrementLikeCount(Long articleId) {
        commandSubService.incrementLikeCount(articleId);
    }

    /**
     * 原子性遞減文章按讚計數（守衛 > 0，防 underflow）
     *
     * @param articleId 文章資料庫主鍵
     */
    @Override
    public void decrementLikeCount(Long articleId) {
        commandSubService.decrementLikeCount(articleId);
    }

    /**
     * 根據文章公開 UUID 查詢資料庫主鍵
     *
     * @param uuid 文章公開 UUID
     * @return 文章資料庫主鍵，若不存在則回傳 null
     */
    @Override
    public Long findIdByUuid(UUID uuid) {
        return articleMapper.findIdByUuid(uuid);
    }

    /**
     * 根據文章 ID 列表批次取得文章摘要（stub，Task 12 將改為批次查詢）。
     *
     * @param articleIds 文章資料庫主鍵列表
     * @return 文章摘要列表
     */
    @Override
    public List<ArticleSummaryResponse> getArticleSummariesByIds(List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) return List.of();
        List<ArticleSummaryResponse> results = new java.util.ArrayList<>();
        for (Long id : articleIds) {
            articleRepository.findById(id).ifPresent(a -> {
                Map<UUID, List<TagSummaryResponse>> tagMap =
                        articleResponseMapper.batchToTagResponsesMap(List.of(a.getUuid()));
                results.add(articleResponseMapper.toSummaryResponse(a, tagMap));
            });
        }
        return results;
    }

    @Override
    public List<Article> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Article> result = new java.util.ArrayList<>();
        articleRepository.findAllById(ids).forEach(result::add);
        return result;
    }

    /**
     * 根據文章公開 UUID 查詢文章實體（供跨模組使用）。
     *
     * @param uuid 文章公開 UUID
     * @return 文章 Optional
     */
    @Override
    public java.util.Optional<Article> findByUuid(UUID uuid) {
        return articleRepository.findByUuid(uuid);
    }

    /**
     * 更新文章的 series 歸屬與排序位置。
     *
     * @param articleId      文章資料庫主鍵
     * @param seriesId       所屬 series 主鍵（null 表示解除）
     * @param seriesPosition 在 series 中的排序位置（null 表示解除）
     */
    @Override
    @Transactional
    public void updateSeriesAssignment(Long articleId, Long seriesId, Integer seriesPosition) {
        commandSubService.updateSeriesAssignment(articleId, seriesId, seriesPosition);
    }

    /**
     * 查詢指定 Series 內的所有文章，按 series_position 升冪排序。
     *
     * @param seriesId Series 資料庫主鍵
     * @return 按 series_position 排序的文章列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<Article> findBySeriesIdOrderByPosition(Long seriesId) {
        return articleMapper.findBySeriesIdOrderByPosition(seriesId);
    }

    /**
     * 根據資料庫主鍵查詢文章實體（供 SeriesFacade 等跨模組使用）。
     *
     * @param id 文章資料庫主鍵
     * @return 文章 Optional
     */
    @Override
    public java.util.Optional<Article> findById(Long id) {
        return articleRepository.findById(id);
    }

}
