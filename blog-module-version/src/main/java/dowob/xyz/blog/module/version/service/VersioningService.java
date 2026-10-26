package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreSource;
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
import org.springframework.transaction.support.TransactionTemplate;

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

    private final ArticleFacade articleFacade;
    private final ArticleVersionRepository versionRepo;
    private final VersionMapper versionMapper;
    private final PreferenceResolver preferenceResolver;
    private final TagFacade tagFacade;

    /** Spring 宣告式事務模板（用於縮小事務範圍，確保 restore 的 MQ 於 DB commit 後才發送） */
    private final TransactionTemplate transactionTemplate;

    /**
     * restore 第一階段（stash 交易）的產出：待還原的目標文章與來源版本。
     *
     * @param articleUuid 目標文章公開 UUID
     * @param version     要還原的版本快照
     */
    private record RestorePlan(UUID articleUuid, ArticleVersion version) {
    }

    /**
     * 記錄自動快照並套用滾動保留策略。
     * <p>若文章不存在則靜默返回（冪等）。</p>
     *
     * <p>V28 起 {@code article_versions} 沒有指向 {@code articles} 的 FK：若文章在「查內容」與「寫快照」之間
     * 被刪除，這裡不會再被資料庫拒絕，而是留下一筆孤兒快照；此情況由 {@code VersionArticleDeletedConsumer}
     * （文章刪除事件）與 {@code ArticleVersionOrphanReconciliationJob}（每日校正）清除。
     * 寫入時的 {@code DataIntegrityViolationException} 因此一律是真正的錯誤，不再吞掉。</p>
     *
     * @param articleUuid 文章公開 UUID
     * @throws IllegalStateException 文章作者 UUID 缺失（資料異常）
     */
    @Transactional
    public void recordAutoSnapshot(UUID articleUuid) {
        ArticleContentData article = articleFacade.findContentByUuid(articleUuid).orElse(null);
        if (article == null) return;

        AutoSnapshotConfig cfg = preferenceResolver.resolveForUser(requireAuthorUuid(article));

        versionRepo.save(snapshotFromContent(article, TYPE_AUTO, null));

        versionMapper.retainAuto(articleUuid, cfg.retain());
    }

    /**
     * 記錄手動快照（type=MANUAL）。
     * <p>不執行 retention；article 不存在時拋 V0106。</p>
     *
     * @param articleUuid 文章公開 UUID
     * @param note        備注說明（可為 null）
     * @return 已持久化的 ArticleVersion
     */
    @Transactional
    public ArticleVersion recordManualSnapshot(UUID articleUuid, String note) {
        ArticleContentData article = articleFacade.findContentByUuid(articleUuid)
            .orElseThrow(() -> new BusinessException(VersionErrorCode.ARTICLE_NOT_FOUND));

        ArticleVersion v = snapshotFromContent(article, TYPE_MANUAL, note);
        return versionRepo.save(v);
    }

    /**
     * 凍結發布快照（type=PUBLISHED）並清除所有 AUTO 快照。
     * <p>note 格式為 "Published vN"，N = 既有 PUBLISHED 數 + 1。</p>
     * <p>article 不存在時靜默返回。</p>
     *
     * @param articleUuid 文章公開 UUID
     */
    @Transactional
    public void freezePublished(UUID articleUuid) {
        ArticleContentData article = articleFacade.findContentByUuid(articleUuid).orElse(null);
        if (article == null) return;

        int count = versionMapper.countPublished(articleUuid);
        String note = "Published v" + (count + 1);

        ArticleVersion v = snapshotFromContent(article, TYPE_PUBLISHED, note);
        versionRepo.save(v);

        versionMapper.deleteAutoByArticle(articleUuid);
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
     * @param currentUserUuid 當前操作者公開 UUID
     * @param isAdmin       是否為管理員（繞過 owner 檢查）
     * @return 更新後的 ArticleVersion
     */
    @Transactional
    public ArticleVersion promote(UUID versionUuid, UUID currentUserUuid, boolean isAdmin) {
        ArticleVersion v = versionRepo.findByUuid(versionUuid)
            .orElseThrow(() -> new BusinessException(VersionErrorCode.VERSION_NOT_FOUND));
        if (!isAdmin && !isOwner(v.getAuthorUuid(), currentUserUuid)) {
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
     * @param currentUserUuid 當前操作者公開 UUID
     * @param isAdmin       是否為管理員（繞過 owner 檢查）
     */
    @Transactional
    public void delete(UUID versionUuid, UUID currentUserUuid, boolean isAdmin) {
        ArticleVersion v = versionRepo.findByUuid(versionUuid)
            .orElseThrow(() -> new BusinessException(VersionErrorCode.VERSION_NOT_FOUND));
        if (!isAdmin && !isOwner(v.getAuthorUuid(), currentUserUuid)) {
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
     *   <li>呼叫 ArticleFacade.applyRestoreContent(UUID, ArticleRestoreSource) atomic 寫回內容
     *       （渲染 HTML／TOC、mutate / save / syncArticleTags / publish events 全由 article 模組內部接管）</li>
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
     * @param currentUserUuid 當前操作者公開 UUID
     * @param isAdmin       是否為管理員（繞過 owner 檢查）
     */
    public void restore(UUID versionUuid, UUID currentUserUuid, boolean isAdmin) {
        /* 1. stash 交易：權限檢查 + 將當前 article 狀態存為 AUTO snapshot（保護現有內容）。
         *    此段先行 commit；若後續還原失敗，僅多一筆忠實反映「未還原」現況的 AUTO 快照，
         *    且不會有任何 MQ 送出（事件由 applyRestoreContent 於其 commit 後才發）。 */
        RestorePlan plan = transactionTemplate.execute(status -> {
            ArticleVersion v = versionRepo.findByUuid(versionUuid)
                .orElseThrow(() -> new BusinessException(VersionErrorCode.VERSION_NOT_FOUND));
            if (!isAdmin && !isOwner(v.getAuthorUuid(), currentUserUuid)) {
                throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
            }

            ArticleContentData article = articleFacade.findContentByUuid(v.getArticleUuid())
                .orElseThrow(() -> new BusinessException(VersionErrorCode.ARTICLE_NOT_FOUND));

            ArticleVersion stash = snapshotFromContent(article, TYPE_AUTO, null);
            versionRepo.save(stash);
            AutoSnapshotConfig cfg = preferenceResolver.resolveForUser(requireAuthorUuid(article));
            versionMapper.retainAuto(article.uuid(), cfg.retain());

            return new RestorePlan(article.uuid(), v);
        });

        /* 2. stash 已 commit，且此處不在任何交易作用域內：
         *    交由 ArticleFacade 以自身交易還原內容，並於其 commit 後 best-effort 發事件。 */
        ArticleVersion v = plan.version();
        /* 只交出 Markdown 原文：HTML 與 TOC 由 article 模組在 applyRestoreContent 內以同一次 render 算出，
         * version 不 import 渲染器（跨模組只經 facade）。
         * SEC-02：刻意不傳 v.getStatus()。還原只還原內容，文章狀態一律不動——
         * 快照的 status 只作為「這份快照是哪個階段的內容」的紀錄（版本詳情 API 仍回傳），
         * 不再被套回文章；狀態轉換的唯一真相是 ArticleCommandSubService.VALID_TRANSITIONS。 */
        ArticleRestoreSource source = new ArticleRestoreSource(
            v.getTitle(),
            v.getSlug(),
            v.getContent(),
            v.getSummary(),
            v.getCoverImageUrl(),
            v.getTags() != null ? v.getTags() : List.of()
        );
        articleFacade.applyRestoreContent(plan.articleUuid(), source);
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
     * @param currentUserUuid 當前操作者公開 UUID
     * @param isAdmin       是否為管理員
     * @return 版本 summary 分頁結果
     */
    @Transactional(readOnly = true)
    public PageResult<VersionSummaryResponse> listByArticle(
            UUID articleUuid, String typeFilter, int page, int size,
            UUID currentUserUuid, boolean isAdmin) {
        assertArticleOwnerOrAdmin(articleUuid, currentUserUuid, isAdmin);
        int offset = Math.max(0, (page - 1) * size);
        List<VersionSummaryResponse> rows = versionMapper
            .listSummaries(articleUuid, typeFilter, size, offset);
        long total = versionMapper.countSummaries(articleUuid, typeFilter);
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
     * @param currentUserUuid 當前操作者公開 UUID
     * @param isAdmin       是否為管理員
     * @return 版本詳情 DTO
     */
    @Transactional(readOnly = true)
    public VersionDetailResponse getDetail(UUID versionUuid, UUID currentUserUuid, boolean isAdmin) {
        ArticleVersion v = versionRepo.findByUuid(versionUuid)
            .orElseThrow(() -> new BusinessException(VersionErrorCode.VERSION_NOT_FOUND));
        if (!isAdmin && !isOwner(v.getAuthorUuid(), currentUserUuid)) {
            throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
        }
        return toDetailResponse(v);
    }

    /**
     * 檢查文章存在且操作者為其作者（或 admin）。
     * <p>取代過去回傳 article 主鍵的 {@code findArticleIdByUuidOrThrow}：跨模組識別一律用 UUID，
     * 不再把 article 的 Long 主鍵帶出本模組，故只做檢查、不回傳。</p>
     * <ul>
     *   <li>V0106：article 不存在</li>
     *   <li>V0102：非 owner 且非 admin</li>
     * </ul>
     *
     * @param articleUuid     文章 UUID
     * @param currentUserUuid 當前操作者公開 UUID
     * @param isAdmin         是否為管理員
     */
    @Transactional(readOnly = true)
    public void assertArticleOwnerOrAdmin(UUID articleUuid, UUID currentUserUuid, boolean isAdmin) {
        ArticleData article = articleFacade.findByUuid(articleUuid)
            .orElseThrow(() -> new BusinessException(VersionErrorCode.ARTICLE_NOT_FOUND));
        if (!isAdmin && !isOwner(article.authorUuid(), currentUserUuid)) {
            throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
        }
    }

    /**
     * 驗證 versionUuid 確實屬於 articleUuid 所指的文章，
     * 用於巢狀 URL（/articles/{articleUuid}/versions/{versionUuid}/...）的一致性檢查。
     * <p>避免 client 用文章 A 的 URL 操作文章 B 的 version。</p>
     * <ul>
     *   <li>V0101：version 不存在</li>
     *   <li>V0106：article 不存在</li>
     *   <li>V0108：version 與 article 不對應</li>
     * </ul>
     *
     * @param articleUuid 路徑中的文章 UUID
     * @param versionUuid 路徑中的版本 UUID
     */
    @Transactional(readOnly = true)
    public void assertVersionBelongsToArticle(UUID articleUuid, UUID versionUuid) {
        ArticleVersion v = versionRepo.findByUuid(versionUuid)
            .orElseThrow(() -> new BusinessException(VersionErrorCode.VERSION_NOT_FOUND));
        if (!articleFacade.existsByUuid(articleUuid)) {
            throw new BusinessException(VersionErrorCode.ARTICLE_NOT_FOUND);
        }
        if (!articleUuid.equals(v.getArticleUuid())) {
            throw new BusinessException(VersionErrorCode.VERSION_ARTICLE_MISMATCH);
        }
    }

    /**
     * 轉換 ArticleVersion 為 VersionDetailResponse。
     *
     * <p>
     * 不映射 entity 的 authorUuid / articleUuid / categoryId / id —— 對外只出 UUID
     * （{@code architecture.md}），理由見 {@link VersionDetailResponse} 的 javadoc。
     * </p>
     *
     * <p>
     * 公開給 controller 使用：{@code createManual} / {@code promote} 過去直接回傳 entity，
     * 現改為經本方法映射。service 內部方法仍回傳 entity（供 ownership 檢查與既有呼叫者）。
     * </p>
     *
     * @param v 版本 entity
     * @return 對外安全的版本詳情 DTO
     */
    public VersionDetailResponse toDetailResponse(ArticleVersion v) {
        VersionDetailResponse r = new VersionDetailResponse();
        r.setUuid(v.getUuid());
        r.setType(v.getType());
        r.setNote(v.getNote());
        r.setCreatedAt(v.getCreatedAt());
        r.setTitle(v.getTitle());
        r.setSlug(v.getSlug());
        r.setContent(v.getContent());
        r.setSummary(v.getSummary());
        r.setCoverImageUrl(v.getCoverImageUrl());
        r.setStatus(v.getStatus());
        r.setTags(v.getTags());
        return r;
    }

    /**
     * 把 article 當前狀態複製成一份 ArticleVersion（不寫入 DB）。
     * <p>供 manual / published / pre-restore 共用。</p>
     *
     * @param article 來源文章內容 DTO
     * @param type    快照類型（TYPE_AUTO / TYPE_MANUAL / TYPE_PUBLISHED）
     * @param note    備注說明（可為 null）
     * @return 尚未持久化的 ArticleVersion
     */
    protected ArticleVersion snapshotFromContent(ArticleContentData article, String type, String note) {
        ArticleVersion v = new ArticleVersion();
        v.setUuid(UUID.randomUUID());
        v.setArticleUuid(article.uuid());
        v.setAuthorUuid(requireAuthorUuid(article));
        v.setType(type);
        v.setTitle(article.title());
        v.setSlug(article.slug());
        v.setContent(article.content());
        v.setSummary(article.summary());
        v.setCoverImageUrl(article.coverImageUrl());
        v.setStatus(article.status());
        // 抄入 article 當前 tags：restore 時 syncArticleTags 才不會把 tags 清空
        v.setTags(tagFacade.findTagIdsByArticleUuid(article.uuid()));
        v.setNote(note);
        v.setCreatedAt(LocalDateTime.now());
        return v;
    }

    /**
     * 取得文章作者 UUID；缺失代表資料異常（文章的作者在 users 表有 FK 且使用者只軟刪除，正常必有值）。
     * 明確拋例外而不是讓 {@code author_uuid NOT NULL} 約束在寫入時才失敗，訊息才指得出是哪篇文章。
     *
     * @param article 文章內容 DTO
     * @return 作者公開 UUID（非 null）
     * @throws IllegalStateException 作者 UUID 為 null
     */
    private UUID requireAuthorUuid(ArticleContentData article) {
        if (article.authorUuid() == null) {
            throw new IllegalStateException("文章缺少作者 UUID，無法建立版本快照 articleUuid=" + article.uuid());
        }
        return article.authorUuid();
    }

    /**
     * 判斷操作者是否為資源擁有者。擁有者 UUID 為 null（資料異常）時一律視為不是（fail-closed）。
     *
     * @param ownerUuid       資源擁有者公開 UUID（可為 null）
     * @param currentUserUuid 當前操作者公開 UUID
     * @return 是擁有者為 true
     */
    private static boolean isOwner(UUID ownerUuid, UUID currentUserUuid) {
        return ownerUuid != null && ownerUuid.equals(currentUserUuid);
    }
}
