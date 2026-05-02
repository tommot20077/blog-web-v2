package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent.Action;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.service.ArticleEventPublisher;
import dowob.xyz.blog.module.article.service.ArticleMarkdownRenderer;
import dowob.xyz.blog.module.version.exception.VersionErrorCode;
import dowob.xyz.blog.module.version.mapper.VersionMapper;
import dowob.xyz.blog.module.version.model.ArticleVersion;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import dowob.xyz.blog.module.version.model.dto.response.VersionDetailResponse;
import dowob.xyz.blog.module.version.model.dto.response.VersionSummaryResponse;
import dowob.xyz.blog.module.version.repository.ArticleVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Version 模組核心 Service — 寫入 / 還原 / 配置。
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class VersioningService {

    public static final String TYPE_AUTO = "AUTO";
    public static final String TYPE_MANUAL = "MANUAL";
    public static final String TYPE_PUBLISHED = "PUBLISHED";

    private final ArticleRepository articleRepo;
    private final ArticleVersionRepository versionRepo;
    private final VersionMapper versionMapper;
    private final PreferenceResolver preferenceResolver;
    private final ArticleMarkdownRenderer markdownRenderer;
    private final ArticleEventPublisher articleEventPublisher;
    private final TagFacade tagFacade;

    /**
     * 記錄自動快照並套用滾動保留策略。
     * <p>若 articleId 不存在則靜默返回（冪等）。</p>
     *
     * @param articleId 文章主鍵
     */
    @Transactional
    public void recordAutoSnapshot(Long articleId) {
        Article article = articleRepo.findById(articleId).orElse(null);
        if (article == null) return;

        AutoSnapshotConfig cfg = preferenceResolver.resolveForUser(article.getAuthorId());

        ArticleVersion v = snapshotFromArticle(article, TYPE_AUTO, null);
        versionRepo.save(v);

        versionMapper.retainAuto(articleId, cfg.retain());
    }

    /**
     * 記錄手動快照（type=MANUAL）。
     * <p>不執行 retention；article 不存在時拋 V0106。</p>
     *
     * @param articleId 文章主鍵
     * @param note      備注說明（可為 null）
     * @return 已持久化的 ArticleVersion
     */
    @Transactional
    public ArticleVersion recordManualSnapshot(Long articleId, String note) {
        Article article = articleRepo.findById(articleId)
            .orElseThrow(() -> new BusinessException(VersionErrorCode.ARTICLE_NOT_FOUND));

        ArticleVersion v = snapshotFromArticle(article, TYPE_MANUAL, note);
        return versionRepo.save(v);
    }

    /**
     * 凍結發布快照（type=PUBLISHED）並清除所有 AUTO 快照。
     * <p>note 格式為 "Published vN"，N = 既有 PUBLISHED 數 + 1。</p>
     * <p>article 不存在時靜默返回。</p>
     *
     * @param articleId 文章主鍵
     */
    @Transactional
    public void freezePublished(Long articleId) {
        Article article = articleRepo.findById(articleId).orElse(null);
        if (article == null) return;

        int count = versionMapper.countPublished(articleId);
        String note = "Published v" + (count + 1);

        ArticleVersion v = snapshotFromArticle(article, TYPE_PUBLISHED, note);
        versionRepo.save(v);

        versionMapper.deleteAutoByArticle(articleId);
    }

    /**
     * 將 AUTO 快照升級為 MANUAL（使用者救援機制）。
     * <ul>
     *   <li>V0101：version 不存在</li>
     *   <li>V0102：非 owner 且非 admin</li>
     *   <li>V0104：type 不是 AUTO</li>
     * </ul>
     *
     * @param versionUuid   版本 UUID
     * @param currentUserId 當前操作者 ID
     * @param isAdmin       是否為管理員（繞過 owner 檢查）
     * @return 更新後的 ArticleVersion
     */
    @Transactional
    public ArticleVersion promote(UUID versionUuid, Long currentUserId, boolean isAdmin) {
        ArticleVersion v = versionRepo.findByUuid(versionUuid)
            .orElseThrow(() -> new BusinessException(VersionErrorCode.VERSION_NOT_FOUND));
        if (!isAdmin && !v.getAuthorId().equals(currentUserId)) {
            throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
        }
        if (!TYPE_AUTO.equals(v.getType())) {
            throw new BusinessException(VersionErrorCode.CANNOT_PROMOTE_NON_AUTO);
        }
        v.setType(TYPE_MANUAL);
        return versionRepo.save(v);
    }

    /**
     * 刪除快照（僅允許 MANUAL / AUTO；PUBLISHED 不可刪）。
     * <ul>
     *   <li>V0101：version 不存在</li>
     *   <li>V0102：非 owner 且非 admin</li>
     *   <li>V0103：type = PUBLISHED，禁止刪除</li>
     * </ul>
     *
     * @param versionUuid   版本 UUID
     * @param currentUserId 當前操作者 ID
     * @param isAdmin       是否為管理員（繞過 owner 檢查）
     */
    @Transactional
    public void delete(UUID versionUuid, Long currentUserId, boolean isAdmin) {
        ArticleVersion v = versionRepo.findByUuid(versionUuid)
            .orElseThrow(() -> new BusinessException(VersionErrorCode.VERSION_NOT_FOUND));
        if (!isAdmin && !v.getAuthorId().equals(currentUserId)) {
            throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
        }
        if (TYPE_PUBLISHED.equals(v.getType())) {
            throw new BusinessException(VersionErrorCode.CANNOT_DELETE_PUBLISHED);
        }
        versionRepo.delete(v);
    }

    /**
     * 將指定版本快照還原為文章當前狀態。
     *
     * <p>流程：
     * <ol>
     *   <li>先將當前 article 狀態 stash 為 AUTO 快照（保護現有內容）</li>
     *   <li>執行 AUTO 保留策略（retainAuto）</li>
     *   <li>將 version 內容寫回 article（含 render contentHtml）</li>
     *   <li>重綁 article_tags（先清後寫，透過 TagFacade.syncArticleTags）</li>
     *   <li>發送 ContentChanged(RESTORED) 與 Updated 事件</li>
     * </ol>
     * </p>
     *
     * <ul>
     *   <li>V0101：version 不存在</li>
     *   <li>V0102：非 owner 且非 admin</li>
     *   <li>V0106：article 不存在</li>
     * </ul>
     *
     * <p>⚠ Limitation: categories restore 暫不處理（schema 設計缺陷 —
     * Article 用 article_categories 多對多但 ArticleVersion 仍存 categoryId 單值欄位；
     * 該欄位永遠 null）。</p>
     *
     * @param versionUuid   要還原的版本 UUID
     * @param currentUserId 當前操作者 ID
     * @param isAdmin       是否為管理員（繞過 owner 檢查）
     * @return 更新後的 Article
     */
    @Transactional
    public Article restore(UUID versionUuid, Long currentUserId, boolean isAdmin) {
        ArticleVersion v = versionRepo.findByUuid(versionUuid)
            .orElseThrow(() -> new BusinessException(VersionErrorCode.VERSION_NOT_FOUND));
        if (!isAdmin && !v.getAuthorId().equals(currentUserId)) {
            throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
        }

        Article article = articleRepo.findById(v.getArticleId())
            .orElseThrow(() -> new BusinessException(VersionErrorCode.ARTICLE_NOT_FOUND));

        /* 1. stash 當前狀態為 AUTO snapshot（保護現有內容） */
        ArticleVersion stash = snapshotFromArticle(article, TYPE_AUTO, null);
        versionRepo.save(stash);
        AutoSnapshotConfig cfg = preferenceResolver.resolveForUser(article.getAuthorId());
        versionMapper.retainAuto(article.getId(), cfg.retain());

        /* 2. 寫回 article 內容 */
        article.setTitle(v.getTitle());
        article.setSlug(v.getSlug());
        article.setContent(v.getContent());
        article.setSummary(v.getSummary());
        article.setCoverImageUrl(v.getCoverImageUrl());
        /* categoryId 暫不處理（schema 設計缺陷，留 dead field） */
        if (v.getStatus() != null) {
            article.setStatus(ArticleStatus.valueOf(v.getStatus()));
        }
        article.setContentHtml(markdownRenderer.render(v.getContent()));
        Article saved = articleRepo.save(article);

        /* 3. 重綁 article_tags（article_tags 使用 UUID FK，透過 TagFacade 操作） */
        List<UUID> tags = v.getTags();
        tagFacade.syncArticleTags(saved.getUuid(), tags != null ? tags : List.of());

        /* 4. 發 events */
        articleEventPublisher.publishContentChanged(saved, Action.RESTORED);
        articleEventPublisher.publishUpdated(saved);

        return saved;
    }

    /**
     * 分頁查詢 article 的版本列表（不含 content）。
     * <ul>
     *   <li>V0106：article 不存在</li>
     *   <li>V0102：非 owner 且非 admin</li>
     * </ul>
     *
     * @param articleUuid   文章 UUID
     * @param typeFilter    類型篩選（可為 null = 全部）
     * @param page          頁碼（從 1 開始）
     * @param size          每頁筆數
     * @param currentUserId 當前操作者 ID
     * @param isAdmin       是否為管理員
     * @return 版本 summary 分頁結果
     */
    @Transactional(readOnly = true)
    public PageResult<VersionSummaryResponse> listByArticle(
            UUID articleUuid, String typeFilter, int page, int size,
            Long currentUserId, boolean isAdmin) {
        Article article = articleRepo.findByUuid(articleUuid)
            .orElseThrow(() -> new BusinessException(VersionErrorCode.ARTICLE_NOT_FOUND));
        if (!isAdmin && !article.getAuthorId().equals(currentUserId)) {
            throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
        }
        int offset = Math.max(0, (page - 1) * size);
        List<VersionSummaryResponse> rows = versionMapper
            .listSummaries(article.getId(), typeFilter, size, offset);
        long total = versionMapper.countSummaries(article.getId(), typeFilter);
        return PageResult.of(page, size, total, rows);
    }

    /**
     * 取版本詳情（含 content）。
     * <ul>
     *   <li>V0101：version 不存在</li>
     *   <li>V0102：非 owner 且非 admin</li>
     * </ul>
     *
     * @param versionUuid   版本 UUID
     * @param currentUserId 當前操作者 ID
     * @param isAdmin       是否為管理員
     * @return 版本詳情 DTO
     */
    @Transactional(readOnly = true)
    public VersionDetailResponse getDetail(UUID versionUuid, Long currentUserId, boolean isAdmin) {
        ArticleVersion v = versionRepo.findByUuid(versionUuid)
            .orElseThrow(() -> new BusinessException(VersionErrorCode.VERSION_NOT_FOUND));
        if (!isAdmin && !v.getAuthorId().equals(currentUserId)) {
            throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
        }
        return toDetailResponse(v);
    }

    /**
     * 查詢 article by UUID，檢查 ownership，回傳 article.id。
     * <ul>
     *   <li>V0106：article 不存在</li>
     *   <li>V0102：非 owner 且非 admin</li>
     * </ul>
     *
     * @param articleUuid   文章 UUID
     * @param currentUserId 當前操作者 ID
     * @param isAdmin       是否為管理員
     * @return article 主鍵 id
     */
    @Transactional(readOnly = true)
    public Long findArticleIdByUuidOrThrow(UUID articleUuid, Long currentUserId, boolean isAdmin) {
        Article article = articleRepo.findByUuid(articleUuid)
            .orElseThrow(() -> new BusinessException(VersionErrorCode.ARTICLE_NOT_FOUND));
        if (!isAdmin && !article.getAuthorId().equals(currentUserId)) {
            throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
        }
        return article.getId();
    }

    /**
     * 轉換 ArticleVersion 為 VersionDetailResponse。
     */
    private VersionDetailResponse toDetailResponse(ArticleVersion v) {
        VersionDetailResponse r = new VersionDetailResponse();
        r.setUuid(v.getUuid());
        r.setType(v.getType());
        r.setNote(v.getNote());
        r.setCreatedAt(v.getCreatedAt());
        r.setAuthorId(v.getAuthorId());
        r.setTitle(v.getTitle());
        r.setSlug(v.getSlug());
        r.setContent(v.getContent());
        r.setSummary(v.getSummary());
        r.setCategoryId(v.getCategoryId());
        r.setCoverImageUrl(v.getCoverImageUrl());
        r.setStatus(v.getStatus());
        r.setTags(v.getTags());
        return r;
    }

    /**
     * 把 article 當前狀態複製成一份 ArticleVersion（不寫入 DB）。
     * <p>供 manual / published / pre-restore 共用。</p>
     *
     * @param article 來源文章
     * @param type    快照類型（TYPE_AUTO / TYPE_MANUAL / TYPE_PUBLISHED）
     * @param note    備注說明（可為 null）
     * @return 尚未持久化的 ArticleVersion
     */
    protected ArticleVersion snapshotFromArticle(Article article, String type, String note) {
        ArticleVersion v = new ArticleVersion();
        v.setUuid(UUID.randomUUID());
        v.setArticleId(article.getId());
        v.setAuthorId(article.getAuthorId());
        v.setType(type);
        v.setTitle(article.getTitle());
        v.setSlug(article.getSlug());
        v.setContent(article.getContent());
        v.setSummary(article.getSummary());
        v.setCoverImageUrl(article.getCoverImageUrl());
        ArticleStatus st = article.getStatus();
        v.setStatus(st != null ? st.name() : null);
        // tags 暫由 mapper 從 article_tags 撈（T11 restore + freezePublished 細節補上）
        v.setNote(note);
        v.setCreatedAt(LocalDateTime.now());
        return v;
    }
}
