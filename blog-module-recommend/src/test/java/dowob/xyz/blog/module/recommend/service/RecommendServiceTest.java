package dowob.xyz.blog.module.recommend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.SearchFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryInfo;
import dowob.xyz.blog.module.recommend.model.dto.response.RecommendArticleResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RecommendServiceImpl 單元測試
 *
 * <p>驗證三層降級策略邏輯、去重、limit 計算與 Redis 快取操作。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecommendServiceTest {

    @Mock
    private ArticleFacade articleFacade;

    @Mock
    private SearchFacade searchFacade;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private RecommendServiceImpl service;

    private static final UUID ARTICLE_UUID = UUID.randomUUID();
    private static final Long TAG_ID_1 = 1L;
    private static final Long TAG_ID_2 = 2L;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new RecommendServiceImpl(articleFacade, searchFacade, stringRedisTemplate, objectMapper);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
    }

    private ArticleSummaryInfo summary(UUID uuid, String title) {
        return new ArticleSummaryInfo(uuid, title, "slug-" + title, "summary",
                "Author", List.of("tag"), 100L, 10L, LocalDateTime.now());
    }

    @Nested
    class GetRelatedArticles {

        @Test
        @DisplayName("當文章不存在時回傳空列表")
        void whenArticleNotFound_returnsEmptyList() {
            when(articleFacade.getPublishedArticleBasicInfo(ARTICLE_UUID)).thenReturn(Optional.empty());

            List<RecommendArticleResponse> result = service.getRelatedArticles(ARTICLE_UUID, 5);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("同標籤文章足夠時不查 ES 和最新文章")
        void whenTagArticlesSufficient_skipsEsAndRecentQuery() {
            ArticleBasicInfo basicInfo = new ArticleBasicInfo(ARTICLE_UUID, List.of(TAG_ID_1));
            UUID uuid1 = UUID.randomUUID();
            UUID uuid2 = UUID.randomUUID();

            when(articleFacade.getPublishedArticleBasicInfo(ARTICLE_UUID)).thenReturn(Optional.of(basicInfo));
            when(articleFacade.getArticlesByTagIds(anyList(), eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(List.of(summary(uuid1, "A"), summary(uuid2, "B")));

            List<RecommendArticleResponse> result = service.getRelatedArticles(ARTICLE_UUID, 2);

            assertThat(result).hasSize(2);
            verify(searchFacade, never()).findSimilarArticles(any(), anyInt());
            verify(articleFacade, never()).getRecentPublishedArticles(any(), anyInt());
        }

        @Test
        @DisplayName("同標籤不足時補充 ES 相似文章")
        void whenTagArticlesInsufficient_supplementsWithEsSimilar() {
            ArticleBasicInfo basicInfo = new ArticleBasicInfo(ARTICLE_UUID, List.of(TAG_ID_1));
            UUID tagArticleUuid = UUID.randomUUID();
            UUID esUuid = UUID.randomUUID();

            when(articleFacade.getPublishedArticleBasicInfo(ARTICLE_UUID)).thenReturn(Optional.of(basicInfo));
            when(articleFacade.getArticlesByTagIds(anyList(), eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(List.of(summary(tagArticleUuid, "TagA")));
            when(searchFacade.findSimilarArticles(eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(List.of(esUuid));
            when(articleFacade.getPublishedArticlesByUuids(List.of(esUuid)))
                    .thenReturn(List.of(summary(esUuid, "EsB")));

            List<RecommendArticleResponse> result = service.getRelatedArticles(ARTICLE_UUID, 3);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(RecommendArticleResponse::uuid)
                    .containsExactlyInAnyOrder(tagArticleUuid, esUuid);
        }

        @Test
        @DisplayName("ES 查詢傳入 remaining 乘以 2 而非完整 limit")
        void esQuery_receivesRemainingTimesTwo() {
            /** limit=5，第一層取得1篇，remaining=4，ES 應被呼叫時傳入 4*2=8 */
            ArticleBasicInfo basicInfo = new ArticleBasicInfo(ARTICLE_UUID, List.of(TAG_ID_1));
            UUID tagUuid = UUID.randomUUID();

            when(articleFacade.getPublishedArticleBasicInfo(ARTICLE_UUID)).thenReturn(Optional.of(basicInfo));
            when(articleFacade.getArticlesByTagIds(anyList(), eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(List.of(summary(tagUuid, "TagA")));
            when(searchFacade.findSimilarArticles(eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(Collections.emptyList());

            service.getRelatedArticles(ARTICLE_UUID, 5);

            /** remaining = 5 - 1 = 4, 預期傳入 4 * 2 = 8 */
            verify(searchFacade).findSimilarArticles(eq(ARTICLE_UUID), eq(8));
        }

        @Test
        @DisplayName("仍不足時補充最新文章")
        void whenStillInsufficient_supplementsWithRecentArticles() {
            ArticleBasicInfo basicInfo = new ArticleBasicInfo(ARTICLE_UUID, Collections.emptyList());
            UUID recentUuid = UUID.randomUUID();

            when(articleFacade.getPublishedArticleBasicInfo(ARTICLE_UUID)).thenReturn(Optional.of(basicInfo));
            when(searchFacade.findSimilarArticles(ARTICLE_UUID, 5))
                    .thenReturn(Collections.emptyList());
            when(articleFacade.getRecentPublishedArticles(eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(List.of(summary(recentUuid, "Recent")));

            List<RecommendArticleResponse> result = service.getRelatedArticles(ARTICLE_UUID, 5);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).uuid()).isEqualTo(recentUuid);
        }

        @Test
        @DisplayName("結果去重不重複出現相同文章")
        void deduplicatesResultsSoSameArticleAppearsOnce() {
            ArticleBasicInfo basicInfo = new ArticleBasicInfo(ARTICLE_UUID, List.of(TAG_ID_1));
            UUID sharedUuid = UUID.randomUUID();

            when(articleFacade.getPublishedArticleBasicInfo(ARTICLE_UUID)).thenReturn(Optional.of(basicInfo));
            when(articleFacade.getArticlesByTagIds(anyList(), eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(List.of(summary(sharedUuid, "Shared")));
            when(searchFacade.findSimilarArticles(ARTICLE_UUID, 5))
                    .thenReturn(List.of(sharedUuid));

            List<RecommendArticleResponse> result = service.getRelatedArticles(ARTICLE_UUID, 5);

            long countShared = result.stream()
                    .filter(r -> r.uuid().equals(sharedUuid))
                    .count();
            assertThat(countShared).isEqualTo(1);
        }

        @Test
        @DisplayName("快取命中時直接回傳快取結果")
        void whenCacheHit_returnsCachedResult() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            List<RecommendArticleResponse> cached = List.of(
                    new RecommendArticleResponse(UUID.randomUUID(), "T", "s", "sum",
                            "A", List.of(), 1L, 0L, LocalDateTime.now())
            );
            String cacheJson = mapper.writeValueAsString(cached);

            when(valueOperations.get(RecommendServiceImpl.RELATED_CACHE_KEY_PREFIX + ARTICLE_UUID))
                    .thenReturn(cacheJson);

            List<RecommendArticleResponse> result = service.getRelatedArticles(ARTICLE_UUID, 5);

            assertThat(result).hasSize(1);
            verify(articleFacade, never()).getPublishedArticleBasicInfo(any());
        }
    }

    @Nested
    class GetTrendingArticles {

        @Test
        @DisplayName("ZSet 為空時回傳空列表")
        void whenZSetEmpty_returnsEmptyList() {
            when(zSetOperations.reverseRange(anyString(), eq(0L), eq(9L)))
                    .thenReturn(Collections.emptySet());

            List<RecommendArticleResponse> result = service.getTrendingArticles("7d", 10);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常回傳 ZSet 中的熱門文章")
        void returnsTrendingArticlesFromZSet() {
            UUID uuid1 = UUID.randomUUID();
            when(zSetOperations.reverseRange(
                    eq(RecommendServiceImpl.TRENDING_KEY_PREFIX + "7d"), eq(0L), eq(4L)))
                    .thenReturn(new java.util.LinkedHashSet<>(List.of(uuid1.toString())));
            when(articleFacade.getPublishedArticlesByUuids(List.of(uuid1)))
                    .thenReturn(List.of(summary(uuid1, "Hot")));

            List<RecommendArticleResponse> result = service.getTrendingArticles("7d", 5);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).uuid()).isEqualTo(uuid1);
        }
    }
}
