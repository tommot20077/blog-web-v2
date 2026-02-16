package dowob.xyz.blog.module.recommend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
     * 相關文章快取 Key 前綴
     */
    static final String RELATED_CACHE_KEY_PREFIX = "recommend:related:";

    /**
     * 熱門排行 ZSet Key 前綴
     */
    static final String TRENDING_KEY_PREFIX = "recommend:trending:";

    /**
     * 相關文章快取 TTL
     */
    private static final Duration RELATED_CACHE_TTL = Duration.ofHours(1);

    /**
     * {@inheritDoc}
     *
     * <p>優先從 Redis 快取讀取，快取未命中時執行三層策略並寫入快取。</p>
     */
    @Override
    public List<RecommendArticleResponse> getRelatedArticles(UUID articleUuid, int limit) {
        String cacheKey = RELATED_CACHE_KEY_PREFIX + articleUuid;

        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                List<RecommendArticleResponse> result = objectMapper.readValue(
                        cached, new TypeReference<>() {});
                return result.size() > limit ? result.subList(0, limit) : result;
            }
        } catch (Exception e) {
            log.warn("讀取相關文章快取失敗，articleUuid={}: {}", articleUuid, e.getMessage());
        }

        List<ArticleSummaryInfo> results = computeRelatedArticles(articleUuid, limit);
        List<RecommendArticleResponse> responses = results.stream()
                .map(RecommendArticleResponse::from)
                .toList();

        try {
            String json = objectMapper.writeValueAsString(responses);
            stringRedisTemplate.opsForValue().set(cacheKey, json, RELATED_CACHE_TTL);
        } catch (Exception e) {
            log.warn("寫入相關文章快取失敗，articleUuid={}: {}", articleUuid, e.getMessage());
        }

        return responses;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<RecommendArticleResponse> getTrendingArticles(String period, int limit) {
        String key = TRENDING_KEY_PREFIX + period;
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
            List<UUID> similarUuids = searchFacade.findSimilarArticles(articleUuid, limit);
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
