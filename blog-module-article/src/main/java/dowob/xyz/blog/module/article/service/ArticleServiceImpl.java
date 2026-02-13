package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.module.article.event.ArticlePublishedEvent;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * 用戶 Facade（跨模組查詢）
     */
    private final UserFacade userFacade;

    /**
     * RabbitMQ 訊息發送模板
     */
    private final RabbitTemplate rabbitTemplate;

    /**
     * 合法狀態轉換規則
     */
    private static final Map<ArticleStatus, Set<ArticleStatus>> VALID_TRANSITIONS = Map.of(
            ArticleStatus.DRAFT, Set.of(ArticleStatus.PUBLISHED, ArticleStatus.PENDING_REVIEW),
            ArticleStatus.PENDING_REVIEW, Set.of(ArticleStatus.PUBLISHED, ArticleStatus.DRAFT, ArticleStatus.REJECTED),
            ArticleStatus.PUBLISHED, Set.of(ArticleStatus.ARCHIVED),
            ArticleStatus.ARCHIVED, Set.of(ArticleStatus.DRAFT),
            ArticleStatus.REJECTED, Set.of(ArticleStatus.DRAFT));

    /**
     * 建立文章
     *
     * @param authorId 作者資料庫主鍵
     * @param request  建立文章請求
     * @return 建立後的文章完整資訊
     */
    @Override
    @Transactional
    public ArticleResponse createArticle(Long authorId, CreateArticleRequest request) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(authorId);
        article.setTitle(request.getTitle());
        article.setContent(request.getContent());
        article.setSummary(request.getSummary());
        article.setSlug(generateSlug(request.getTitle()));
        article.setStatus(request.getStatus() != null ? request.getStatus() : ArticleStatus.DRAFT);
        article.setViewCount(0L);
        article.setLikeCount(0L);
        article.setCommentCount(0);

        Article saved = articleRepository.save(article);
        return toResponse(saved);
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
    @Transactional
    public ArticleResponse updateArticle(Long operatorId, Role operatorRole, UUID articleUuid,
            UpdateArticleRequest request) {
        Article article = findByUuidOrThrow(articleUuid);
        checkWritePermission(operatorId, operatorRole, article);

        if (request.getTitle() != null) {
            article.setTitle(request.getTitle());
            article.setSlug(generateSlug(request.getTitle()));
        }
        if (request.getContent() != null) {
            article.setContent(request.getContent());
        }
        if (request.getSummary() != null) {
            article.setSummary(request.getSummary());
        }
        if (request.getStatus() != null) {
            validateStatusTransition(article.getStatus(), request.getStatus(), operatorRole);
            article.setStatus(request.getStatus());
            if (request.getStatus() == ArticleStatus.PUBLISHED && article.getPublishedAt() == null) {
                article.setPublishedAt(LocalDateTime.now());
            }
        }

        Article updated = articleRepository.save(article);
        return toResponse(updated);
    }

    /**
     * 刪除文章
     *
     * @param operatorId   操作者資料庫主鍵
     * @param operatorRole 操作者角色
     * @param articleUuid  文章公開 UUID
     */
    @Override
    @Transactional
    public void deleteArticle(Long operatorId, Role operatorRole, UUID articleUuid) {
        Article article = findByUuidOrThrow(articleUuid);
        checkWritePermission(operatorId, operatorRole, article);
        articleRepository.delete(article);
    }

    /**
     * 根據 UUID 取得文章詳情
     *
     * @param articleUuid 文章公開 UUID
     * @param viewerId    觀看者 ID（匿名為 null）
     * @param viewerRole  觀看者角色（匿名為 null）
     * @return 文章完整資訊
     */
    @Override
    @Transactional
    public ArticleResponse getArticleByUuid(UUID articleUuid, Long viewerId, Role viewerRole) {
        Article article = findByUuidOrThrow(articleUuid);

        boolean isAdmin = Role.ADMIN == viewerRole;
        boolean isAuthor = article.getAuthorId().equals(viewerId);
        boolean isPublished = article.getStatus().isPubliclyVisible();

        if (!isPublished && !isAdmin && !isAuthor) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
        }

        /* 增加瀏覽次數（原子性） */
        if (isPublished) {
            articleMapper.incrementViewCount(article.getId());
            article.setViewCount(article.getViewCount() + 1);
        }

        return toResponse(article);
    }

    /**
     * 分頁取得已發布文章列表
     *
     * @param pageNum  頁碼（從 1 開始）
     * @param pageSize 每頁筆數
     * @return 分頁文章摘要列表
     */
    @Override
    public PageResult<ArticleSummaryResponse> getPublishedArticles(int pageNum, int pageSize) {
        long offset = (long) (pageNum - 1) * pageSize;
        List<Article> articles = articleMapper.findPublishedPage(offset, pageSize);
        long total = articleMapper.countPublished();
        List<ArticleSummaryResponse> list = articles.stream().map(this::toSummaryResponse).collect(Collectors.toList());
        return PageResult.of(pageNum, pageSize, total, list);
    }

    /**
     * 分頁取得當前登入用戶的文章列表
     *
     * @param authorId 作者資料庫主鍵
     * @param pageNum  頁碼（從 1 開始）
     * @param pageSize 每頁筆數
     * @return 分頁文章摘要列表
     */
    @Override
    public PageResult<ArticleSummaryResponse> getMyArticles(Long authorId, int pageNum, int pageSize) {
        long offset = (long) (pageNum - 1) * pageSize;
        List<Article> articles = articleMapper.findByAuthorIdPaged(authorId, offset, pageSize);
        long total = articleMapper.countByAuthorId(authorId);
        List<ArticleSummaryResponse> list = articles.stream().map(this::toSummaryResponse).collect(Collectors.toList());
        return PageResult.of(pageNum, pageSize, total, list);
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
    @Transactional
    public ArticleResponse publishArticle(Long operatorId, Role operatorRole, UUID articleUuid) {
        Article article = findByUuidOrThrow(articleUuid);
        checkWritePermission(operatorId, operatorRole, article);
        validateStatusTransition(article.getStatus(), ArticleStatus.PUBLISHED, operatorRole);

        article.setStatus(ArticleStatus.PUBLISHED);
        if (article.getPublishedAt() == null) {
            article.setPublishedAt(LocalDateTime.now());
        }

        Article updated = articleRepository.save(article);

        ArticlePublishedEvent event = new ArticlePublishedEvent(
                updated.getUuid(), updated.getAuthorId(), updated.getTitle(), updated.getPublishedAt());
        rabbitTemplate.convertAndSend(ArticleRabbitMqConfig.EXCHANGE, ArticleRabbitMqConfig.ROUTING_KEY_PUBLISHED, event);

        return toResponse(updated);
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
        if (operatorRole != Role.ADMIN) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_ACCESS_DENIED);
        }

        Article article = findByUuidOrThrow(articleUuid);
        validateStatusTransition(article.getStatus(), ArticleStatus.REJECTED, operatorRole);

        article.setStatus(ArticleStatus.REJECTED);
        log.info("文章 {} 已被駁回，原因：{}", articleUuid, reason);

        Article updated = articleRepository.save(article);
        return toResponse(updated);
    }

    /**
     * 分頁取得待審文章列表（僅 ADMIN）
     *
     * @param pageNum  頁碼（從 1 開始）
     * @param pageSize 每頁筆數
     * @return 分頁文章摘要列表
     */
    @Override
    public PageResult<ArticleSummaryResponse> getPendingArticles(int pageNum, int pageSize) {
        long offset = (long) (pageNum - 1) * pageSize;
        List<Article> articles = articleMapper.findPendingReviewPage(offset, pageSize);
        long total = articleMapper.countPendingReview();
        List<ArticleSummaryResponse> list = articles.stream().map(this::toSummaryResponse).collect(Collectors.toList());
        return PageResult.of(pageNum, pageSize, total, list);
    }

    /**
     * 根據 UUID 查詢文章，不存在則拋出例外
     *
     * @param uuid 文章 UUID
     * @return 文章實體
     */
    private Article findByUuidOrThrow(UUID uuid) {
        return articleRepository.findByUuid(uuid)
                .orElseThrow(() -> new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND));
    }

    /**
     * 檢查寫入權限：ADMIN 可操作任何文章，AUTHOR 只能操作自己的
     *
     * @param operatorId   操作者 ID
     * @param operatorRole 操作者角色
     * @param article      目標文章
     */
    private void checkWritePermission(Long operatorId, Role operatorRole, Article article) {
        if (operatorRole == Role.ADMIN) {
            return;
        }
        if (!article.getAuthorId().equals(operatorId)) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_ACCESS_DENIED);
        }
    }

    /**
     * 驗證狀態轉換是否合法
     *
     * <p>
     * PENDING_REVIEW → PUBLISHED 僅 ADMIN 可執行。
     * </p>
     *
     * @param from         目前狀態
     * @param to           目標狀態
     * @param operatorRole 操作者角色
     */
    private void validateStatusTransition(ArticleStatus from, ArticleStatus to, Role operatorRole) {
        Set<ArticleStatus> allowed = VALID_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID);
        }
        /** PENDING_REVIEW → PUBLISHED / REJECTED 需要 ADMIN 權限 */
        if (from == ArticleStatus.PENDING_REVIEW
                && (to == ArticleStatus.PUBLISHED || to == ArticleStatus.REJECTED)
                && operatorRole != Role.ADMIN) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_ACCESS_DENIED);
        }
    }

    /**
     * 生成 URL slug（基於標題，去除特殊字元）
     *
     * @param title 文章標題
     * @return slug 字串
     */
    private String generateSlug(String title) {
        String base = title.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-");
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 取得作者 UUID，查無結果時回傳 null
     *
     * @param authorId 作者 DB ID
     * @return 作者 UUID
     */
    private UUID resolveAuthorUuid(Long authorId) {
        return userFacade.getUserUuidById(authorId).orElse(null);
    }

    /**
     * 取得作者暱稱，查無結果時回傳 null
     *
     * @param authorId 作者 DB ID
     * @return 作者暱稱
     */
    private String resolveAuthorNickname(Long authorId) {
        return userFacade.getUserNicknameById(authorId).orElse(null);
    }

    /**
     * 轉換文章實體為完整回應 DTO
     *
     * @param article 文章實體
     * @return ArticleResponse
     */
    private ArticleResponse toResponse(Article article) {
        return ArticleResponse.builder()
                .uuid(article.getUuid())
                .title(article.getTitle())
                .content(article.getContent())
                .summary(article.getSummary())
                .coverImageUrl(article.getCoverImageUrl())
                .authorUuid(resolveAuthorUuid(article.getAuthorId()))
                .authorNickname(resolveAuthorNickname(article.getAuthorId()))
                .status(article.getStatus())
                .viewCount(article.getViewCount())
                .createdAt(article.getCreatedAt())
                .updatedAt(article.getUpdatedAt())
                .build();
    }

    /**
     * 轉換文章實體為摘要回應 DTO
     *
     * @param article 文章實體
     * @return ArticleSummaryResponse
     */
    private ArticleSummaryResponse toSummaryResponse(Article article) {
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
                .build();
    }
}
