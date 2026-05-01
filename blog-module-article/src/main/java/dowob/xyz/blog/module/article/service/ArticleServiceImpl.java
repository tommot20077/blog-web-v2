package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleTagEvent;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.module.article.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.article.event.ArticleUpdatedEvent;
import dowob.xyz.blog.module.article.event.ArticleViewedEvent;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.CategoryMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.Category;
import dowob.xyz.blog.module.article.model.CategoryWithArticleId;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateArticleRequest;
import dowob.xyz.blog.module.article.model.TagWithArticleUuid;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.model.dto.response.CategoryResponse;
import dowob.xyz.blog.module.article.model.dto.response.EditorArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.TagSummaryResponse;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
     * 瀏覽計數服務（讀取 DB + Redis 合計瀏覽數）
     */
    private final ViewCountService viewCountService;

    /**
     * Redis 字串操作模板（用於瀏覽數防刷原子性設值）
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 分類 Mapper（同步 article_categories + 查詢分類）
     */
    private final CategoryMapper categoryMapper;

    /**
     * 分類 Repository（根據 UUID 查詢分類實體）
     */
    private final CategoryRepository categoryRepository;

    /**
     * 標籤 Facade（跨模組標籤操作）
     */
    private final TagFacade tagFacade;

    /** Spring 宣告式事務模板（用於縮小事務範圍，避免 MQ 在 transaction 內發送） */
    private final TransactionTemplate transactionTemplate;

    /**
     * Redis 防刷 Key 前綴
     */
    private static final String VIEW_KEY_PREFIX = "view:";

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
    public EditorArticleResponse createArticle(Long authorId, CreateArticleRequest request) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(authorId);
        article.setTitle(request.getTitle());
        article.setContent(request.getContent());
        article.setContentHtml(convertToHtml(request.getContent()));
        article.setSummary(extractSummary(request.getContent(), request.getSummary()));
        article.setSlug(generateSlug(request.getTitle()));
        // 建立文章時狀態一律強制為 DRAFT，防止用戶繞過審核流程直接發布
        article.setStatus(ArticleStatus.DRAFT);
        article.setViewCount(0L);
        article.setLikeCount(0L);
        article.setCommentCount(0);
        if (request.getCoverImageUrl() != null) {
            article.setCoverImageUrl(request.getCoverImageUrl());
        }

        /**
         * DB 操作（save + syncTags）在同一個 transaction 內。
         * 回傳 Object[] 以同時取得 saved Article 與 tagInfos。
         */
        Object[] txResult = transactionTemplate.execute(status -> {
            Article saved = articleRepository.save(article);

            /** 同步文章分類 */
            if (request.getCategoryIds() != null && !request.getCategoryIds().isEmpty()) {
                syncCategories(saved.getId(), request.getCategoryIds());
            }

            /** 同步文章標籤 */
            List<TagInfo> tags = List.of();
            if (request.getTagNames() != null && !request.getTagNames().isEmpty()) {
                tags = tagFacade.findOrCreateTags(request.getTagNames());
                List<UUID> tagIds = tags.stream().map(TagInfo::id).collect(Collectors.toList());
                tagFacade.syncArticleTags(saved.getUuid(), tagIds);
            }
            return new Object[]{saved, tags};
        });

        Article saved = (Article) txResult[0];
        @SuppressWarnings("unchecked")
        List<TagInfo> tagInfos = (List<TagInfo>) txResult[1];

        /** DB 已 commit，best-effort 發送標籤事件 MQ（失敗不影響建立結果） */
        if (tagInfos != null && !tagInfos.isEmpty()) {
            try {
                List<UUID> tagIds = tagInfos.stream().map(TagInfo::id).toList();
                rabbitTemplate.convertAndSend(
                        ArticleRabbitMqConfig.EXCHANGE,
                        ArticleRabbitMqConfig.ROUTING_KEY_TAGGED,
                        new ArticleTagEvent(saved.getUuid(), tagIds));
            } catch (Exception e) {
                log.warn("標籤事件 MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
            }
        }

        return toEditorResponse(saved);
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
        Article article = findByUuidOrThrow(articleUuid);
        checkWritePermission(operatorId, operatorRole, article);

        // 狀態守衛：只有 DRAFT 或 REJECTED 允許透過 PUT 編輯內容
        ArticleStatus currentStatus = article.getStatus();
        if (currentStatus != ArticleStatus.DRAFT && currentStatus != ArticleStatus.REJECTED) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_EDIT_NOT_ALLOWED);
        }

        if (request.getTitle() != null) {
            article.setTitle(request.getTitle());
            article.setSlug(generateSlug(request.getTitle()));
        }
        if (request.getContent() != null) {
            article.setContent(request.getContent());
            article.setContentHtml(convertToHtml(request.getContent()));
        }
        if (request.getSummary() != null) {
            String baseContent = request.getContent() != null ? request.getContent() : article.getContent();
            article.setSummary(extractSummary(baseContent, request.getSummary()));
        }
        if (request.getStatus() != null) {
            validateStatusTransition(article.getStatus(), request.getStatus(), operatorRole);
            article.setStatus(request.getStatus());
            if (request.getStatus() == ArticleStatus.PUBLISHED && article.getPublishedAt() == null) {
                article.setPublishedAt(LocalDateTime.now());
            }
        }
        if (request.getCoverImageUrl() != null) {
            article.setCoverImageUrl(request.getCoverImageUrl());
        }

        /** DB 操作（save + syncCategories + syncTags）在同一個 transaction 內 */
        Object[] txResult = transactionTemplate.execute(status -> {
            Article updated;
            try {
                updated = articleRepository.save(article);
            } catch (org.springframework.dao.OptimisticLockingFailureException e) {
                throw new BusinessException(ArticleErrorCode.ARTICLE_CONCURRENT_UPDATE);
            }

            /** 同步文章分類（null 表示不更新，空列表表示清除） */
            if (request.getCategoryIds() != null) {
                syncCategories(updated.getId(), request.getCategoryIds());
            }

            /** 同步文章標籤（null 表示不更新，空列表表示清除所有標籤） */
            List<TagInfo> tags = List.of();
            if (request.getTagNames() != null) {
                if (request.getTagNames().isEmpty()) {
                    tagFacade.deleteArticleTags(updated.getUuid());
                } else {
                    tags = tagFacade.findOrCreateTags(request.getTagNames());
                    List<UUID> tagIds = tags.stream().map(TagInfo::id).collect(Collectors.toList());
                    tagFacade.syncArticleTags(updated.getUuid(), tagIds);
                }
            }
            return new Object[]{updated, tags};
        });

        Article updated = (Article) txResult[0];
        @SuppressWarnings("unchecked")
        List<TagInfo> tagInfos = (List<TagInfo>) txResult[1];

        /** DB 已 commit，best-effort 發送更新事件 MQ */
        if (updated.getStatus() == ArticleStatus.PUBLISHED) {
            publishUpdatedEvent(updated);
        }

        /** DB 已 commit，best-effort 發送標籤事件 MQ */
        if (tagInfos != null && !tagInfos.isEmpty()) {
            try {
                List<UUID> tagIds = tagInfos.stream().map(TagInfo::id).toList();
                rabbitTemplate.convertAndSend(
                        ArticleRabbitMqConfig.EXCHANGE,
                        ArticleRabbitMqConfig.ROUTING_KEY_TAGGED,
                        new ArticleTagEvent(updated.getUuid(), tagIds));
            } catch (Exception e) {
                log.warn("標籤事件 MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
            }
        }

        return toEditorResponse(updated);
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
        Article article = findByUuidOrThrow(articleUuid);
        checkWritePermission(operatorId, operatorRole, article);

        /** DB 刪除在 transaction 內 */
        transactionTemplate.executeWithoutResult(status -> articleRepository.delete(article));

        /** DB 已 commit，best-effort 發送刪除事件 MQ（失敗不影響刪除結果） */
        try {
            rabbitTemplate.convertAndSend(
                    ArticleRabbitMqConfig.EXCHANGE,
                    ArticleRabbitMqConfig.ROUTING_KEY_DELETED,
                    new ArticleDeletedEvent(article.getUuid(), Instant.now()));
        } catch (Exception e) {
            log.warn("MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
        }
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
        Article article = findByUuidOrThrow(articleUuid);
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
        Article article = findByUuidOrThrow(articleUuid);
        if (!article.getAuthorId().equals(requesterId)) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_ACCESS_DENIED);
        }
        return toEditorResponse(article);
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
        if (isPublished) {
            String viewKey = VIEW_KEY_PREFIX + article.getUuid() + ":" + clientIp;
            Boolean firstVisit = stringRedisTemplate.opsForValue()
                    .setIfAbsent(viewKey, "1", 5, TimeUnit.MINUTES);
            if (Boolean.TRUE.equals(firstVisit)) {
                rabbitTemplate.convertAndSend(
                        ArticleRabbitMqConfig.EXCHANGE,
                        ArticleRabbitMqConfig.ROUTING_KEY_VIEWED,
                        new ArticleViewedEvent(article.getUuid(), Instant.now()));
            }
        }

        return toResponse(article);
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
        Map<UUID, List<TagSummaryResponse>> tagMap = batchToTagResponsesMap(uuids);
        List<ArticleSummaryResponse> list = articles.stream()
                .map(a -> toSummaryResponse(a, tagMap))
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
        Map<UUID, List<TagSummaryResponse>> tagMap = batchToTagResponsesMap(uuids);
        List<ArticleSummaryResponse> list = articles.stream()
                .map(a -> toSummaryResponse(a, tagMap))
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
        Map<UUID, List<TagSummaryResponse>> tagMap = batchToTagResponsesMap(uuids);
        List<ArticleSummaryResponse> list = articles.stream()
                .map(a -> toSummaryResponse(a, tagMap))
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
        Article article = findByUuidOrThrow(articleUuid);
        checkWritePermission(operatorId, operatorRole, article);
        validateStatusTransition(article.getStatus(), ArticleStatus.PUBLISHED, operatorRole);

        article.setStatus(ArticleStatus.PUBLISHED);
        if (article.getPublishedAt() == null) {
            article.setPublishedAt(LocalDateTime.now());
        }

        /** DB 操作（save + 查詢 tags）在同一個 transaction 內 */
        Object[] txResult = transactionTemplate.execute(status -> {
            Article saved = articleRepository.save(article);
            List<TagInfo> tagInfos = articleMapper.findTagsByArticleUuid(saved.getUuid());
            return new Object[]{saved, tagInfos};
        });

        Article saved = (Article) txResult[0];
        @SuppressWarnings("unchecked")
        List<TagInfo> tags = (List<TagInfo>) txResult[1];

        /** DB 已 commit，best-effort 發送發布事件 MQ（失敗不影響發布結果） */
        try {
            ArticlePublishedEvent event = new ArticlePublishedEvent(
                    saved.getUuid(),
                    saved.getAuthorId(),
                    saved.getTitle(),
                    saved.getPublishedAt(),
                    saved.getSlug(),
                    saved.getSummary(),
                    stripMarkdown(saved.getContent()),
                    userFacade.getUserUsernameById(saved.getAuthorId()).orElse(null),
                    resolveAuthorNickname(saved.getAuthorId()),
                    tags);
            rabbitTemplate.convertAndSend(ArticleRabbitMqConfig.EXCHANGE,
                    ArticleRabbitMqConfig.ROUTING_KEY_PUBLISHED, event);
        } catch (Exception e) {
            log.warn("MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
        }

        /** best-effort 發送標籤事件 MQ */
        if (tags != null && !tags.isEmpty()) {
            try {
                List<UUID> tagIds = tags.stream().map(TagInfo::id).toList();
                rabbitTemplate.convertAndSend(
                        ArticleRabbitMqConfig.EXCHANGE,
                        ArticleRabbitMqConfig.ROUTING_KEY_TAGGED,
                        new ArticleTagEvent(saved.getUuid(), tagIds));
            } catch (Exception e) {
                log.warn("標籤事件 MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
            }
        }

        return toResponse(saved);
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
        article.setRejectReason(reason);

        Article updated = articleRepository.save(article);
        return toResponse(updated);
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
        Map<UUID, List<TagSummaryResponse>> tagMap = batchToTagResponsesMap(uuids);
        List<ArticleSummaryResponse> list = articles.stream()
                .map(a -> toSummaryResponse(a, tagMap))
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
        Article article = findByUuidOrThrow(articleUuid);
        checkWritePermission(operatorId, operatorRole, article);
        validateStatusTransition(article.getStatus(), ArticleStatus.PENDING_REVIEW, operatorRole);
        article.setStatus(ArticleStatus.PENDING_REVIEW);
        Article updated = articleRepository.save(article);
        return toResponse(updated);
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
     * 去除 Markdown 格式，回傳純文字（供 Elasticsearch 索引）
     *
     * <p>
     * 依序去除：程式碼區塊、行內程式碼、標題符號、粗體/斜體、連結、圖片、水平線，
     * 最後收合多餘空白。
     * </p>
     *
     * @param markdown Markdown 原文
     * @return 純文字內容
     */
    private String stripMarkdown(String markdown) {
        if (markdown == null)
            return "";
        return markdown
                .replaceAll("```[\\s\\S]*?```", "")
                .replaceAll("`[^`]*`", "")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("\\*{1,2}([^*]+)\\*{1,2}", "$1")
                .replaceAll("_{1,2}([^_]+)_{1,2}", "$1")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", "")
                .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
                .replaceAll("(?m)^[-*_]{3,}$", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
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
     * 將 Markdown 轉換為 HTML 字串
     *
     * @param markdown Markdown 原文
     * @return HTML 字串
     */
    private String convertToHtml(String markdown) {
        if (markdown == null) {
            return null;
        }
        MutableDataSet options = new MutableDataSet();
        options.set(HtmlRenderer.ESCAPE_HTML, true);
        options.set(HtmlRenderer.SUPPRESS_HTML, true);
        Parser parser = Parser.builder(options).build();
        Node document = parser.parse(markdown);
        HtmlRenderer renderer = HtmlRenderer.builder(options).build();
        return renderer.render(document);
    }

    /**
     * 自動擷取摘要
     *
     * <p>
     * summary 非空白時直接回傳；空白則從 Markdown 取純文字前 200 字。
     * </p>
     *
     * @param content Markdown 內容
     * @param summary 原始摘要（可能為空）
     * @return 摘要字串
     */
    private String extractSummary(String content, String summary) {
        if (summary != null && !summary.isBlank()) {
            return summary;
        }
        if (content == null) {
            return null;
        }
        Parser parser = Parser.builder().build();
        Node document = parser.parse(content);
        com.vladsch.flexmark.util.ast.TextCollectingVisitor visitor =
                new com.vladsch.flexmark.util.ast.TextCollectingVisitor();
        String plainText = visitor.collectAndGetText(document);
        return plainText.substring(0, Math.min(200, plainText.length()));
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
                .contentHtml(article.getContentHtml())
                .summary(article.getSummary())
                .coverImageUrl(article.getCoverImageUrl())
                .authorUuid(resolveAuthorUuid(article.getAuthorId()))
                .authorNickname(resolveAuthorNickname(article.getAuthorId()))
                .status(article.getStatus())
                .viewCount(viewCountService.getViewCount(article.getUuid()))
                .createdAt(article.getCreatedAt())
                .updatedAt(article.getUpdatedAt())
                .categories(toCategoryResponses(article.getId()))
                .slug(article.getSlug())
                .likeCount(article.getLikeCount())
                .commentCount(article.getCommentCount())
                .publishedAt(article.getPublishedAt())
                .tags(toTagSummaryResponses(article.getUuid()))
                .rejectReason(article.getRejectReason())
                .build();
    }

    /**
     * 轉換文章實體為 Editor 專用回應 DTO
     *
     * <p>
     * 僅包含編輯器所需欄位，不含 contentHtml、slug、viewCount 等閱讀端欄位。
     * </p>
     *
     * @param article 文章實體
     * @return EditorArticleResponse
     */
    private EditorArticleResponse toEditorResponse(Article article) {
        return EditorArticleResponse.builder()
                .uuid(article.getUuid())
                .title(article.getTitle())
                .summary(article.getSummary())
                .content(article.getContent())
                .coverImageUrl(article.getCoverImageUrl())
                .status(article.getStatus())
                .categories(toCategoryResponses(article.getId()))
                .tags(toTagSummaryResponses(article.getUuid()))
                .rejectReason(article.getRejectReason())
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
    private ArticleSummaryResponse toSummaryResponse(Article article, Map<UUID, List<TagSummaryResponse>> tagMap) {
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
                .tags(tagMap.getOrDefault(article.getUuid(), List.of()))
                .rejectReason(article.getRejectReason())
                .build();
    }

    /**
     * 同步文章分類關聯
     *
     * <p>
     * 先刪除文章現有所有分類關聯，再根據傳入的 UUID 列表重新建立。
     * 空列表表示清除所有分類，null 由呼叫端決定是否進入此方法。
     * </p>
     *
     * @param articleId   文章資料庫主鍵
     * @param categoryIds 分類 UUID 列表（空列表表示清除所有）
     */
    private void syncCategories(Long articleId, List<UUID> categoryIds) {
        categoryMapper.deleteArticleCategoriesByArticleId(articleId);
        if (categoryIds != null && !categoryIds.isEmpty()) {
            for (UUID categoryUuid : categoryIds) {
                Category category = categoryRepository.findByUuid(categoryUuid)
                        .orElseThrow(() -> new BusinessException(ArticleErrorCode.CATEGORY_NOT_FOUND));
                categoryMapper.insertArticleCategory(articleId, category.getId());
            }
        }
    }

    /**
     * 查詢單篇文章標籤並轉換為 TagSummaryResponse 列表
     *
     * <p>
     * 供 {@link #toResponse(Article)} 單篇文章使用，單次查詢即可。
     * 注意：{@link dowob.xyz.blog.infrastructure.event.TagInfo} 是 Record，需使用 id()、name()、slug() 方法。
     * </p>
     *
     * @param articleUuid 文章公開 UUID
     * @return 標籤摘要回應列表
     */
    private List<TagSummaryResponse> toTagSummaryResponses(UUID articleUuid) {
        return articleMapper.findTagsByArticleUuid(articleUuid).stream()
                .map(tag -> TagSummaryResponse.builder()
                        .id(tag.id())
                        .name(tag.name())
                        .slug(tag.slug())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 批次查詢多篇文章標籤並建立文章 UUID → TagSummaryResponse 列表的對應 Map
     *
     * <p>
     * 供列表場景使用，一次查詢避免 N+1 問題。
     * </p>
     *
     * @param articleUuids 文章公開 UUID 列表
     * @return Map&lt;articleUuid, 標籤摘要回應列表&gt;
     */
    private Map<UUID, List<TagSummaryResponse>> batchToTagResponsesMap(List<UUID> articleUuids) {
        if (articleUuids == null || articleUuids.isEmpty()) return Map.of();
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

    /**
     * 查詢文章分類並轉換為 Response（委派批次查詢，支援單篇使用）
     *
     * @param articleId 文章資料庫主鍵
     * @return 分類回應列表
     */
    private List<CategoryResponse> toCategoryResponses(Long articleId) {
        if (articleId == null)
            return List.of();
        return batchToCategoryResponsesMap(List.of(articleId))
                .getOrDefault(articleId, List.of());
    }

    /**
     * 批次查詢多篇文章分類並轉換為 Map（供列表場景使用，避免 N+1）
     *
     * @param articleIds 文章資料庫主鍵列表
     * @return Map&lt;articleId, 分類回應列表&gt;
     */
    private Map<Long, List<CategoryResponse>> batchToCategoryResponsesMap(List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty())
            return Map.of();
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
     * 發送文章更新事件至 RabbitMQ
     *
     * <p>
     * 供已發布文章內容修改後通知搜尋模組同步索引。
     * </p>
     *
     * @param article 更新後的文章實體
     */
    private void publishUpdatedEvent(Article article) {
        try {
            List<TagInfo> tags = articleMapper.findTagsByArticleUuid(article.getUuid());
            ArticleUpdatedEvent event = new ArticleUpdatedEvent(
                    article.getUuid(),
                    article.getAuthorId(),
                    article.getTitle(),
                    article.getPublishedAt(),
                    article.getSlug(),
                    article.getSummary(),
                    stripMarkdown(article.getContent()),
                    userFacade.getUserUsernameById(article.getAuthorId()).orElse(null),
                    resolveAuthorNickname(article.getAuthorId()),
                    tags);
            rabbitTemplate.convertAndSend(
                    ArticleRabbitMqConfig.EXCHANGE,
                    ArticleRabbitMqConfig.ROUTING_KEY_UPDATED,
                    event);
        } catch (Exception e) {
            log.warn("MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
        }
    }
}
