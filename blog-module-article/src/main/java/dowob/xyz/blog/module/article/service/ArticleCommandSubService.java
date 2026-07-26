package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.CategoryMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.Category;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.EditorArticleResponse;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
class ArticleCommandSubService {

    private final ArticleRepository articleRepository;
    private final ArticleMapper articleMapper;
    private final ArticleEventPublisher articleEventPublisher;
    private final CategoryMapper categoryMapper;
    private final CategoryRepository categoryRepository;
    private final TagFacade tagFacade;
    /** 檔案模組 Facade，供文章儲存後掃描 content 回填「檔案 → 文章」綁定（Task B6） */
    private final FileFacade fileFacade;
    private final ArticleMarkdownRenderer markdownRenderer;
    private final TransactionTemplate transactionTemplate;
    private final ArticleEntityFinder entityFinder;
    private final ArticleResponseMapper articleResponseMapper;

    /**
     * 內文檔案連結格式：{@code /api/v1/files/{uuid}/content}（見 spec §3.1 相對路徑決策）。
     * 只匹配標準 UUID 格式（8-4-4-4-12 hex），格式不符者天然不會被擷取，等同安靜略過。
     */
    private static final Pattern FILE_CONTENT_URL_PATTERN = Pattern.compile(
            "/api/v1/files/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/content");

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
    public EditorArticleResponse createArticle(Long authorId, CreateArticleRequest request) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(authorId);
        article.setTitle(request.getTitle());
        article.setContent(request.getContent());
        article.setContentHtml(markdownRenderer.render(request.getContent()));
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
            List<UUID> tagIds = tagInfos.stream().map(TagInfo::id).toList();
            articleEventPublisher.publishTagged(saved, tagIds);
        }

        /** DB 已 commit，best-effort 回填「檔案 → 文章」綁定（Task B6，失敗不影響建立結果） */
        bindFilesToArticleSafely(saved.getUuid(), saved.getContent());

        return articleResponseMapper.toEditorResponse(saved);
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
    public EditorArticleResponse updateArticle(Long operatorId, Role operatorRole, UUID articleUuid,
            UpdateArticleRequest request) {
        Article article = entityFinder.findByUuidOrThrow(articleUuid);
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
            article.setContentHtml(markdownRenderer.render(request.getContent()));
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
            articleEventPublisher.publishUpdated(updated);
        }

        /** best-effort 發送 ContentChanged(SAVED) MQ（任何狀態下都發，供 version 模組觸發快照） */
        articleEventPublisher.publishContentChanged(updated, ArticleContentChangedEvent.Action.SAVED);

        /** DB 已 commit，best-effort 發送標籤事件 MQ */
        if (tagInfos != null && !tagInfos.isEmpty()) {
            List<UUID> tagIds = tagInfos.stream().map(TagInfo::id).toList();
            articleEventPublisher.publishTagged(updated, tagIds);
        }

        /** DB 已 commit，best-effort 回填「檔案 → 文章」綁定（Task B6，失敗不影響更新結果） */
        bindFilesToArticleSafely(updated.getUuid(), updated.getContent());

        return articleResponseMapper.toEditorResponse(updated);
    }

    /**
     * 刪除文章
     *
     * @param operatorId   操作者資料庫主鍵
     * @param operatorRole 操作者角色
     * @param articleUuid  文章公開 UUID
     */
    public void deleteArticle(Long operatorId, Role operatorRole, UUID articleUuid) {
        Article article = entityFinder.findByUuidOrThrow(articleUuid);
        checkWritePermission(operatorId, operatorRole, article);

        /* delete 前讀取：article 刪除後 FK CASCADE 會清 article_tags / article_categories，
         * series consumer 也撈不到。所以要在 delete 前撈 + 包進 ArticleDeletedEvent payload。*/
        Long seriesId = article.getSeriesId();
        List<UUID> categoryIds = articleMapper.findCategoryUuidsByArticleId(article.getId());
        List<UUID> tagIds = articleMapper.findTagUuidsByArticleId(article.getId());

        /* DB 刪除（含 FK CASCADE 自動清 article_versions / article_tags / article_categories）*/
        transactionTemplate.executeWithoutResult(status -> {
            articleRepository.delete(article);
            /*
             * SP-A: 移除 seriesFacade.notifyArticleDeletedFromSeries(seriesId)
             * 改由 SeriesArticleDeletedConsumer 訂閱 ArticleDeletedEvent 處理（冪等）。
             * ArticleServiceImpl 不再依賴 SeriesFacade interface。
             */
        });

        /* DB 已 commit，best-effort 發送 ArticleDeletedEvent（rich payload）*/
        articleEventPublisher.publishDeleted(article, seriesId, categoryIds, tagIds);
    }

    /**
     * 發布文章
     *
     * @param operatorId   操作者資料庫主鍵
     * @param operatorRole 操作者角色
     * @param articleUuid  文章公開 UUID
     * @return 發布後的文章完整資訊
     */
    public ArticleResponse publishArticle(Long operatorId, Role operatorRole, UUID articleUuid) {
        Article article = entityFinder.findByUuidOrThrow(articleUuid);
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
        articleEventPublisher.publishPublished(saved, tags);

        /** best-effort 發送 ContentChanged(PUBLISHED) MQ（供 version 模組觸發快照） */
        articleEventPublisher.publishContentChanged(saved, ArticleContentChangedEvent.Action.PUBLISHED);

        /** best-effort 發送標籤事件 MQ */
        if (tags != null && !tags.isEmpty()) {
            List<UUID> tagIds = tags.stream().map(TagInfo::id).toList();
            articleEventPublisher.publishTagged(saved, tagIds);
        }

        return articleResponseMapper.toResponse(saved);
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
    @Transactional
    public ArticleResponse rejectArticle(Long operatorId, Role operatorRole, UUID articleUuid, String reason) {
        if (operatorRole != Role.ADMIN) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_ACCESS_DENIED);
        }

        Article article = entityFinder.findByUuidOrThrow(articleUuid);
        validateStatusTransition(article.getStatus(), ArticleStatus.REJECTED, operatorRole);

        article.setStatus(ArticleStatus.REJECTED);
        log.info("文章 {} 已被駁回，原因：{}", articleUuid, reason);
        article.setRejectReason(reason);

        Article updated = articleRepository.save(article);
        return articleResponseMapper.toResponse(updated);
    }

    /**
     * 提交文章審核（DRAFT → PENDING_REVIEW）
     *
     * @param operatorId   操作者資料庫主鍵
     * @param operatorRole 操作者角色
     * @param articleUuid  文章公開 UUID
     * @return 提交審核後的文章完整資訊
     */
    @Transactional
    public ArticleResponse submitForReview(Long operatorId, Role operatorRole, UUID articleUuid) {
        Article article = entityFinder.findByUuidOrThrow(articleUuid);
        checkWritePermission(operatorId, operatorRole, article);
        ArticleStatus currentStatus = article.getStatus();
        if (currentStatus != ArticleStatus.DRAFT && currentStatus != ArticleStatus.REJECTED) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID);
        }
        article.setStatus(ArticleStatus.PENDING_REVIEW);
        article.setRejectReason(null);
        Article updated = articleRepository.save(article);
        return articleResponseMapper.toResponse(updated);
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
     * 自動擷取摘要。
     *
     * <p>summary 非空白時直接回傳；空白則透過 {@link ArticleMarkdownRenderer#toPlainText(String)}
     * 取 Markdown 純文字前 200 字。</p>
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
        String plainText = markdownRenderer.toPlainText(content);
        if (plainText == null) {
            return null;
        }
        return plainText.substring(0, Math.min(200, plainText.length()));
    }

    /**
     * 掃描文章內文，回填「檔案 → 文章」綁定（Task B6）。
     *
     * <p>
     * 上傳圖片當下文章可能尚未存在（使用者開新文章、還沒儲存就貼圖），因此僅靠上傳時綁定
     * 不足夠——這類「先貼圖、後存檔」的檔案會永遠停在未綁定狀態，而未綁定 = 私有（fail-safe），
     * 導致文章發布後讀者看不到圖。文章儲存時必須重新掃描 content 並回填完整清單。
     * </p>
     *
     * <p>
     * <strong>穩健性</strong>：內文是使用者輸入，格式錯誤或不存在的 uuid 一律安靜略過，
     * 絕不可讓文章儲存因此失敗。{@link FileFacade#bindFilesToArticle} 拋出的任何例外
     * 皆視為 best-effort 失敗，僅記錄警告，不往外傳播——文章內容已經存好，綁定只是附帶動作，
     * 不應讓使用者因為這個非核心步驟而遺失剛才的編輯內容。
     * </p>
     *
     * <p>
     * <strong>交易邊界</strong>：呼叫時機在呼叫端的 {@code transactionTemplate.execute(...)}
     * 回傳（即 DB 交易已 commit）之後，比照 code-standards「Transaction + MQ 時序」規範——
     * 不在交易作用域內對外（跨模組）呼叫，避免交易未提交卻已產生外部副作用，也避免在對外
     * 呼叫期間持有本模組的 DB 連線。
     * </p>
     *
     * @param articleUuid 文章公開 UUID
     * @param content     文章目前的 Markdown 內文（可能為 null）
     */
    private void bindFilesToArticleSafely(UUID articleUuid, String content) {
        List<UUID> fileUuids = extractFileUuids(content);
        try {
            fileFacade.bindFilesToArticle(articleUuid, fileUuids);
        } catch (Exception e) {
            log.warn("檔案綁定回填失敗（best-effort，不影響文章儲存）：articleUuid={}, error={}",
                    articleUuid, e.getMessage(), e);
        }
    }

    /**
     * 從 Markdown 內文中擷取所有 {@code /api/v1/files/{uuid}/content} 連結的 fileUuid。
     *
     * <p>
     * 格式錯誤的 uuid（不符合標準 8-4-4-4-12 hex 格式）不會被正則比對到，等同安靜略過；
     * 重複出現的 uuid 會去重（保留首次出現順序），無圖時回傳空清單。
     * </p>
     *
     * @param content Markdown 內文（可能為 null 或空白）
     * @return 內文中出現的檔案 UUID 清單（已去重，可能為空清單，但不為 null）
     */
    private List<UUID> extractFileUuids(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        Set<UUID> fileUuids = new LinkedHashSet<>();
        Matcher matcher = FILE_CONTENT_URL_PATTERN.matcher(content);
        while (matcher.find()) {
            try {
                fileUuids.add(UUID.fromString(matcher.group(1)));
            } catch (IllegalArgumentException e) {
                log.debug("內文含格式錯誤的檔案 UUID，略過：{}", matcher.group(1));
            }
        }
        return new ArrayList<>(fileUuids);
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
     * 原子性遞增文章留言計數
     *
     * @param articleId 文章資料庫主鍵
     */
    public void incrementCommentCount(Long articleId) {
        articleMapper.incrementCommentCount(articleId);
    }

    /**
     * 原子性遞減文章留言計數（守衛 > 0，防 underflow）
     *
     * @param articleId 文章資料庫主鍵
     */
    public void decrementCommentCount(Long articleId) {
        articleMapper.decrementCommentCount(articleId);
    }

    /**
     * 原子性遞增文章按讚計數
     *
     * @param articleId 文章資料庫主鍵
     */
    public void incrementLikeCount(Long articleId) {
        articleMapper.incrementLikeCount(articleId);
    }

    /**
     * 原子性遞減文章按讚計數（守衛 > 0，防 underflow）
     *
     * @param articleId 文章資料庫主鍵
     */
    public void decrementLikeCount(Long articleId) {
        articleMapper.decrementLikeCount(articleId);
    }

    /**
     * 更新文章的 series 歸屬與排序位置。
     *
     * @param articleId      文章資料庫主鍵
     * @param seriesId       所屬 series 主鍵（null 表示解除）
     * @param seriesPosition 在 series 中的排序位置（null 表示解除）
     */
    @Transactional
    public void updateSeriesAssignment(Long articleId, Long seriesId, Integer seriesPosition) {
        articleRepository.findById(articleId).ifPresent(a -> {
            a.setSeriesId(seriesId);
            a.setSeriesPosition(seriesPosition);
            articleRepository.save(a);
        });
    }
}
