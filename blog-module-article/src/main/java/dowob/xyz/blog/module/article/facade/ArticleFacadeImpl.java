package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.util.ArticleVisibility;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleNavRef;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreSource;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryResponse;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleTrendingData;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.infrastructure.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.ArticleRecommendMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.ArticleSummaryRow;
import dowob.xyz.blog.module.article.model.ArticleTagRow;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.service.ArticleContentFreezePolicy;
import dowob.xyz.blog.module.article.service.ArticleEventPublisher;
import dowob.xyz.blog.module.article.service.ArticleFileBinder;
import dowob.xyz.blog.module.article.service.ArticleMarkdownRenderer;
import dowob.xyz.blog.module.article.service.ArticleQueryService;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.article.service.ArticleTocCodec;
import dowob.xyz.blog.module.article.service.RenderResult;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * ArticleFacade 實作
 *
 * <p>
 * 提供搜尋模組與推薦模組所需的文章資料跨模組存取實作。
 * 搜尋功能透過 {@link ArticleMapper} 與 {@link UserFacade} 取得全量索引資料；
 * 推薦功能透過 {@link ArticleRecommendMapper} 執行高效查詢。
 * 此 Bean 由 blog-module-article 提供，在應用啟動時注入。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class ArticleFacadeImpl implements ArticleFacade {

    /**
     * 文章 MyBatis Mapper（搜尋用）
     */
    private final ArticleMapper articleMapper;

    /**
     * 用戶 Facade（跨模組查詢作者資訊）
     */
    private final UserFacade userFacade;

    /**
     * 推薦功能專用 MyBatis Mapper
     */
    private final ArticleRecommendMapper recommendMapper;

    /**
     * 文章 Service（SP-B 新增：供 10 個新 delegate method 使用）
     */
    private final ArticleService articleService;

    /**
     * 文章 Repository（SP-D 新增：供 findContentById / applyRestoreContent 使用）
     */
    private final ArticleRepository articleRepository;

    /**
     * 標籤 Facade（SP-D 新增：供 applyRestoreContent syncArticleTags 使用）
     */
    private final TagFacade tagFacade;

    /**
     * 文章事件發布元件（SP-D 新增：供 applyRestoreContent publish events 使用）
     */
    private final ArticleEventPublisher articleEventPublisher;

    /**
     * Spring 宣告式事務模板（用於縮小事務範圍，確保 MQ 於 DB commit 後才發送）
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 文章「檔案 → 文章」綁定回填元件（修復：版本還原路徑繞過檔案綁定掃描導致還原後圖片破圖）
     *
     * <p>
     * 比照 {@code ArticleCommandSubService} create/update 路徑（Task B6）：
     * applyRestoreContent 會改動 article.content，因此還原後也必須重新掃描並回填「檔案 → 文章」
     * 綁定，否則還原後內文引用的圖仍停在未綁定 = 私有（fail-safe），已發布文章會破圖；
     * 而還原前內容引用、還原後不再引用的圖，其舊綁定也不會被解除（權限殘留）。
     * </p>
     */
    private final ArticleFileBinder articleFileBinder;

    /**
     * ArticleData 組裝元件（設計 §3.1 過渡 API：批次填入 authorUuid / seriesUuid；
     * SeriesFacade 在其內以 ObjectProvider 延遲取得，避免與 SeriesFacadeImpl 形成 bean 循環）
     */
    private final ArticleDataAssembler articleDataAssembler;

    /**
     * 文章查詢服務的延遲取得入口（M2-a 新增：供 {@link #getSummariesByUuids} 重用既有摘要組裝與登入者補值）
     *
     * <p>必須用 {@link ObjectProvider} 而非直接注入：{@code ArticleQueryService} 注入 {@code ReadingFacade}，
     * 而 reading 的實作類別注入 {@code ArticleFacade}，直接建構子注入會形成 bean 循環。
     * 比照 {@code ArticleIdentityResolver} 處理 {@code SeriesFacade} 的做法，只在方法被呼叫時才取得。</p>
     */
    private final ObjectProvider<ArticleQueryService> articleQueryServiceProvider;

    /**
     * Markdown 渲染器（M3-a 新增：供 UUID 版 {@link #applyRestoreContent(UUID, ArticleRestoreSource)} 在 article 內部渲染）
     */
    private final ArticleMarkdownRenderer markdownRenderer;

    /**
     * TOC JSON 編解碼器（M3-a 新增：把渲染出的章節導覽序列化後與 HTML 一併回填）
     */
    private final ArticleTocCodec tocCodec;

    /**
     * 查詢所有已發布文章的索引資料，供搜尋模組重建 Elasticsearch 索引使用
     *
     * @return 已發布文章的索引資料列表
     */
    @Override
    public List<ArticleIndexData> findAllPublishedForIndex() {
        List<Article> articles = articleMapper.findAllPublished();
        /** authorUuid 整批一次取得（設計 §3.1），不隨文章逐筆查詢 */
        Map<Long, UUID> authorUuids = articleDataAssembler.resolveAuthorUuids(
                articles.stream().map(Article::getAuthorId).toList());
        return articles.stream()
                .map(article -> toIndexData(article, authorUuids))
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>caller（搜尋模組的幽靈掃描）以每頁 1000 筆的節奏送進來，輸入無上界，
     * 故經 {@link BatchedQuery} 切批；空輸入不發查詢，避免產生 {@code IN ()}。
     * 結果為集合聯集，屬跨批可合併形狀。</p>
     */
    @Override
    public Set<UUID> filterPublishedUuids(Collection<UUID> uuids) {
        if (uuids == null) {
            return Set.of();
        }
        List<UUID> distinct = uuids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return BatchedQuery.queryInBatches(distinct, articleMapper::findPublishedUuidsIn).stream()
                .map(UUID::fromString)
                .collect(Collectors.toSet());
    }

    /**
     * {@inheritDoc}
     *
     * <p>分兩步查詢：先確認文章存在且已發布，再取得標籤 ID 列表。</p>
     */
    @Override
    public Optional<ArticleBasicInfo> getPublishedArticleBasicInfo(UUID articleUuid) {
        Long articleId = recommendMapper.findPublishedIdByUuid(articleUuid);
        if (articleId == null) {
            return Optional.empty();
        }
        List<UUID> tagIds = recommendMapper.findTagIdsByArticleUuid(articleUuid)
                .stream().map(UUID::fromString).collect(Collectors.toList());
        return Optional.of(new ArticleBasicInfo(articleUuid, tagIds));
    }

    /**
     * {@inheritDoc}
     *
     * <p>若 tagIds 為空則直接回傳空列表，避免 SQL IN () 語法錯誤。</p>
     */
    @Override
    public List<ArticleSummaryInfo> getArticlesByTagIds(List<UUID> tagIds, UUID excludeUuid, int limit) {
        if (tagIds == null || tagIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> tagIdStrings = tagIds.stream().map(UUID::toString).collect(Collectors.toList());
        List<ArticleSummaryRow> rows = recommendMapper.findByTagIds(tagIdStrings, excludeUuid, limit);
        return assembleSummaryInfoList(rows);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ArticleSummaryInfo> getRecentPublishedArticles(UUID excludeUuid, int limit) {
        List<ArticleSummaryRow> rows = recommendMapper.findRecentPublished(excludeUuid, limit);
        return assembleSummaryInfoList(rows);
    }

    /**
     * {@inheritDoc}
     *
     * <p>若 uuids 為空則直接回傳空列表，避免 SQL IN () 語法錯誤。</p>
     */
    @Override
    public List<ArticleSummaryInfo> getPublishedArticlesByUuids(List<UUID> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return Collections.emptyList();
        }
        List<ArticleSummaryRow> rows = BatchedQuery.queryInBatches(uuids, recommendMapper::findByUuids);
        return assembleSummaryInfoList(rows);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ArticleTrendingData> getArticlesPublishedAfter(LocalDateTime since) {
        return recommendMapper.findPublishedAfter(since);
    }

    /**
     * 將文章實體轉換為索引資料 DTO（供搜尋模組使用）
     *
     * @param article     文章實體
     * @param authorUuids 作者主鍵 → 公開 UUID（整批一次取得）
     * @return ArticleIndexData
     */
    private ArticleIndexData toIndexData(Article article, Map<Long, UUID> authorUuids) {
        List<TagInfo> tags = articleMapper.findTagsByArticleUuid(article.getUuid());
        List<ArticleIndexData.TagData> tagData = tags.stream()
                .map(t -> new ArticleIndexData.TagData(t.id(), t.name(), t.slug()))
                .toList();

        ArticleDataAssembler.warnIfAuthorUuidMissing(article.getId(), article.getAuthorId(), authorUuids);
        return new ArticleIndexData(
                article.getUuid(),
                article.getTitle(),
                article.getSlug(),
                article.getSummary(),
                stripMarkdown(article.getContent()),
                article.getAuthorId(),
                userFacade.getUserUsernameById(article.getAuthorId()).orElse(null),
                userFacade.getUserNicknameById(article.getAuthorId()).orElse(null),
                article.getPublishedAt(),
                article.getViewCount(),
                article.getLikeCount(),
                tagData,
                authorUuids.get(article.getAuthorId()));
    }

    /**
     * 去除 Markdown 格式，回傳純文字（供 Elasticsearch 索引）
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
     * 將 ArticleSummaryRow 列表組裝為 ArticleSummaryInfo 列表
     *
     * <p>
     * 批次查詢所有文章的標籤名稱，避免 N+1 查詢問題，
     * 再以 articleId 為索引建立 Map 對應。
     * </p>
     *
     * @param rows 原始查詢結果列表
     * @return 組裝完成的 ArticleSummaryInfo 列表
     */
    private List<ArticleSummaryInfo> assembleSummaryInfoList(List<ArticleSummaryRow> rows) {
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> articleUuidStrings = rows.stream()
                .map(ArticleSummaryRow::getUuid).collect(Collectors.toList());
        List<ArticleTagRow> tagRows = BatchedQuery.queryInBatches(articleUuidStrings,
                recommendMapper::findTagsByArticleUuids);

        Map<String, List<String>> tagNamesByArticleUuid = tagRows.stream()
                .collect(Collectors.groupingBy(
                        ArticleTagRow::getArticleUuid,
                        Collectors.mapping(ArticleTagRow::getTagName, Collectors.toList())
                ));

        return rows.stream()
                .map(row -> new ArticleSummaryInfo(
                        UUID.fromString(row.getUuid()),
                        row.getTitle(),
                        row.getSlug(),
                        row.getSummary(),
                        row.getAuthorNickname(),
                        tagNamesByArticleUuid.getOrDefault(row.getUuid(), Collections.emptyList()),
                        row.getViewCount(),
                        row.getLikeCount(),
                        row.getPublishedAt()
                ))
                .toList();
    }

    // ─── SP-B 新增 5 read method（純 delegate + Article→ArticleData 轉換）───

    /**
     * {@inheritDoc}
     */
    @Override
    public Long findIdByUuid(UUID articleUuid) {
        return articleService.findIdByUuid(articleUuid);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ArticleData> findByUuid(UUID articleUuid) {
        return articleService.findByUuid(articleUuid).map(articleDataAssembler::toArticleData);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ArticleData> findById(Long articleId) {
        return articleService.findById(articleId).map(articleDataAssembler::toArticleData);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ArticleData> findByIds(List<Long> articleIds) {
        return articleDataAssembler.toArticleDataList(articleService.findByIds(articleIds));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ArticleData> findBySeriesIdOrderByPosition(Long seriesId) {
        return articleDataAssembler.toArticleDataList(articleService.findBySeriesIdOrderByPosition(seriesId));
    }

    // ─── SP-B 新增 5 write method（純 delegate）───

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementCommentCount(Long articleId) {
        articleService.incrementCommentCount(articleId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void decrementCommentCount(Long articleId) {
        articleService.decrementCommentCount(articleId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementLikeCount(Long articleId) {
        articleService.incrementLikeCount(articleId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void decrementLikeCount(Long articleId) {
        articleService.decrementLikeCount(articleId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateSeriesAssignment(Long articleId, Long seriesId, Integer seriesPosition) {
        articleService.updateSeriesAssignment(articleId, seriesId, seriesPosition);
    }

    // ─── SP-D 新增：findContentById + applyRestoreContent ───

    /**
     * {@inheritDoc}
     *
     * <p>純 delegate：委派 articleService.findById 查找 article entity，轉換為 ArticleContentData record。</p>
     */
    @Override
    public Optional<ArticleContentData> findContentById(Long articleId) {
        return articleService.findById(articleId).map(this::toContentData);
    }

    /**
     * {@inheritDoc}
     *
     * <p>M3-a 新增。純 delegate：委派 articleService.findByUuid 查找 article entity，
     * 轉換方式與 {@link #findContentById(Long)} 相同（同一個 {@code toContentData}），不分狀態；
     * {@code articleUuid} 為 null 時直接回傳 empty，不查 DB（與 UUID 版
     * {@link #applyRestoreContent(UUID, ArticleRestoreSource)} 對 null 拋 NPE 不同：查詢方法對「查無」寬容，
     * 寫入方法對呼叫端程式錯誤嚴格）。</p>
     */
    @Override
    public Optional<ArticleContentData> findContentByUuid(UUID articleUuid) {
        if (articleUuid == null) {
            return Optional.empty();
        }
        return articleService.findByUuid(articleUuid).map(this::toContentData);
    }

    /**
     * {@inheritDoc}
     *
     * <p>flow：交易內（撈 article → mutate 內容欄位 → save → syncArticleTags）→ commit →
     * best-effort publish events。</p>
     *
     * <p>
     * <strong>SEC-02：還原只還原內容，文章狀態一律不動</strong>。舊實作會把快照當時的 status
     * 套回文章，等於讓還原成為第三條、且完全不經
     * {@code ArticleCommandSubService.VALID_TRANSITIONS} 守衛的狀態轉換路徑——作者拿一份舊的
     * PUBLISHED 快照按還原，就能把被 ADMIN 駁回（REJECTED）或送審中（PENDING_REVIEW）的文章
     * 直接變回 PUBLISHED，繞過「PENDING_REVIEW → PUBLISHED 僅 ADMIN」的限制。
     * 狀態轉換的唯一入口是 {@code ArticleCommandSubService}（publish / reject / submit / withdraw / update），
     * 還原不參與其中。
     * </p>
     *
     * <p>DB 操作以 {@link TransactionTemplate} 收斂為單一交易，事件一律於 commit 後才發送：
     * 若在交易內發送，commit 失敗時 version/search 模組已收到事件但文章實際未還原，
     * 造成資料不一致（見 {@code ai-docs/code-standards.md} §Transaction+MQ 時序）。</p>
     *
     * <p>重要：syncArticleTags 必須在 publish events 之前完成，
     * 確保 search index update consumer 拿到的 tags 已是最新狀態。</p>
     *
     * <p>
     * <strong>F-H1：還原必須通過與 PUT 相同的內容凍結守衛</strong>。
     * {@code ArticleCommandSubService#updateArticle} 規定 PENDING_REVIEW / PUBLISHED / ARCHIVED
     * 內容凍結，但還原路徑原本完全不受約束：作者把文章送審後仍能用還原把內容換成另一個版本，
     * admin 審的是 A、通過的是 B（審核 TOCTOU）。SEC-02 之後還原不再改 status，
     * 這個換內容的動作在狀態機上完全無痕，反而更隱蔽。
     * </p>
     *
     * <p>
     * 守衛刻意放在<b>本方法內、mutate 之前</b>，而不是要求 version 模組先問一次：
     * 凍結是 article 模組的業務規則，由擁有該資料的模組在自己的寫入點把關，
     * 任何呼叫端（含未來新增的）都繞不過去，規則也不會洩漏到別的模組
     * （見 {@code ai-docs/architecture.md} 跨模組邊界：業務 data 的寫必走 owner module 的 service）。
     * 判斷共用 {@link ArticleContentFreezePolicy}，不複製第二套。
     * </p>
     *
     * <p>
     * 附帶效果：PUBLISHED 被守衛擋下後，下方
     * {@code if (saved.getStatus() == PUBLISHED) publishUpdated} 分支已無呼叫端能到達，
     * 作為防禦性程式碼保留（本次修復不動還原邏輯本身）。
     * </p>
     *
     * <p>
     * <strong>檔案綁定回填（修復：還原後圖片破圖）</strong>：DB commit 後，比照
     * {@code ArticleCommandSubService}（Task B6）另呼叫 {@link ArticleFileBinder#bindFilesToArticleSafely}，
     * 掃描的是 {@code saved.getContent()}（DB 實際存下的那份），而非 {@code data.content()}——
     * 避免將來若此路徑改成部分更新時，掃到不完整的內容。此呼叫與事件發送一樣是
     * best-effort：{@code ArticleFileBinder} 內部已吞掉並記錄失敗，不會讓還原流程失敗。
     * 呼叫時機同樣在 transactionTemplate.execute 回傳（即本方法自身交易已 commit）之後，
     * 且 {@code VersioningService.restore} 本身並非 {@code @Transactional}
     * （見 ai-docs/backlog/2026-07-14-full-review-findings.md §C2 補充），
     * 故此處確實是「外層無交易」情境下的真實 post-commit。
     * </p>
     */
    @Override
    public void applyRestoreContent(Long articleId, ArticleRestoreData data) {
        restoreContent(() -> articleRepository.findById(articleId), data);
    }

    /**
     * {@inheritDoc}
     *
     * <p>M3-a 新增。先在 article 內部以 {@link ArticleMarkdownRenderer} 渲染 {@code source.content()}，
     * HTML 與 TOC 取自<b>同一次</b> {@link RenderResult}（只回填 HTML 會讓 {@code articles.toc} 停在還原前那版，
     * 章節導覽指向不存在的錨點），TOC 以 {@link ArticleTocCodec} 序列化；組成 {@link ArticleRestoreData} 後，
     * 與 Long 版共用同一個 {@link #restoreContent} 內部方法，交易、內容凍結守衛、tag 同步、
     * commit 後事件、檔案重新綁定、SEC-02 全部只有一份實作。</p>
     *
     * <p>渲染刻意放在交易之外：純 CPU 運算，不佔用 DB 連線。</p>
     *
     * <p>入口以 {@link Objects#requireNonNull} 檢查 {@code articleUuid}、{@code source}、
     * {@code source.content()}，任一為 null 即拋帶訊息的 {@link NullPointerException}（呼叫端的程式錯誤，
     * 不是業務錯誤，故不轉為 {@link BusinessException}）；檢查在渲染與任何 DB 存取之前。</p>
     *
     * @throws NullPointerException articleUuid、source 或 source.content() 為 null
     */
    @Override
    public void applyRestoreContent(UUID articleUuid, ArticleRestoreSource source) {
        Objects.requireNonNull(articleUuid, "articleUuid 不可為 null");
        Objects.requireNonNull(source, "source 不可為 null");
        Objects.requireNonNull(source.content(), "source.content 不可為 null");
        RenderResult rendered = markdownRenderer.render(source.content());
        ArticleRestoreData data = new ArticleRestoreData(
                source.title(),
                source.slug(),
                source.content(),
                source.summary(),
                source.coverImageUrl(),
                rendered.html(),
                tocCodec.serialize(rendered.toc()),
                source.tags()
        );
        restoreContent(() -> articleRepository.findByUuid(articleUuid), data);
    }

    /**
     * Long 版與 UUID 版 {@code applyRestoreContent} 共用的還原流程（M3-a 抽出，兩個入口只差「怎麼找到文章」）。
     *
     * <p>flow 與原 Long 版完全相同，詳見 {@link #applyRestoreContent(Long, ArticleRestoreData)} 的說明
     * （SEC-02、F-H1、commit 後發事件、檔案綁定回填）。</p>
     *
     * @param loader 在交易內查找目標文章的來源；查無須回傳 empty（本方法會拋 ARTICLE_NOT_FOUND）
     * @param data   已含 contentHtml／toc 的完整還原資料
     */
    private void restoreContent(Supplier<Optional<Article>> loader, ArticleRestoreData data) {
        Article saved = transactionTemplate.execute(status -> {
            Article article = loader.get()
                    .orElseThrow(() -> new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND));

            /*
             * F-H1 內容凍結守衛：與 PUT 更新共用同一套判斷（ArticleContentFreezePolicy）。
             * 必須在任何 mutate 之前，凍結狀態下 entity 一個欄位都不得被動到。
             */
            ArticleContentFreezePolicy.assertContentEditable(article.getStatus());

            article.setTitle(data.title());
            article.setSlug(data.slug());
            article.setContent(data.content());
            article.setSummary(data.summary());
            article.setCoverImageUrl(data.coverImageUrl());
            /*
             * SEC-02：這裡刻意不碰 article.status。還原是「內容回溯」，不是狀態轉換；
             * 狀態一律留給 ArticleCommandSubService 的 VALID_TRANSITIONS 守衛決定。
             * ArticleRestoreData 也已移除 status 欄位，讓這條路徑在型別上就無法改狀態。
             */
            article.setContentHtml(data.contentHtml());
            /*
             * TOC 必須與 contentHtml 一起回填：兩者出自 caller 的同一次 render。
             * 若漏掉這行，article.toc 會停在還原前那版（Spring Data JDBC 把載入時的舊值原樣寫回），
             * 章節導覽就會指向還原後 HTML 中不存在的錨點。
             * data.toc() 為 null 時存空陣列，維持 articles.toc 恆為合法 JSON 陣列的不變量。
             */
            article.setToc(data.toc() != null ? data.toc() : ArticleTocCodec.EMPTY_TOC_JSON);

            /*
             * 這裡刻意不處理 rejectReason：SEC-02 之後還原完全不改 status（見上方註解與
             * ArticleRestoreData 已無 status 欄位），故還原無法把 REJECTED 文章推進公開狀態，
             * 也就不存在「駁回理由隨文章公開而外流」的路徑。
             * 「非 REJECTED 的文章不得帶駁回理由」這條不變量由
             * ArticleCommandSubService#clearRejectReasonIfNotRejected 在各個真正的狀態轉換點維持；
             * 在這條非轉換路徑上再清一次只是死碼。
             * 守衛見 ArticleFacadeImplTest#applyRestoreContent_rejectedArticle_keepsStatusAndRejectReason。
             */

            Article updated = articleRepository.save(article);

            /** IMPORTANT: syncArticleTags 必須在 publish events 之前發 — search index update 需拿到正確 tags */
            tagFacade.syncArticleTags(updated.getUuid(),
                    data.tags() != null ? data.tags() : List.of());

            return updated;
        });

        /** DB 已 commit，best-effort 發送事件 */
        articleEventPublisher.publishContentChanged(saved, ArticleContentChangedEvent.Action.RESTORED);
        /*
         * 判準是「文章現在的狀態」而非「快照當時的狀態」（SEC-02 後兩者已徹底分離）：
         * 只有本來就公開的文章才需要重新索引；DRAFT / PENDING_REVIEW / REJECTED / ARCHIVED
         * 的文章即使還原了一份 PUBLISHED 時期的內容，也不得被送進 Elasticsearch。
         */
        if (saved.getStatus() == ArticleStatus.PUBLISHED) {
            articleEventPublisher.publishUpdated(saved);
        }

        /** DB 已 commit，best-effort 回填「檔案 → 文章」綁定：掃描 saved.getContent()（DB 實際存下的內容） */
        articleFileBinder.bindFilesToArticleSafely(saved.getUuid(), saved.getContent());
    }

    /**
     * 將文章實體轉換為跨模組 ArticleContentData DTO（SP-D 新增）
     *
     * <p>比 toArticleData 多含 title / slug / content / summary / coverImageUrl 5 個欄位，
     * 適用需要完整 article 內容的場景（version snapshot / restore）。</p>
     *
     * @param article 文章實體
     * @return ArticleContentData record
     */
    private ArticleContentData toContentData(Article article) {
        Map<Long, UUID> authorUuids = articleDataAssembler.resolveAuthorUuids(
                article.getAuthorId() == null ? List.of() : List.of(article.getAuthorId()));
        ArticleDataAssembler.warnIfAuthorUuidMissing(article.getId(), article.getAuthorId(), authorUuids);
        UUID authorUuid = article.getAuthorId() == null ? null : authorUuids.get(article.getAuthorId());
        return new ArticleContentData(
                article.getId(),
                article.getUuid(),
                article.getAuthorId(),
                article.getTitle(),
                article.getSlug(),
                article.getContent(),
                article.getSummary(),
                article.getCoverImageUrl(),
                article.getStatus() != null ? article.getStatus().name() : null,
                authorUuid
        );
    }

    // ─── 集合述詞實作 ───

    /**
     * {@inheritDoc}
     *
     * <p>輸入為 caller 的全量 series 主鍵，無上界，故經 {@link BatchedQuery} 切批。
     * 各批回傳的 key 不重疊（一個 series 只屬於一批），屬跨批可合併形狀。</p>
     */
    @Override
    public Map<Long, Integer> countPublishedBySeriesIds(Collection<Long> seriesIds) {
        return BatchedQuery.queryInBatches(seriesIds, articleMapper::countPublishedBySeriesIds)
                .stream()
                .collect(Collectors.toMap(
                        ArticleMapper.SeriesPublishedCountRow::seriesId,
                        ArticleMapper.SeriesPublishedCountRow::publishedCount));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ArticleNavRef> findPrevPublishedInSeries(Long seriesId, Integer currentPosition) {
        return Optional.ofNullable(articleMapper.findPrevPublishedInSeries(seriesId, currentPosition));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ArticleNavRef> findNextPublishedInSeries(Long seriesId, Integer currentPosition) {
        return Optional.ofNullable(articleMapper.findNextPublishedInSeries(seriesId, currentPosition));
    }

    /**
     * {@inheritDoc}
     *
     * <p>輸入為 caller 的全量候選集（例如某使用者的全部收藏），無上界，
     * 故經 {@link BatchedQuery} 切批。可見性過濾在各批獨立成立，屬跨批可合併形狀；
     * 回傳順序由最後對 {@code candidateIds} 的重排保證，不依賴各批查詢的回傳順序。</p>
     */
    @Override
    public List<Long> filterReadableIds(List<Long> candidateIds, Long viewerId, boolean isAdmin) {
        if (candidateIds == null) {
            return List.of();
        }
        Set<Long> readable = BatchedQuery
                .queryInBatches(candidateIds, articleMapper::findVisibilityRowsByIds).stream()
                .filter(row -> ArticleVisibility.isReadableBy(
                        row.status(), row.authorId(), viewerId, isAdmin))
                .map(ArticleMapper.ArticleVisibilityRow::id)
                .collect(Collectors.toSet());
        return candidateIds.stream().filter(readable::contains).toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>不分狀態</b>：SQL 不篩 {@code status}，草稿也算存在，與
     * {@link #filterPublishedUuids(Collection)} 只認 PUBLISHED 不同。輸入無上界，
     * 故去重後經 {@link BatchedQuery} 切批；空輸入不發查詢，避免產生 {@code IN ()}。
     * 結果為集合聯集，屬跨批可合併形狀。</p>
     */
    @Override
    public Set<UUID> filterExistingUuids(Collection<UUID> uuids) {
        if (uuids == null) {
            return Set.of();
        }
        List<UUID> distinct = uuids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return BatchedQuery.queryInBatches(distinct, articleMapper::findExistingUuidsIn).stream()
                .map(UUID::fromString)
                .collect(Collectors.toSet());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean existsByUuid(UUID articleUuid) {
        return articleUuid != null && articleMapper.existsByUuid(articleUuid);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementCommentCount(UUID articleUuid) {
        articleMapper.incrementCommentCountByUuid(articleUuid);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void decrementCommentCount(UUID articleUuid) {
        articleMapper.decrementCommentCountByUuid(articleUuid);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementLikeCount(UUID articleUuid) {
        articleMapper.incrementLikeCountByUuid(articleUuid);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void decrementLikeCount(UUID articleUuid) {
        articleMapper.decrementLikeCountByUuid(articleUuid);
    }

    /**
     * {@inheritDoc}
     *
     * <p>輸入為 caller 的全量候選集（例如某使用者的全部收藏），無上界，故去重後經 {@link BatchedQuery} 切批。
     * 可見性過濾在各批獨立成立，屬跨批可合併形狀；回傳順序由最後對 {@code candidates} 的重排保證，
     * 不依賴各批查詢的回傳順序。可見性判斷委派 {@link ArticleVisibility}（單一真相），不在 SQL 內重寫。</p>
     */
    @Override
    public List<UUID> filterReadableUuids(List<UUID> candidates, UUID viewerUuid, boolean isAdmin) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<UUID> distinct = candidates.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Set<UUID> readable = BatchedQuery
                .queryInBatches(distinct, articleMapper::findVisibilityRowsByUuids).stream()
                .filter(row -> ArticleVisibility.isReadableBy(
                        row.status(), row.authorUuid(), viewerUuid, isAdmin))
                .map(ArticleMapper.ArticleUuidVisibilityRow::uuid)
                .collect(Collectors.toSet());
        return candidates.stream().filter(readable::contains).toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>做法：先以 {@link BatchedQuery} 批次把 uuid 轉成 id（沿用 {@code ArticleMapper#findIdsByUuids}），
     * 再委派既有的 {@link ArticleQueryService#getArticleSummariesByIds(List)}，因此摘要組裝、
     * 登入者 liked／bookmarked／progress 補值與 series 導覽補值與 Long 版<b>由同一份程式碼產生</b>，
     * 不會出現兩份語意。最後依輸入 uuids 的順序重排，使順序契約不依賴下游實作。</p>
     *
     * <p>{@link ArticleQueryService} 以 {@link ObjectProvider} 延遲取得（見欄位說明），
     * 且只在確實有文章要查時才取得，空輸入與全部查無時不會碰它。</p>
     */
    @Override
    public List<ArticleSummaryResponse> getSummariesByUuids(List<UUID> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return List.of();
        }
        List<UUID> distinct = uuids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> uuidToId = BatchedQuery.queryInBatches(distinct, articleMapper::findIdsByUuids).stream()
                .collect(Collectors.toMap(Article::getUuid, Article::getId, (first, second) -> first));
        List<UUID> foundUuids = distinct.stream().filter(uuidToId::containsKey).toList();
        if (foundUuids.isEmpty()) {
            return List.of();
        }
        List<Long> ids = foundUuids.stream().map(uuidToId::get).toList();
        Map<UUID, ArticleSummaryResponse> byUuid = articleQueryServiceProvider.getObject()
                .getArticleSummariesByIds(ids).stream()
                .collect(Collectors.toMap(ArticleSummaryResponse::getUuid, Function.identity(),
                        (first, second) -> first));
        return foundUuids.stream().map(byUuid::get).filter(Objects::nonNull).toList();
    }

    // ─── M4-a：series UUID 版（過渡期：M6 改以 articles.series_uuid 直查）───

    /**
     * {@inheritDoc}
     *
     * <p>過渡期：先經 SeriesFacade 把 series UUID 換成主鍵，再委派 Long 版的內部查詢；
     * M6 改以 {@code articles.series_uuid} 直查。series 不存在時不查文章，直接回傳空清單。</p>
     */
    @Override
    public List<ArticleData> findBySeriesUuidOrderByPosition(UUID seriesUuid) {
        return articleDataAssembler.resolveSeriesIdByUuid(seriesUuid)
                .map(this::findBySeriesIdOrderByPosition)
                .orElseGet(List::of);
    }

    /**
     * {@inheritDoc}
     *
     * <p>過渡期：流程與 Long 版逐項對應——先以 {@code articleUuid} 找文章主鍵（查無即 no-op，
     * 不檢查 series）；{@code seriesUuid} 為 null 直接寫入 null；非 null 但查無 series 時，
     * 於寫入前拋 {@link IllegalArgumentException}。這是呼叫端的程式錯誤（應先驗證 series 存在），
     * 不是業務錯誤，故不轉為 {@code BusinessException}。M6 改以 {@code articles.series_uuid} 直寫。</p>
     *
     * @throws IllegalArgumentException seriesUuid 非 null 但查無此 series（文章不會被改動）
     */
    @Override
    public void updateSeriesAssignment(UUID articleUuid, UUID seriesUuid, Integer seriesPosition) {
        Long articleId = articleService.findIdByUuid(articleUuid);
        if (articleId == null) {
            return;
        }
        Long seriesId = null;
        if (seriesUuid != null) {
            seriesId = articleDataAssembler.resolveSeriesIdByUuid(seriesUuid)
                    .orElseThrow(() -> new IllegalArgumentException("series 不存在: " + seriesUuid));
        }
        articleService.updateSeriesAssignment(articleId, seriesId, seriesPosition);
    }

    /**
     * {@inheritDoc}
     *
     * <p>過渡期：series UUID 先經 SeriesFacade 換成主鍵，再以<b>單一</b>條件式 UPDATE
     * （{@code WHERE uuid = ? AND series_id IS NULL}）寫入，沒有先讀再寫；M6 改以 {@code series_uuid} 直寫。
     * 本方法刻意不標 {@code @Transactional}：單一語句本身原子，並隨呼叫端（series 服務）的交易一起提交或回滾。</p>
     *
     * @throws IllegalArgumentException seriesUuid 為 null 或查無此 series
     */
    @Override
    public boolean assignToSeriesIfUnassigned(UUID articleUuid, UUID seriesUuid, Integer seriesPosition) {
        if (seriesUuid == null) {
            throw new IllegalArgumentException("seriesUuid 不得為 null");
        }
        Long seriesId = articleDataAssembler.resolveSeriesIdByUuid(seriesUuid)
                .orElseThrow(() -> new IllegalArgumentException("series 不存在: " + seriesUuid));
        return articleMapper.assignToSeriesIfUnassigned(articleUuid, seriesId, seriesPosition, LocalDateTime.now()) == 1;
    }

    /**
     * {@inheritDoc}
     *
     * <p>過渡期：series UUID 先經 SeriesFacade 換成主鍵，再以單一條件式 UPDATE
     * （{@code WHERE uuid = ? AND series_id = ?}）寫入；series 不存在代表文章不可能屬於它，直接回傳 false。</p>
     */
    @Override
    public boolean removeFromSeriesIfAssigned(UUID articleUuid, UUID seriesUuid) {
        return articleDataAssembler.resolveSeriesIdByUuid(seriesUuid)
                .map(seriesId -> articleMapper.removeFromSeriesIfAssigned(articleUuid, seriesId, LocalDateTime.now()) == 1)
                .orElse(false);
    }

    /**
     * {@inheritDoc}
     *
     * <p>過渡期：series UUID 先經 SeriesFacade 換成主鍵，再以單一條件式 UPDATE
     * （{@code WHERE uuid = ? AND series_id = ?}）只改位置；series 不存在代表文章不可能屬於它，直接回傳 false。</p>
     */
    @Override
    public boolean updateSeriesPositionIfAssigned(UUID articleUuid, UUID seriesUuid, Integer seriesPosition) {
        return articleDataAssembler.resolveSeriesIdByUuid(seriesUuid)
                .map(seriesId -> articleMapper.updateSeriesPositionIfAssigned(
                        articleUuid, seriesId, seriesPosition, LocalDateTime.now()) == 1)
                .orElse(false);
    }

    /**
     * {@inheritDoc}
     *
     * <p>過渡期：series UUID 經 {@code SeriesFacade.findIdsByUuids} <b>一次</b>批次換成主鍵
     * （不逐筆），再委派 Long 版計數並以 UUID 為 key 回傳；M6 改以 {@code articles.series_uuid} 直查。</p>
     */
    @Override
    public Map<UUID, Integer> countPublishedBySeriesUuids(Collection<UUID> seriesUuids) {
        Map<UUID, Long> idByUuid = articleDataAssembler.resolveSeriesIdsByUuids(seriesUuids);
        // 與 Long 版一致：結果一律是可變的 HashMap（空結果也是）
        Map<UUID, Integer> result = new HashMap<>();
        if (idByUuid.isEmpty()) {
            return result;
        }
        Map<Long, Integer> countById = countPublishedBySeriesIds(idByUuid.values());
        idByUuid.forEach((uuid, id) -> {
            Integer count = countById.get(id);
            if (count != null) {
                result.put(uuid, count);
            }
        });
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * <p>過渡期：先經 SeriesFacade 換成主鍵再委派 Long 版；series 不存在時回傳 empty。</p>
     */
    @Override
    public Optional<ArticleNavRef> findPrevPublishedInSeries(UUID seriesUuid, Integer currentPosition) {
        return articleDataAssembler.resolveSeriesIdByUuid(seriesUuid)
                .flatMap(seriesId -> findPrevPublishedInSeries(seriesId, currentPosition));
    }

    /**
     * {@inheritDoc}
     *
     * <p>過渡期：先經 SeriesFacade 換成主鍵再委派 Long 版；series 不存在時回傳 empty。</p>
     */
    @Override
    public Optional<ArticleNavRef> findNextPublishedInSeries(UUID seriesUuid, Integer currentPosition) {
        return articleDataAssembler.resolveSeriesIdByUuid(seriesUuid)
                .flatMap(seriesId -> findNextPublishedInSeries(seriesId, currentPosition));
    }
}
