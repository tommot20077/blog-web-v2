package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.module.article.event.ArticlePublishedEvent;
import dowob.xyz.blog.module.article.event.ArticleViewedEvent;
import dowob.xyz.blog.module.article.event.TagInfo;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.CategoryMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.model.dto.response.CategoryResponse;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.text.TextContentRenderer;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional
    public ArticleResponse createArticle(Long authorId, CreateArticleRequest request) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(authorId);
        article.setTitle(request.getTitle());
        article.setContent(request.getContent());
        article.setContentHtml(convertToHtml(request.getContent()));
        article.setSummary(extractSummary(request.getContent(), request.getSummary()));
        article.setSlug(generateSlug(request.getTitle()));
        article.setStatus(request.getStatus() != null ? request.getStatus() : ArticleStatus.DRAFT);
        article.setViewCount(0L);
        article.setLikeCount(0L);
        article.setCommentCount(0);

        Article saved = articleRepository.save(article);

        // 同步文章分類
        if (request.getCategoryIds() != null && !request.getCategoryIds().isEmpty()) {
            syncCategories(saved.getId(), request.getCategoryIds());
        }

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

        Article updated = articleRepository.save(article);

        // 同步文章分類（null 表示不更新，空列表表示清除）
        if (request.getCategoryIds() != null) {
            syncCategories(updated.getId(), request.getCategoryIds());
        }

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
     * 根據分類 slug 分頁取得已發布文章列表
     *
     * @param categorySlug 分類 slug
     * @param pageNum      頁碼（從 1 開始）
     * @param pageSize     每頁筆數
     * @return 分頁文章摘要列表
     */
    @Override
    public PageResult<ArticleSummaryResponse> getPublishedArticlesByCategorySlug(
            String categorySlug, int pageNum, int pageSize) {
        long offset = (long) (pageNum - 1) * pageSize;
        List<Article> articles = articleMapper.findPublishedPageByCategorySlug(categorySlug, offset, pageSize);
        long total = articleMapper.countPublishedByCategorySlug(categorySlug);
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

        List<TagInfo> tags = articleMapper.findTagsByArticleId(updated.getId());
        ArticlePublishedEvent event = new ArticlePublishedEvent(
                updated.getUuid(),
                updated.getAuthorId(),
                updated.getTitle(),
                updated.getPublishedAt(),
                updated.getSlug(),
                updated.getSummary(),
                stripMarkdown(updated.getContent()),
                userFacade.getUserUsernameById(updated.getAuthorId()).orElse(null),
                resolveAuthorNickname(updated.getAuthorId()),
                tags);
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
        if (markdown == null) return "";
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
        Parser parser = Parser.builder().build();
        Node document = parser.parse(markdown);
        HtmlRenderer renderer = HtmlRenderer.builder()
                .escapeHtml(true)
                .sanitizeUrls(true)
                .build();
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
        TextContentRenderer textRenderer = TextContentRenderer.builder().build();
        String plainText = textRenderer.render(document);
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
                categoryRepository.findByUuid(categoryUuid)
                        .ifPresent(category -> categoryMapper.insertArticleCategory(articleId, category.getId()));
            }
        }
    }

    /**
     * 查詢文章分類並轉換為 Response
     *
     * @param articleId 文章資料庫主鍵
     * @return 分類回應列表
     */
    private List<CategoryResponse> toCategoryResponses(Long articleId) {
        if (articleId == null) return List.of();
        return categoryMapper.findCategoriesByArticleId(articleId).stream()
                .map(c -> CategoryResponse.builder()
                        .uuid(c.getUuid())
                        .name(c.getName())
                        .slug(c.getSlug())
                        .description(c.getDescription())
                        .sortOrder(c.getSortOrder())
                        .build())
                .collect(Collectors.toList());
    }
}
