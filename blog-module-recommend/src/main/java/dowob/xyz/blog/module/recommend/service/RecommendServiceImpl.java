package dowob.xyz.blog.module.recommend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.SearchFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryInfo;
import dowob.xyz.blog.module.recommend.model.dto.response.RecommendArticleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 推薦服務實作
 *
 * <p>
 * 相關文章推薦採三層降級策略：
 * <ol>
 *   <li>同標籤文章（依瀏覽次數降冪）</li>
 *   <li>Elasticsearch more_like_this 相似文章（補充不足）</li>
 *   <li>最新已發布文章（最終降級補充）</li>
 * </ol>
 * 熱門排行從 Redis ZSet 讀取（由 TrendingRefreshJob 定期計算）。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendServiceImpl implements RecommendService {

    /**
     * 文章跨模組 Facade
     */
    private final ArticleFacade articleFacade;

    /**
     * 搜尋跨模組 Facade
     */
    private final SearchFacade searchFacade;

    /**
     * Redis 操作模板
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * JSON 序列化工具
     */
    private final ObjectMapper objectMapper;

    /**
     * 相關文章快取 TTL
     */
    private static final Duration RELATED_CACHE_TTL = Duration.ofHours(1);

    /**
     * 相關文章一律計算並快取的筆數，等於 controller 允許的最大 limit（20）
     */
    static final int RELATED_COMPUTE_LIMIT = 20;

    /**
     * {@inheritDoc}
     *
     * <p>快取 key 不含 limit，因此一律以 {@link #RELATED_COMPUTE_LIMIT}（controller 允許的最大值）計算並快取，
     * 讀取時再切片，避免第一個小 limit 的請求把不完整結果快取 1 小時（PERF-26）。
     * 快取命中後以 {@link ArticleFacade#filterPublishedUuids} 一次查詢即時過濾已刪除／下架的文章，
     * 再切到 limit（DATA-13）。</p>
     */
    @Override
    public List<RecommendArticleResponse> getRelatedArticles(UUID articleUuid, int limit) {
        String cacheKey = RedisKeyConstant.getRelatedKey(articleUuid.toString());

        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                List<RecommendArticleResponse> result = objectMapper.readValue(
                        cached, new TypeReference<>() {});
                return sliceVisible(result, limit);
            }
        } catch (Exception e) {
            log.warn("讀取相關文章快取失敗，articleUuid={}: {}", articleUuid, e.getMessage());
        }

        List<ArticleSummaryInfo> results = computeRelatedArticles(articleUuid, RELATED_COMPUTE_LIMIT);
        List<RecommendArticleResponse> responses = results.stream()
                .map(RecommendArticleResponse::from)
                .toList();

        try {
            String json = objectMapper.writeValueAsString(responses);
            stringRedisTemplate.opsForValue().set(cacheKey, json, RELATED_CACHE_TTL);
        } catch (Exception e) {
            log.warn("寫入相關文章快取失敗，articleUuid={}: {}", articleUuid, e.getMessage());
        }

        return responses.size() > limit ? responses.subList(0, Math.max(limit, 0)) : responses;
    }

    /**
     * 即時過濾非公開文章後再切到 limit（先過濾再切片，前段被下架時仍可由後段補滿）
     *
     * @param cached 快取中的完整結果（保持原順序）
     * @param limit  呼叫端要求的筆數
     * @return 僅含公開文章、不超過 limit 的結果
     */
    private List<RecommendArticleResponse> sliceVisible(List<RecommendArticleResponse> cached, int limit) {
        if (cached.isEmpty()) {
            return cached;
        }
        Set<UUID> published = articleFacade.filterPublishedUuids(
                cached.stream().map(RecommendArticleResponse::uuid).toList());
        return cached.stream()
                .filter(r -> published.contains(r.uuid()))
                .limit(Math.max(limit, 0))
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<RecommendArticleResponse> getTrendingArticles(String period, int limit) {
        String key = RedisKeyConstant.getTrendingKey(period);
        Set<String> uuidStrings = stringRedisTemplate.opsForZSet()
                .reverseRange(key, 0, limit - 1L);

        if (uuidStrings == null || uuidStrings.isEmpty()) {
            return Collections.emptyList();
        }

        List<UUID> uuids = uuidStrings.stream()
                .map(UUID::fromString)
                .toList();

        List<ArticleSummaryInfo> articles = articleFacade.getPublishedArticlesByUuids(uuids);

        return articles.stream()
                .map(RecommendArticleResponse::from)
                .toList();
    }

    /**
     * 執行三層降級策略計算相關文章
     *
     * <p>
     * 每一層均去除與已有結果重複的文章，確保結果不重複。
     * </p>
     *
     * @param articleUuid 目標文章 UUID
     * @param limit       目標筆數
     * @return 相關文章摘要列表
     */
    private List<ArticleSummaryInfo> computeRelatedArticles(UUID articleUuid, int limit) {
        Optional<ArticleBasicInfo> basicInfoOpt = articleFacade.getPublishedArticleBasicInfo(articleUuid);
        if (basicInfoOpt.isEmpty()) {
            return Collections.emptyList();
        }

        ArticleBasicInfo basicInfo = basicInfoOpt.get();
        Set<UUID> seenUuids = new LinkedHashSet<>();
        List<ArticleSummaryInfo> results = new ArrayList<>();

        /** 第一層：同標籤文章 */
        if (!basicInfo.tagIds().isEmpty()) {
            List<ArticleSummaryInfo> byTag = articleFacade.getArticlesByTagIds(
                    basicInfo.tagIds(), articleUuid, limit);
            appendDedup(byTag, results, seenUuids, limit);
        }

        /** 第二層：ES more_like_this 相似文章 */
        if (results.size() < limit) {
            int remaining = limit - results.size();
            List<UUID> similarUuids = searchFacade.findSimilarArticles(articleUuid, remaining * 2);
            List<UUID> newUuids = similarUuids.stream()
                    .filter(u -> !seenUuids.contains(u))
                    .limit(remaining)
                    .toList();

            if (!newUuids.isEmpty()) {
                List<ArticleSummaryInfo> bySimilar = articleFacade.getPublishedArticlesByUuids(newUuids);
                appendDedup(bySimilar, results, seenUuids, limit);
            }
        }

        /** 第三層：最新文章降級補充 */
        if (results.size() < limit) {
            int remaining = limit - results.size();
            List<ArticleSummaryInfo> recent = articleFacade.getRecentPublishedArticles(articleUuid, remaining * 2);
            appendDedup(recent, results, seenUuids, limit);
        }

        return results;
    }

    /**
     * 將來源列表中尚未出現的文章追加至結果集，並記錄已見 UUID
     *
     * @param source    來源文章列表
     * @param results   累積結果列表（會被修改）
     * @param seenUuids 已見 UUID 集合（會被修改）
     * @param limit     上限筆數
     */
    private void appendDedup(
            List<ArticleSummaryInfo> source,
            List<ArticleSummaryInfo> results,
            Set<UUID> seenUuids,
            int limit) {

        for (ArticleSummaryInfo info : source) {
            if (results.size() >= limit) break;
            if (seenUuids.add(info.uuid())) {
                results.add(info);
            }
        }
    }
}
