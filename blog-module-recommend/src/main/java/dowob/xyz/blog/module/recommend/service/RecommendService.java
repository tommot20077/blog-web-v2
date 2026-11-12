package dowob.xyz.blog.module.recommend.service;

import dowob.xyz.blog.module.recommend.model.dto.response.RecommendArticleResponse;

import java.util.List;
import java.util.UUID;

/**
 * 推薦服務介面
 *
 * <p>
 * 定義文章推薦功能的業務合約，包含相關文章推薦與熱門文章排行。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface RecommendService {

    /**
     * 取得指定文章的相關推薦文章
     *
     * <p>
     * 依序嘗試以下策略直到累積足夠結果：
     * <ol>
     *   <li>同標籤文章（依瀏覽次數降冪）</li>
     *   <li>Elasticsearch more_like_this 相似文章</li>
     *   <li>最新已發布文章（降級補充）</li>
     * </ol>
     * 結果快取於 Redis {@code recommend:related:{articleUuid}}，TTL 1 小時。
     * </p>
     *
     * @param articleUuid 目標文章公開 UUID
     * @param limit       最多回傳筆數
     * @return 推薦文章列表
     */
    List<RecommendArticleResponse> getRelatedArticles(UUID articleUuid, int limit);

    /**
     * 取得指定週期的熱門文章排行
     *
     * <p>
     * 從 Redis ZSet {@code recommend:trending:{period}} 讀取預先計算的熱門分數排行。
     * 若 ZSet 不存在（排程尚未執行），回傳空列表。
     * </p>
     *
     * @param period 時間週期（{@code 24h}、{@code 7d}、{@code 30d}）
     * @param limit  最多回傳筆數
     * @return 熱門文章列表
     */
    List<RecommendArticleResponse> getTrendingArticles(String period, int limit);
}
