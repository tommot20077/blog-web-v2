package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.event.TagInfo;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    /** 文章「檔案 → 文章」綁定回填元件，供文章儲存後掃描 content 回填綁定（Task B6，已抽出見 {@link ArticleFileBinder}） */
    private final ArticleFileBinder articleFileBinder;
    private final ArticleMarkdownRenderer markdownRenderer;
    private final TransactionTemplate transactionTemplate;
    private final ArticleEntityFinder entityFinder;
    private final ArticleResponseMapper articleResponseMapper;

    /**
     * TOC JSON 編解碼器（寫入端序列化，讀取端由 ArticleResponseMapper 反序列化）
     */
    private final ArticleTocCodec articleTocCodec;

    /**
     * 文章狀態機的<b>唯一真相</b>：每個狀態允許轉往哪些狀態。
     *
     * <p>所有會改動 {@code article.status} 的路徑一律經 {@link #validateStatusTransition} 查此表，
     * 不得各自內嵌判斷（SEC-02：內嵌判斷讓 submitForReview / withdraw / restore 各自長出一套規則，
     * 其中 restore 那套等於沒有守衛）。新增狀態時只要漏改此表，
     * {@code ArticleCommandSubServiceTest} 的轉換矩陣測試會立刻紅。</p>
     *
     * <p><b>表與入口的分工</b>：此表定義「狀態機上存在哪些邊」；個別入口可以更嚴
     * （例如 withdraw 只走 PENDING_REVIEW → DRAFT 這一條，不因表裡也有
     * ARCHIVED / REJECTED → DRAFT 就一併開放），但<b>不得更寬</b>。入口自己的限制屬於操作語意，
     * 一律寫在該方法內並註明理由。</p>
     */
    private static final Map<ArticleStatus, Set<ArticleStatus>> VALID_TRANSITIONS = Map.of(
            ArticleStatus.DRAFT, Set.of(ArticleStatus.PUBLISHED, ArticleStatus.PENDING_REVIEW),
            ArticleStatus.PENDING_REVIEW, Set.of(ArticleStatus.PUBLISHED, ArticleStatus.DRAFT, ArticleStatus.REJECTED),
            ArticleStatus.PUBLISHED, Set.of(ArticleStatus.ARCHIVED),
            ArticleStatus.ARCHIVED, Set.of(ArticleStatus.DRAFT),
            /* REJECTED → PENDING_REVIEW：被駁回後改一改重新送審，submitForReview 一直支援，
             * 只是原本沒被寫進表（表是規格、實作是真相，兩者對不上）。SEC-02 收斂時補齊。 */
            ArticleStatus.REJECTED, Set.of(ArticleStatus.DRAFT, ArticleStatus.PENDING_REVIEW));

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
        RenderResult renderResult = markdownRenderer.render(request.getContent());
        article.setContentHtml(renderResult == null ? null : renderResult.html());
        article.setToc(articleTocCodec.serialize(renderResult == null ? null : renderResult.toc()));
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

        /**
         * DB 已 commit，best-effort 發送 ContentChanged(SAVED) MQ（供 version 模組觸發快照）。
         * 建立狀態也是一份可還原的版本；若缺這行，初版內容永遠不會有快照，
         * 作者第一次編輯並儲存後，初版內容就永久遺失（BUG-003）。
         */
        articleEventPublisher.publishContentChanged(saved, ArticleContentChangedEvent.Action.SAVED);

        /** DB 已 commit，best-effort 發送標籤事件 MQ（失敗不影響建立結果） */
        if (tagInfos != null && !tagInfos.isEmpty()) {
            List<UUID> tagIds = tagInfos.stream().map(TagInfo::id).toList();
            articleEventPublisher.publishTagged(saved, tagIds);
        }

        /** DB 已 commit，best-effort 回填「檔案 → 文章」綁定（Task B6，失敗不影響建立結果） */
        articleFileBinder.bindFilesToArticleSafely(saved.getUuid(), saved.getContent());

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

        /*
         * 內容凍結守衛：只有 DRAFT / REJECTED 允許改寫內容，其餘狀態拋 ARTICLE_EDIT_NOT_ALLOWED。
         * 判斷本身已收斂進 ArticleContentFreezePolicy（唯一真相），與版本還原路徑
         * （ArticleFacadeImpl.applyRestoreContent）共用同一份——F-H1：還原原本不受此約束，
         * 作者送審後仍能換掉內容（審核 TOCTOU）；修法若在還原那邊複製一份判斷，
         * 就會再造出 SEC-02 剛收斂掉的「多套真相」。
         */
        ArticleContentFreezePolicy.assertContentEditable(article.getStatus());

        if (request.getTitle() != null) {
            article.setTitle(request.getTitle());
            article.setSlug(generateSlug(request.getTitle()));
        }
        if (request.getContent() != null) {
            article.setContent(request.getContent());
            RenderResult renderResult = markdownRenderer.render(request.getContent());
            article.setContentHtml(renderResult.html());
            article.setToc(articleTocCodec.serialize(renderResult.toc()));
        }
        // request.getContent() 為 null：不重算 TOC，也不覆寫 article 上既有的 toc
        // （entityFinder 載入的 Article 已帶有 DB 既有值，save 時原樣寫回）
        if (request.getSummary() != null) {
            String baseContent = request.getContent() != null ? request.getContent() : article.getContent();
            article.setSummary(extractSummary(baseContent, request.getSummary()));
        }
        if (request.getStatus() != null) {
            /*
             * 入口語意守衛（比狀態機更嚴，見 VALID_TRANSITIONS 的「表與入口的分工」）：
             * PUT 不得把 REJECTED 文章直接送審。REJECTED → PENDING_REVIEW 是合法轉換，
             * 但只有 submitForReview 那條路徑會一併清掉 rejectReason；從 PUT 轉過去，
             * 待審文章會帶著上一輪的駁回理由。此限制是 SEC-02 收斂前就有的行為
             * （REJECTED → PENDING_REVIEW 當時不在表裡），補表後改以顯式守衛保留，
             * 不在安全修復裡夾帶行為放寬。
             */
            if (article.getStatus() == ArticleStatus.REJECTED
                    && request.getStatus() == ArticleStatus.PENDING_REVIEW) {
                throw new BusinessException(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID);
            }
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
        articleFileBinder.bindFilesToArticleSafely(updated.getUuid(), updated.getContent());

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
     * 提交文章審核（DRAFT / REJECTED → PENDING_REVIEW）
     *
     * <p>
     * 允許的來源狀態完全由 {@link #VALID_TRANSITIONS} 決定，本方法不內嵌第二套判斷
     * （SEC-02 收斂）。REJECTED → PENDING_REVIEW 是「被駁回後改一改重新送審」，
     * 送審成功時一併清空 rejectReason。
     * </p>
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
        validateStatusTransition(article.getStatus(), ArticleStatus.PENDING_REVIEW, operatorRole);
        article.setStatus(ArticleStatus.PENDING_REVIEW);
        article.setRejectReason(null);
        Article updated = articleRepository.save(article);
        return articleResponseMapper.toResponse(updated);
    }

    /**
     * 抽回送審文章（PENDING_REVIEW → DRAFT）
     *
     * <p>
     * 作者送審後在審核完成前反悔時使用，將文章退回草稿以便繼續編輯。
     * 僅允許 PENDING_REVIEW 狀態抽回，其餘狀態一律拋出
     * {@link ArticleErrorCode#ARTICLE_STATUS_TRANSITION_INVALID}。
     * </p>
     *
     * <p>
     * <b>來源限制比狀態機更嚴是刻意的</b>：{@link #VALID_TRANSITIONS} 裡
     * ARCHIVED → DRAFT、REJECTED → DRAFT 都是合法轉換，但那兩條不屬於「抽回送審」語意，
     * 不從本端點放行（見 VALID_TRANSITIONS 的「表與入口的分工」）。
     * 轉換本身是否合法仍交由 {@link #validateStatusTransition} 判定，本方法不內嵌第二套規則。
     * </p>
     *
     * <p>
     * <b>權限刻意嚴於其他寫入操作：僅限文章作者本人</b>。抽回與駁回是職責分離的兩個動作
     * ——「抽回」是作者主動收回自己送審的文章，「駁回」是 ADMIN 審核不通過。
     * 因此本方法不走 {@link #checkWritePermission} 的 ADMIN ownership bypass，
     * 改用 {@link #checkAuthorOnly}；ADMIN 要處理他人送審文章應走
     * {@link #rejectArticle}（該路徑行為不受影響）。
     * </p>
     *
     * <p>
     * 刻意不發送任何 MQ 事件：PENDING_REVIEW 從未進入 Elasticsearch 索引
     * （索引僅由 ArticlePublishedEvent / ArticleUpdatedEvent 建立，兩者皆限 PUBLISHED），
     * 故無反向索引動作需求；此與同樣不發事件的 {@link #submitForReview}、
     * {@link #rejectArticle} 對稱。
     * </p>
     *
     * @param operatorId   操作者資料庫主鍵
     * @param operatorRole 操作者角色（保留以與其他狀態轉換方法簽章一致；
     *                     抽回為作者本人專屬，刻意不依角色放行）
     * @param articleUuid  文章公開 UUID
     * @return 抽回後的文章完整資訊
     */
    @Transactional
    public ArticleResponse withdrawArticle(Long operatorId, Role operatorRole, UUID articleUuid) {
        Article article = entityFinder.findByUuidOrThrow(articleUuid);
        checkAuthorOnly(operatorId, article);
        /*
         * 入口語意守衛（比狀態機更嚴，見 VALID_TRANSITIONS 的「表與入口的分工」）：
         * 抽回專指「收回自己送審中的文章」，故來源必須是 PENDING_REVIEW。
         * 表裡的 ARCHIVED → DRAFT、REJECTED → DRAFT 是合法轉換，但不屬於抽回語意，
         * 不得從這個端點放行。
         */
        if (article.getStatus() != ArticleStatus.PENDING_REVIEW) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID);
        }
        /** 轉換是否合法一律回頭問狀態機，不在此內嵌第二套規則 */
        validateStatusTransition(article.getStatus(), ArticleStatus.DRAFT, operatorRole);
        article.setStatus(ArticleStatus.DRAFT);
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
     * 檢查嚴格作者身分：僅文章作者本人可通過，ADMIN 亦不例外
     *
     * <p>
     * 與 {@link #checkWritePermission} 的差異在於<b>不提供 ADMIN ownership bypass</b>，
     * 供「作者主動操作」語意的端點使用（目前為 {@link #withdrawArticle}）。
     * 刻意獨立成一個方法而非在共用方法加開關，避免影響
     * update / delete / submit / publish 既有的 ADMIN 管理權行為。
     * </p>
     *
     * @param operatorId 操作者 ID
     * @param article    目標文章
     */
    private void checkAuthorOnly(Long operatorId, Article article) {
        if (!article.getAuthorId().equals(operatorId)) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_ACCESS_DENIED);
        }
    }

    /**
     * 驗證狀態轉換是否合法（所有改狀態路徑的唯一守衛）
     *
     * <p>
     * 合法轉換一律查 {@link #VALID_TRANSITIONS}；PENDING_REVIEW → PUBLISHED / REJECTED
     * 另需 ADMIN 權限。update / publish / reject / submitForReview / withdraw 全部經此，
     * 任何新的狀態改動路徑也必須經此（SEC-02：restore 曾完全繞過，已於同一 PR 移除改狀態的能力）。
     * </p>
     *
     * <p>
     * <b>可見性刻意放寬到 package-private</b>：狀態機的完整 5×5 轉換矩陣要由
     * {@code ArticleCommandSubServiceTest} 直接驗證。表裡有數條邊（PUBLISHED → ARCHIVED、
     * ARCHIVED → DRAFT）目前沒有任何公開端點可觸發，透過既有 public 方法無法覆蓋全矩陣；
     * 本類別本身即 package-private，故此舉不擴大模組外的 API 面。
     * </p>
     *
     * @param from         目前狀態
     * @param to           目標狀態
     * @param operatorRole 操作者角色
     */
    void validateStatusTransition(ArticleStatus from, ArticleStatus to, Role operatorRole) {
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
