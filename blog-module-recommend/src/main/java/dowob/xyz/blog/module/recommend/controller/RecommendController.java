package dowob.xyz.blog.module.recommend.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.module.recommend.model.dto.response.RecommendArticleResponse;
import dowob.xyz.blog.module.recommend.service.RecommendService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 推薦文章 REST API 控制器
 *
 * <p>
 * 提供相關文章推薦與熱門文章排行兩個公開端點，
 * 均無需認證即可存取。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Tag(name = "推薦")
@RestController
@RequestMapping("/api/v1/recommend")
@RequiredArgsConstructor
public class RecommendController {

    /**
     * 推薦服務
     */
    private final RecommendService recommendService;

    /**
     * 取得指定文章的相關推薦文章列表
     *
     * <p>依同標籤 → ES 相似度 → 最新文章的三層策略推薦，結果快取 1 小時。</p>
     *
     * @param articleUuid 目標文章公開 UUID
     * @param limit       最多回傳筆數（預設 5，最大 20）
     * @return 推薦文章列表
     */
    @Operation(summary = "相關文章推薦",
            description = "公開。回傳與指定文章相關的已發布文章；limit 夾在 1 到 20。文章不存在時回空清單。")
    @GetMapping("/related/{articleUuid}")
    public ApiResponse<List<RecommendArticleResponse>> getRelatedArticles(
            @PathVariable UUID articleUuid,
            @RequestParam(defaultValue = "5") int limit) {

        int safeLimit = Math.min(Math.max(limit, 1), 20);
        List<RecommendArticleResponse> result = recommendService.getRelatedArticles(articleUuid, safeLimit);
        return ApiResponse.success(result);
    }

    /**
     * 取得指定週期的熱門文章排行列表
     *
     * <p>從 Redis ZSet 讀取預先計算的時間衰減分數排行，每 30 分鐘更新一次。</p>
     *
     * @param period 時間週期（{@code 24h}、{@code 7d}、{@code 30d}，預設 {@code 7d}）
     * @param limit  最多回傳筆數（預設 10，最大 50）
     * @return 熱門文章列表
     */
    @Operation(summary = "熱門文章",
            description = "公開。回傳指定期間（預設 7d）的熱門文章；limit 夾在 1 到 50。")
    @GetMapping("/trending")
    public ApiResponse<List<RecommendArticleResponse>> getTrendingArticles(
            @RequestParam(defaultValue = "7d") String period,
            @RequestParam(defaultValue = "10") int limit) {

        int safeLimit = Math.min(Math.max(limit, 1), 50);
        List<RecommendArticleResponse> result = recommendService.getTrendingArticles(period, safeLimit);
        return ApiResponse.success(result);
    }
}
