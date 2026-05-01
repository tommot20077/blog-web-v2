package dowob.xyz.blog.infrastructure.facade;

import dowob.xyz.blog.infrastructure.facade.dto.ArticleBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleTrendingData;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文章模組跨模組查詢 Facade 介面
 *
 * <p>
 * 定義推薦模組、搜尋模組等跨模組存取文章資料的合約。
 * 實作由 blog-module-article 提供，透過 Spring DI 注入。
 * 所有方法僅查詢已發布（PUBLISHED）狀態的文章。
 * </p>
 *
 * @author Yuan
 * @version 1.0
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
}
