package dowob.xyz.blog.infrastructure.facade;

import dowob.xyz.blog.infrastructure.facade.dto.ArticleBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleNavRef;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreSource;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryResponse;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleTrendingData;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 文章模組跨模組 Facade 介面
 *
 * <p>定義跨模組存取文章資料的合約（含 read + simple write）。
 * 實作由 blog-module-article 提供，透過 Spring DI 注入。</p>
 *
 * <p>跨模組識別一律是公開 UUID（文章、系列、使用者）；facade 介面不暴露任何資料庫 Long 主鍵。
 * 方法分類：</p>
 * <ul>
 *   <li><b>recommend / search read</b>（findAllPublishedForIndex / getPublishedArticleBasicInfo /
 *       getArticlesByTagIds / getRecentPublishedArticles / getPublishedArticlesByUuids /
 *       getArticlesPublishedAfter）：僅查 PUBLISHED 文章。</li>
 *   <li><b>不限狀態的 read</b>（findByUuid / findBySeriesUuidOrderByPosition / findContentByUuid / existsByUuid）：
 *       回傳含 status 的 DTO，caller 自行依 status 判斷（如 SeriesService 過濾 DRAFT）。</li>
 *   <li><b>reading 用</b>（getSummariesByUuids / filterReadableUuids / incrementLikeCount / decrementLikeCount）。</li>
 *   <li><b>comment 用 simple write</b>（incrementCommentCount / decrementCommentCount）：counter 原子增減，
 *       無業務邏輯觸發。</li>
 *   <li><b>series 用條件式寫入</b>（assignToSeriesIfUnassigned / removeFromSeriesIfAssigned /
 *       updateSeriesPositionIfAssigned）：由 affected rows 回報「這次呼叫是否真的改變了歸屬」，
 *       讓 series 的 article_count 不必先讀再寫（RACE-14）；直接查寫 {@code articles.series_uuid}。</li>
 *   <li><b>version 用 atomic write</b>（applyRestoreContent）：內部接管渲染 / mutate / save /
 *       syncArticleTags / publish events 順序，caller 只交出 Markdown 原文。</li>
 * </ul>
 *
 * @author Yuan
 * @version 2.0（移除所有 Long 主鍵版方法）
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

    // ─── 不限狀態的 read ───

    /**
     * UUID → ArticleData（跨模組查 article 元資料）。
     *
     * @param articleUuid 文章公開 UUID
     * @return ArticleData Optional
     */
    Optional<ArticleData> findByUuid(UUID articleUuid);

    /**
     * 撈 series 內 article 排序好（給 SeriesService.getSeriesDetail 用）。
     *
     * <p>直接以 {@code articles.series_uuid} 查詢。不限文章狀態；系列沒有任何成員（含系列不存在）時回傳空清單。</p>
     *
     * @param seriesUuid 系列公開 UUID；null 視為查無，回傳空清單
     * @return 該 series 內 article 按 series_position 排序；查無成員時為空清單
     */
    List<ArticleData> findBySeriesUuidOrderByPosition(UUID seriesUuid);

    // ─── comment / reading 用的 counter write（simple write）───

    /**
     * 文章是否存在，<b>不分狀態</b>（UUID 版）。
     *
     * <p>DRAFT、PENDING、REJECTED、PUBLISHED、ARCHIVED 一律算存在，僅被硬刪（查無此列）者回傳 false。
     * 供 comment 模組建立留言時的存在性檢查：留言表已無指向 {@code articles} 的 FK，
     * 這個檢查是「不對不存在的文章寫入留言」的唯一防線。</p>
     *
     * @param articleUuid 文章公開 UUID；{@code null} 回傳 false
     * @return 文章存在為 true
     */
    boolean existsByUuid(UUID articleUuid);

    /**
     * comment 模組創建 comment 時連動 article.comment_count + 1。
     *
     * <p>原子 +1；文章不存在時不影響任何列（no-op）。</p>
     *
     * @param articleUuid 文章公開 UUID
     */
    void incrementCommentCount(UUID articleUuid);

    /**
     * comment 模組刪除 comment 時連動 article.comment_count - 1。
     *
     * <p>原子 -1，守衛 {@code comment_count > 0}（防 underflow）；文章不存在時不影響任何列（no-op）。</p>
     *
     * @param articleUuid 文章公開 UUID
     */
    void decrementCommentCount(UUID articleUuid);

    /**
     * reading 模組 like article 時連動 article.like_count + 1。
     *
     * <p>原子 +1；文章不存在時不影響任何列（no-op），不拋例外。</p>
     *
     * @param articleUuid 文章公開 UUID
     */
    void incrementLikeCount(UUID articleUuid);

    /**
     * reading 模組 unlike article 時連動 article.like_count - 1。
     *
     * <p>原子 -1，守衛 {@code like_count > 0}（防 underflow）；文章不存在時不影響任何列（no-op），不拋例外。</p>
     *
     * @param articleUuid 文章公開 UUID
     */
    void decrementLikeCount(UUID articleUuid);

    /**
     * 條件式加入 series：僅當文章<b>目前不屬於任何 series</b> 時才指派，並回報這次寫入是否真的指派了。
     *
     * <p>為什麼需要：series 模組以 {@code article_count} 記錄成員數，必須知道「這次呼叫是不是新成員」。
     * 先讀 {@code seriesUuid} 再決定是否 +1 是 check-then-act（RACE-14）——兩個請求同時加入同一篇文章，
     * 都讀到「無 series」，都 +1，計數多出 1。本方法把判斷放進寫入本身：
     * {@code UPDATE articles SET series_uuid = ?, series_position = ? WHERE uuid = ? AND series_uuid IS NULL}，
     * 由 affected rows 決定。同一時間只有一個交易能把 NULL 改成非 NULL，另一個得到 {@code false}。</p>
     *
     * <p>回傳 {@code false} 代表文章不存在，或<b>已屬於某個 series（含同一個）</b>；呼叫端須自行區分
         * 只更新位置）。寫入時一併遞增樂觀鎖 {@code version} 並更新 {@code updated_at}，與實體 save 的效果一致，
     * 使並行中的文章編輯（讀到舊 series 歸屬）會因版本不符而失敗，不會把舊歸屬寫回去。</p>
     *
     * <p>直接寫入 {@code articles.series_uuid}；article 不檢查系列是否存在
     * （系列存在性由呼叫端 series 模組負責，V32 起沒有 FK 把關）。呼叫端若在寫入後發現系列已被併發刪除
     * （例如 {@code article_count + 1} 影響 0 列），須回滾交易，避免留下指向已不存在系列的歸屬。</p>
     *
     * @param articleUuid    文章公開 UUID
     * @param seriesUuid     要加入的系列公開 UUID（不得為 null）
     * @param seriesPosition 在系列內的位置
     * @return {@code true} 表示這次呼叫把文章從「無 series」改為屬於該系列；{@code false} 表示沒有任何寫入
     * @throws IllegalArgumentException seriesUuid 為 null（文章不會被改動）
     */
    boolean assignToSeriesIfUnassigned(UUID articleUuid, UUID seriesUuid, Integer seriesPosition);

    /**
     * 條件式移出 series：僅當文章<b>目前屬於指定的 series</b> 時才移出，並回報這次寫入是否真的移出了。
     *
     * <p>與 {@link #assignToSeriesIfUnassigned} 對稱，解 RACE-14 的移出面：
     * {@code UPDATE articles SET series_uuid = NULL, series_position = NULL WHERE uuid = ? AND series_uuid = ?}，
     * 由 affected rows 決定 series 模組是否 {@code article_count - 1}。兩個請求同時移出同一篇文章，
     * 只有一個得到 {@code true}。寫入時一併遞增 {@code version} 並更新 {@code updated_at}。</p>
     *
     * @param articleUuid 文章公開 UUID
     * @param seriesUuid  文章應屬於的系列公開 UUID；null 視為「不屬於」
     * @return {@code true} 表示這次呼叫把文章從該系列移出；{@code false} 表示沒有任何寫入
     *         （文章不存在、不屬於該系列，或已被另一個請求移出）
     */
    boolean removeFromSeriesIfAssigned(UUID articleUuid, UUID seriesUuid);

    /**
     * 條件式更新位置：僅當文章<b>目前屬於指定的 series</b> 時才改 {@code series_position}。
     *
     * <p>供 series 模組處理「文章已在同一個 series，只更新位置」：以單一
     * {@code UPDATE articles SET series_position = ? WHERE uuid = ? AND series_uuid = ?} 完成，
     * 取代「讀文章、改欄位、整列 save」的讀改寫——後者在兩個請求同時更新位置時
     * 會因樂觀鎖衝突失敗，與併發移出交錯時還可能把文章重新寫回 series 而沒有 +1 計數（RACE-14）。
     * 寫入時一併遞增 {@code version} 並更新 {@code updated_at}。</p>
     *
     * @param articleUuid    文章公開 UUID
     * @param seriesUuid     文章應屬於的系列公開 UUID；null 視為「不屬於」
     * @param seriesPosition 新的位置
     * @return {@code true} 表示位置已更新；{@code false} 表示沒有任何寫入
     *         （文章不存在、不屬於該系列，或已被另一個請求移出／移到別的系列）
     */
    boolean updateSeriesPositionIfAssigned(UUID articleUuid, UUID seriesUuid, Integer seriesPosition);

    /**
     * 公開 UUID → ArticleContentData（含 title / slug / content / summary / coverImageUrl 等完整內容，
     * 給 version 模組 snapshot / restore stash 流程用）。
     *
     * <p><b>不分狀態</b>（DRAFT、PENDING_REVIEW、REJECTED、PUBLISHED、ARCHIVED 皆回傳），
     * 查無（含已硬刪）回傳 empty。回傳的 {@code authorUuid} 直接取自
     * {@code articles.author_uuid}，必定有值（該欄位為 NOT NULL）。</p>
     *
     * @param articleUuid 文章公開 UUID；{@code null} 回傳 empty
     * @return ArticleContentData Optional；查無時 empty
     */
    Optional<ArticleContentData> findContentByUuid(UUID articleUuid);

    // ─── version 用 atomic write ───

    /**
     * 還原 article 內容到指定快照（atomic）。
     *
     * <p>以公開 UUID 定位文章；caller 只交出 Markdown 原文（{@link ArticleRestoreSource}），
     * 由 article 模組在內部以 {@code ArticleMarkdownRenderer} 渲染，<b>HTML 與 TOC 出自同一次 render</b>
     * （只回填 HTML 會讓 {@code articles.toc} 停在還原前那版，章節導覽指向不存在的錨點）。
     * caller 不需要（也不應該）import 渲染器、ArticleEventPublisher 或 TagFacade 的寫入方法，也不會看到 Article entity。</p>
     *
     * <p>流程：單一交易內撈文章（不存在 throw ARTICLE_NOT_FOUND）→ 內容凍結守衛（F-H1：
     * PENDING_REVIEW / PUBLISHED / ARCHIVED 一律 throw ARTICLE_EDIT_NOT_ALLOWED，與
     * {@code PUT /api/v1/articles/{uuid}} 同一套判斷，caller 不需自行判斷文章狀態）→ mutate 內容欄位
     * → save → syncArticleTags（必須在發事件之前），commit 後才發 ContentChanged(RESTORED)
     * （文章現況為 PUBLISHED 時另發 Updated；在目前的凍結政策下該分支不可達，保留為防禦性程式碼），
     * 並重新綁定「檔案 → 文章」。<b>SEC-02：不會改動 article.status</b>——還原只還原內容，
     * 狀態轉換的唯一真相是 {@code ArticleCommandSubService.VALID_TRANSITIONS}。</p>
     *
     * <p><b>null 契約</b>：{@code articleUuid}、{@code source}、{@code source.content()} 任一為 null
     * 拋帶訊息的 {@link NullPointerException}（呼叫端程式錯誤），不渲染、不寫入。
     * 對照 {@link #findContentByUuid(UUID)}：傳入 null 回 empty。</p>
     *
     * @param articleUuid 文章公開 UUID；不可為 null
     * @param source      快照內容（含 tags，不含 contentHtml／toc）；不可為 null，content 亦不可為 null
     * @throws NullPointerException 上述 null 契約被違反
     * @throws dowob.xyz.blog.common.exception.BusinessException ARTICLE_NOT_FOUND 若 articleUuid 對應 article 不存在；
     *                                                          ARTICLE_EDIT_NOT_ALLOWED 若文章目前狀態內容凍結
     */
    void applyRestoreContent(UUID articleUuid, ArticleRestoreSource source);

    // ─── 集合述詞（Set Predicate，architecture.md §跨模組邊界規則）───

    /**
     * 集合述詞：一次取得多個 series 各自的 PUBLISHED 文章數。
     *
     * <p>供 series 模組同時作為「哪些 series 有公開內容」的過濾條件與
     * {@code article_count} 的即時投影，取代原本直讀 articles 的
     * EXISTS ＋ COUNT 子查詢（ARCH-13 / PERF-34）。直接以 {@code articles.series_uuid} 分組計數。</p>
     *
     * <p><b>傳輸量界限</b>：與 {@code seriesUuids} 大小成正比。series 為低基數實體
     * （部落格量級為數十），可接受。<b>重評門檻：series 總數 > 5,000。</b></p>
     *
     * @param seriesUuids series 公開 UUID 集合；null 或空集合回傳空 Map（不查 DB）；null 元素會被忽略
     * @return series 公開 UUID → PUBLISHED 文章數；<b>只含 count &gt; 0 者</b>，回傳可變的 Map
     */
    Map<UUID, Integer> countPublishedBySeriesUuids(Collection<UUID> seriesUuids);

    /**
     * series 內 PUBLISHED 且位置小於 current 的最後一篇（prev 導覽）。
     *
     * <p>直接以 {@code articles.series_uuid} 查詢。</p>
     *
     * @param seriesUuid      series 公開 UUID；null 回傳 {@code Optional.empty()}
     * @param currentPosition 當前文章在 series 內的位置
     * @return 前一篇；當前為第一篇或系列沒有成員時為 {@code Optional.empty()}
     */
    Optional<ArticleNavRef> findPrevPublishedInSeries(UUID seriesUuid, Integer currentPosition);

    /**
     * series 內 PUBLISHED 且位置大於 current 的第一篇（next 導覽）。
     *
     * <p>直接以 {@code articles.series_uuid} 查詢。</p>
     *
     * @param seriesUuid      series 公開 UUID；null 回傳 {@code Optional.empty()}
     * @param currentPosition 當前文章在 series 內的位置
     * @return 後一篇；當前為最後一篇或系列沒有成員時為 {@code Optional.empty()}
     */
    Optional<ArticleNavRef> findNextPublishedInSeries(UUID seriesUuid, Integer currentPosition);

    /**
     * 集合述詞：篩出 candidates 中「對該 viewer 可見」者，<b>維持輸入順序</b>。
     *
     * <p>可見性委派 {@code blog-common} 的 {@code ArticleVisibility}（單一真相）——PUBLISHED 全公開；
     * 非 PUBLISHED 僅作者本人與 ADMIN；查無此 uuid（已硬刪）者不會出現在結果中。
     * 作者以 UUID 比對（直接取自 {@code articles.author_uuid}）。</p>
     *
     * <p><b>傳輸量界限</b>：與 {@code candidates} 大小成正比，非與頁大小成正比；實作端經 {@code BatchedQuery} 切批。
     * <b>重評門檻：任一使用者 B &gt; 5,000。</b></p>
     *
     * @param candidates 候選文章公開 UUID（依 caller 期望的顯示順序）；null 或空清單回傳空清單（不查 DB）
     * @param viewerUuid 檢視者公開 UUID；匿名為 null
     * @param isAdmin    檢視者是否為 ADMIN
     * @return 可見的文章 UUID，順序與輸入一致
     */
    List<UUID> filterReadableUuids(List<UUID> candidates, UUID viewerUuid, boolean isAdmin);

    /**
     * 依文章公開 UUID 批次取得文章摘要，<b>輸出順序與輸入相同</b>。
     *
     * <p>語意與 article 模組內部 {@code ArticleQueryService} 的批次摘要查詢完全相同：
     * 含目前登入者的 liked／bookmarked／lastReadProgress 補值（未登入時 liked／bookmarked 為 false、
     * lastReadProgress 為 null），以及 series 導覽（seriesUuid／seriesTitle）補值；全程批次查詢，不隨文章數 N+1。</p>
     *
     * <p><b>可見性</b>：本方法為物件級讀取，<b>不過濾狀態</b>（status-agnostic，見 {@code architecture.md}
     * 的兩層契約），caller 須先以 {@link #filterReadableUuids} 篩出可見者再傳入。</p>
     *
     * <p>輸入中重複的 UUID 只保留第一次出現的位置；查無的 UUID 與 null 元素略過。</p>
     *
     * @param uuids 文章公開 UUID（依 caller 期望的顯示順序）；null 或空清單回傳空清單（不查 DB）
     * @return 文章摘要，順序與輸入一致，查無者略過
     */
    List<ArticleSummaryResponse> getSummariesByUuids(List<UUID> uuids);

    /**
     * 篩出 uuids 中「仍存在」的文章 UUID，<b>不分狀態</b>。
     *
     * <p>與 {@link #filterPublishedUuids(Collection)} 的差別：後者只認 PUBLISHED，
     * 本方法只問「這篇文章還在不在」——DRAFT、PENDING、REJECTED、PUBLISHED 一律算存在，
     * 僅有被硬刪（查無此列）者不會出現在結果中。供其他模組的每日孤兒校正排程使用：
     * 校正的判準是「owner 是否已刪除」，若誤用 {@code filterPublishedUuids}，
     * 草稿文章的相關資料會被當成孤兒清掉。</p>
     *
     * <p>實作端會自行去重並經 {@code BatchedQuery} 切批，caller 可一次傳入任意數量。</p>
     *
     * @param uuids 待查證的文章公開 UUID；null 或空集合回傳空集合（不查 DB）；null 元素會被忽略
     * @return 其中仍存在於 articles 表的 UUID 集合（不分狀態）
     */
    Set<UUID> filterExistingUuids(Collection<UUID> uuids);
}
