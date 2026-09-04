package dowob.xyz.blog.infrastructure.facade;

import dowob.xyz.blog.infrastructure.facade.dto.ArticleBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleTrendingData;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 文章模組跨模組 Facade 介面
 *
 * <p>定義跨模組存取文章資料的合約（含 read + simple write）。
 * 實作由 blog-module-article 提供，透過 Spring DI 注入。</p>
 *
 * <p>方法分類：</p>
 * <ul>
 *   <li><b>recommend / search read</b>（findAllPublishedForIndex / getPublishedArticleBasicInfo /
 *       getArticlesByTagIds / getRecentPublishedArticles / getPublishedArticlesByUuids /
 *       getArticlesPublishedAfter）：僅查 PUBLISHED 文章。</li>
 *   <li><b>SP-B 新增 read</b>（findIdByUuid / findByUuid / findById / findByIds /
 *       findBySeriesIdOrderByPosition）：不限狀態，回傳含 status 的 ArticleData，
 *       caller 自行依 status 判斷（如 SeriesService 過濾 DRAFT）。</li>
 *   <li><b>SP-B 新增 simple write</b>（incrementCommentCount / decrementCommentCount /
 *       incrementLikeCount / decrementLikeCount / updateSeriesAssignment）：counter /
 *       欄位 set 類型，直接更新 article 欄位，無業務邏輯觸發。</li>
 *   <li><b>SP-D 新增 read</b>（findContentById）：完整 content 內容 DTO，給 version 模組 snapshot / restore stash 流程用。</li>
 *   <li><b>SP-D 新增 atomic write</b>（applyRestoreContent）：version 模組 restore 用，內部接管 mutate / save / syncArticleTags / publish events 順序。</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.1
 */
public interface ArticleFacade {

    /**
     * 查詢所有已發布文章的索引資料
     *
     * <p>
     * 用於搜尋模組全量重建 Elasticsearch 索引（Reindex）。
     * 僅回傳狀態為 PUBLISHED 的文章，包含完整索引所需欄位。
     * </p>
     *
     * @return 所有已發布文章的索引資料列表
     */
    List<ArticleIndexData> findAllPublishedForIndex();

    /**
     * 在給定的文章 UUID 中，篩出「查詢當下確實是 PUBLISHED」的那些
     *
     * <p>
     * 供搜尋模組清除幽靈 document（ES 有、DB 已非 PUBLISHED）使用。
     * 與 {@link #findAllPublishedForIndex()} 的差別在於**時點**：後者是全量重建開始時的快照，
     * 拿快照當清除判準會把「快照撈完之後才發布、由 MQ 寫進索引」的文章誤判為幽靈刪掉；
     * 本方法是即時查證，因此並發發布的文章會被認出來而保留。
     * </p>
     *
     * <p>{@code articles} 是業務 Data，跨模組不得直接查表（見 {@code ai-docs/architecture.md}），
     * 故此查證由 article 模組提供。實作端會自行切批，caller 可一次傳入一整頁掃描結果。</p>
     *
     * @param uuids 待查證的文章公開 UUID；null 或空集合回傳空集合（不查 DB）
     * @return 其中目前狀態為 PUBLISHED 的 UUID 集合
     */
    Set<UUID> filterPublishedUuids(Collection<UUID> uuids);

    /**
     * 取得已發布文章的基本資訊（標籤列表）
     *
     * <p>用於推薦演算法第一步：取得目標文章的標籤，以查詢同標籤文章。</p>
     *
     * @param articleUuid 文章公開 UUID
     * @return 文章基本資訊，若文章不存在或未發布則回傳 empty
     */
    Optional<ArticleBasicInfo> getPublishedArticleBasicInfo(UUID articleUuid);

    /**
     * 查詢包含指定標籤的已發布文章（依瀏覽次數降冪排序）
     *
     * <p>用於推薦演算法第一層：同標籤文章推薦。</p>
     *
     * @param tagIds      標籤 ID 列表
     * @param excludeUuid 要排除的文章 UUID（避免推薦自身）
     * @param limit       最多回傳筆數
     * @return 文章摘要列表
     */
    List<ArticleSummaryInfo> getArticlesByTagIds(List<UUID> tagIds, UUID excludeUuid, int limit);

    /**
     * 查詢最新已發布文章（依發布時間降冪排序）
     *
     * <p>用於推薦演算法最終降級策略：補充最新文章。</p>
     *
     * @param excludeUuid 要排除的文章 UUID（避免推薦自身）
     * @param limit       最多回傳筆數
     * @return 文章摘要列表
     */
    List<ArticleSummaryInfo> getRecentPublishedArticles(UUID excludeUuid, int limit);

    /**
     * 批次查詢已發布文章（依 UUID 列表）
     *
     * <p>用於將 ES more_like_this 回傳的 UUID 列表轉換為文章摘要資訊。</p>
     *
     * @param uuids 文章 UUID 列表
     * @return 文章摘要列表（順序依資料庫決定）
     */
    List<ArticleSummaryInfo> getPublishedArticlesByUuids(List<UUID> uuids);

    /**
     * 查詢指定時間之後發布的所有文章及其統計資料
     *
     * <p>用於 TrendingRefreshJob 計算熱門排行分數。</p>
     *
     * @param since 起始時間（含）
     * @return 文章熱門計算資料列表
     */
    List<ArticleTrendingData> getArticlesPublishedAfter(LocalDateTime since);

    // ─── SP-B 新增 5 read method ───

    /**
     * UUID → DB id（最高頻跨模組查詢）。
     *
     * @param articleUuid 文章公開 UUID
     * @return 文章資料庫主鍵；查無時 null
     */
    Long findIdByUuid(UUID articleUuid);

    /**
     * UUID → ArticleData（跨模組查 article 元資料）。
     *
     * @param articleUuid 文章公開 UUID
     * @return ArticleData Optional
     */
    Optional<ArticleData> findByUuid(UUID articleUuid);

    /**
     * DB id → ArticleData（給 SeriesFacade.getSeriesNavigation 用 — 解 @Lazy）。
     *
     * @param articleId 文章資料庫主鍵
     * @return ArticleData Optional
     */
    Optional<ArticleData> findById(Long articleId);

    /**
     * 批次 id 查 article（給 ReadingProgressService 用）。
     *
     * @param articleIds 文章主鍵列表
     * @return ArticleData 列表
     */
    List<ArticleData> findByIds(List<Long> articleIds);

    /**
     * 撈 series 內 article 排序好（給 SeriesService.getSeriesDetail 用）。
     *
     * @param seriesId 系列主鍵
     * @return 該 series 內 article 按 series_position 排序
     */
    List<ArticleData> findBySeriesIdOrderByPosition(Long seriesId);

    // ─── SP-B 新增 5 write method（counter / 欄位 set，simple write）───

    /**
     * comment 模組創建 comment 時連動 article.comment_count + 1。
     */
    void incrementCommentCount(Long articleId);

    /**
     * comment 模組刪除 comment 時連動 article.comment_count - 1。
     */
    void decrementCommentCount(Long articleId);

    /**
     * reading 模組 like article 時連動 article.like_count + 1。
     */
    void incrementLikeCount(Long articleId);

    /**
     * reading 模組 unlike article 時連動 article.like_count - 1。
     */
    void decrementLikeCount(Long articleId);

    /**
     * series 模組 add / remove article from series 時 set article.series_id + series_position。
     *
     * @param articleId      文章主鍵
     * @param seriesId       系列主鍵（remove 時傳 null）
     * @param seriesPosition 在 series 內的位置（remove 時傳 null）
     */
    void updateSeriesAssignment(Long articleId, Long seriesId, Integer seriesPosition);

    // ─── SP-D 新增 1 read ───

    /**
     * DB id → ArticleContentData（給 VersioningService stash 流程 + AutoSnapshotPolicy 用）。
     *
     * <p>比 findById（return ArticleData）多含 title / slug / content / summary / coverImageUrl 等欄位。</p>
     *
     * @param articleId 文章資料庫主鍵
     * @return ArticleContentData Optional；查無時 empty
     */
    Optional<ArticleContentData> findContentById(Long articleId);

    // ─── SP-D 新增 1 atomic write ───

    /**
     * 還原 article 內容到指定版本（atomic）。
     *
     * <p>內部完整流程：</p>
     * <ol>
     *   <li>撈 Article entity（不存在 throw ARTICLE_NOT_FOUND）</li>
     *   <li>mutate 7 個內容欄位（title / slug / content / summary / coverImageUrl / contentHtml / toc）</li>
     *   <li>save Article</li>
     *   <li>syncArticleTags — 必須在 publish events 之前</li>
     *   <li>publishContentChanged(article, RESTORED)</li>
     *   <li>若 article.status == PUBLISHED：publishUpdated(article)</li>
     * </ol>
     *
     * <p><strong>SEC-02：不會改動 article.status</strong>。還原只還原內容，狀態轉換的唯一真相是
     * {@code ArticleCommandSubService.VALID_TRANSITIONS}；上述步驟 6 的判準是「文章現在的狀態」，
     * 與快照當時的狀態無關。</p>
     *
     * <p>caller 不需要再 inject ArticleEventPublisher / TagFacade write methods，
     * 也不會看到 Article entity。</p>
     *
     * @param articleId 文章資料庫主鍵
     * @param data      還原所需資料（含 tags）
     * @throws dowob.xyz.blog.common.exception.BusinessException ARTICLE_NOT_FOUND 若 articleId 對應 article 不存在
     */
    void applyRestoreContent(Long articleId, ArticleRestoreData data);
}
