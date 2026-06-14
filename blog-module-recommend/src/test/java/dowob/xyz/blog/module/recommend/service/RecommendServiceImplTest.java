package dowob.xyz.blog.module.recommend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RecommendServiceImpl 補充單元測試
 *
 * <p>
 * 與既有 {@link RecommendServiceTest} 互補，聚焦於先前未覆蓋的邊界與分支：
 * trending 的 limit 邊界 / null 回傳 / ZSet 索引計算、related 的快取裁切 /
 * 快取反序列化失敗降級 / 快取寫入、三層降級的 remaining*2 參數、跨層去重、
 * limit 截斷與全空結果。
 * </p>
 *
 * <p>所有外部相依（Redis、ES Facade、Article Facade）均以 Mockito mock。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecommendServiceImplTest {

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

    private ObjectMapper objectMapper;

    private RecommendServiceImpl service;

    private static final UUID ARTICLE_UUID = UUID.randomUUID();
    private static final UUID TAG_ID_1 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new RecommendServiceImpl(articleFacade, searchFacade, stringRedisTemplate, objectMapper);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
    }

    private ArticleSummaryInfo summary(UUID uuid, String title) {
        return new ArticleSummaryInfo(uuid, title, "slug-" + title, "summary-" + title,
                "Author", List.of("tag"), 100L, 10L, LocalDateTime.now());
    }

    /** 產生 n 個帶隨機 UUID 的摘要 */
    private List<ArticleSummaryInfo> summaries(int n) {
        List<ArticleSummaryInfo> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(summary(UUID.randomUUID(), "T" + i));
        }
        return list;
    }

    // ─────────────────────────── Trending ───────────────────────────

    @Nested
    @DisplayName("getTrendingArticles - 熱門排行")
    class Trending {

        @Test
        @DisplayName("ZSet reverseRange 回傳 null 時回傳空列表（不丟例外、不查 Facade）")
        void whenReverseRangeReturnsNull_returnsEmptyList() {
            when(zSetOperations.reverseRange(anyString(), eq(0L), anyLong()))
                    .thenReturn(null);

            List<RecommendArticleResponse> result = service.getTrendingArticles("7d", 10);

            assertThat(result).isEmpty();
            verify(articleFacade, never()).getPublishedArticlesByUuids(anyList());
        }

        @Test
        @DisplayName("ZSet 索引以 limit-1 計算（limit=10 → reverseRange(key,0,9)）")
        void usesLimitMinusOneAsZSetEndIndex() {
            when(zSetOperations.reverseRange(anyString(), anyLong(), anyLong()))
                    .thenReturn(Collections.emptySet());

            service.getTrendingArticles("24h", 10);

            verify(zSetOperations).reverseRange(
                    eq(RedisKeyConstant.getTrendingKey("24h")), eq(0L), eq(9L));
        }

        @Test
        @DisplayName("limit=1 邊界 → reverseRange(key,0,0)")
        void limitOne_usesIndexZeroToZero() {
            when(zSetOperations.reverseRange(anyString(), anyLong(), anyLong()))
                    .thenReturn(Collections.emptySet());

            service.getTrendingArticles("7d", 1);

            verify(zSetOperations).reverseRange(
                    eq(RedisKeyConstant.getTrendingKey("7d")), eq(0L), eq(0L));
        }

        @Test
        @DisplayName("limit=50 上限邊界 → reverseRange(key,0,49)")
        void limitFifty_usesIndexZeroToFortyNine() {
            when(zSetOperations.reverseRange(anyString(), anyLong(), anyLong()))
                    .thenReturn(Collections.emptySet());

            service.getTrendingArticles("30d", 50);

            verify(zSetOperations).reverseRange(
                    eq(RedisKeyConstant.getTrendingKey("30d")), eq(0L), eq(49L));
        }

        @Test
        @DisplayName("多筆排行依 ZSet 順序轉成回應，且只查一次 Facade")
        void multipleEntries_mappedAndSingleFacadeLookup() {
            UUID u1 = UUID.randomUUID();
            UUID u2 = UUID.randomUUID();
            UUID u3 = UUID.randomUUID();
            LinkedHashSet<String> ordered = new LinkedHashSet<>(
                    List.of(u1.toString(), u2.toString(), u3.toString()));

            when(zSetOperations.reverseRange(anyString(), anyLong(), anyLong()))
                    .thenReturn(ordered);
            when(articleFacade.getPublishedArticlesByUuids(List.of(u1, u2, u3)))
                    .thenReturn(List.of(summary(u1, "A"), summary(u2, "B"), summary(u3, "C")));

            List<RecommendArticleResponse> result = service.getTrendingArticles("7d", 10);

            assertThat(result).extracting(RecommendArticleResponse::uuid)
                    .containsExactly(u1, u2, u3);
            verify(articleFacade, times(1)).getPublishedArticlesByUuids(anyList());
        }

        @Test
        @DisplayName("Facade 回傳少於 ZSet（部分文章已不存在）→ 只回傳 Facade 實際結果")
        void facadeReturnsFewerThanZSet_returnsOnlyFacadeResults() {
            UUID u1 = UUID.randomUUID();
            UUID u2 = UUID.randomUUID();
            when(zSetOperations.reverseRange(anyString(), anyLong(), anyLong()))
                    .thenReturn(new LinkedHashSet<>(List.of(u1.toString(), u2.toString())));
            /** u2 已被刪除，Facade 只回 u1 */
            when(articleFacade.getPublishedArticlesByUuids(List.of(u1, u2)))
                    .thenReturn(List.of(summary(u1, "OnlyOne")));

            List<RecommendArticleResponse> result = service.getTrendingArticles("7d", 10);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).uuid()).isEqualTo(u1);
        }
    }

    // ─────────────────────────── Related: Cache ───────────────────────────

    @Nested
    @DisplayName("getRelatedArticles - 快取行為")
    class RelatedCache {

        @Test
        @DisplayName("快取命中且筆數多於 limit 時裁切到 limit")
        void cacheHitWithMoreThanLimit_trimmedToLimit() throws Exception {
            List<RecommendArticleResponse> cached = List.of(
                    resp("c1"), resp("c2"), resp("c3"), resp("c4"));
            String json = objectMapper.writeValueAsString(cached);
            when(valueOperations.get(RedisKeyConstant.getRelatedKey(ARTICLE_UUID.toString())))
                    .thenReturn(json);

            List<RecommendArticleResponse> result = service.getRelatedArticles(ARTICLE_UUID, 2);

            assertThat(result).hasSize(2);
            verify(articleFacade, never()).getPublishedArticleBasicInfo(any());
        }

        @Test
        @DisplayName("快取命中且筆數不超過 limit 時原樣回傳")
        void cacheHitWithinLimit_returnedAsIs() throws Exception {
            List<RecommendArticleResponse> cached = List.of(resp("c1"), resp("c2"));
            String json = objectMapper.writeValueAsString(cached);
            when(valueOperations.get(anyString())).thenReturn(json);

            List<RecommendArticleResponse> result = service.getRelatedArticles(ARTICLE_UUID, 5);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("快取內容損毀（無法反序列化）時降級為重新計算，不丟例外")
        void corruptedCache_fallsBackToCompute() {
            when(valueOperations.get(anyString())).thenReturn("這不是合法 JSON {{{");

            ArticleBasicInfo basicInfo = new ArticleBasicInfo(ARTICLE_UUID, Collections.emptyList());
            UUID recentUuid = UUID.randomUUID();
            when(articleFacade.getPublishedArticleBasicInfo(ARTICLE_UUID))
                    .thenReturn(Optional.of(basicInfo));
            when(searchFacade.findSimilarArticles(eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(articleFacade.getRecentPublishedArticles(eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(List.of(summary(recentUuid, "Recent")));

            List<RecommendArticleResponse> result = service.getRelatedArticles(ARTICLE_UUID, 5);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).uuid()).isEqualTo(recentUuid);
            /** 應重新計算（呼叫 basicInfo），證明走了降級路徑 */
            verify(articleFacade).getPublishedArticleBasicInfo(ARTICLE_UUID);
        }

        @Test
        @DisplayName("快取未命中且計算完成後，將結果以 1 小時 TTL 寫回 Redis")
        void cacheMiss_writesResultBackWithOneHourTtl() {
            ArticleBasicInfo basicInfo = new ArticleBasicInfo(ARTICLE_UUID, List.of(TAG_ID_1));
            UUID tagUuid = UUID.randomUUID();
            when(articleFacade.getPublishedArticleBasicInfo(ARTICLE_UUID))
                    .thenReturn(Optional.of(basicInfo));
            when(articleFacade.getArticlesByTagIds(anyList(), eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(List.of(summary(tagUuid, "Tag")));

            service.getRelatedArticles(ARTICLE_UUID, 2);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
            verify(valueOperations).set(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());

            assertThat(keyCaptor.getValue())
                    .isEqualTo(RedisKeyConstant.getRelatedKey(ARTICLE_UUID.toString()));
            assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofHours(1));
            assertThat(valueCaptor.getValue()).contains(tagUuid.toString());
        }
    }

    // ─────────────────────────── Related: 三層降級 ───────────────────────────

    @Nested
    @DisplayName("getRelatedArticles - 三層降級策略")
    class RelatedFallback {

        @Test
        @DisplayName("basicInfo 標籤為空時跳過第一層，直接走 ES")
        void emptyTags_skipTagLayerGoesToEs() {
            ArticleBasicInfo basicInfo = new ArticleBasicInfo(ARTICLE_UUID, Collections.emptyList());
            UUID esUuid = UUID.randomUUID();
            when(articleFacade.getPublishedArticleBasicInfo(ARTICLE_UUID))
                    .thenReturn(Optional.of(basicInfo));
            when(searchFacade.findSimilarArticles(eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(List.of(esUuid));
            when(articleFacade.getPublishedArticlesByUuids(List.of(esUuid)))
                    .thenReturn(List.of(summary(esUuid, "Es")));

            List<RecommendArticleResponse> result = service.getRelatedArticles(ARTICLE_UUID, 5);

            assertThat(result).extracting(RecommendArticleResponse::uuid).containsExactly(esUuid);
            verify(articleFacade, never()).getArticlesByTagIds(anyList(), any(), anyInt());
        }

        @Test
        @DisplayName("ES 回傳的 UUID 與第一層重複時被過濾（跨層去重）")
        void esResultsDuplicatingTagLayer_areFiltered() {
            ArticleBasicInfo basicInfo = new ArticleBasicInfo(ARTICLE_UUID, List.of(TAG_ID_1));
            UUID shared = UUID.randomUUID();
            UUID esOnly = UUID.randomUUID();
            when(articleFacade.getPublishedArticleBasicInfo(ARTICLE_UUID))
                    .thenReturn(Optional.of(basicInfo));
            when(articleFacade.getArticlesByTagIds(anyList(), eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(List.of(summary(shared, "Shared")));
            /** ES 回 shared(重複) + esOnly(新)，shared 應在轉 Facade 查詢前被過濾掉 */
            when(searchFacade.findSimilarArticles(eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(List.of(shared, esOnly));
            when(articleFacade.getPublishedArticlesByUuids(List.of(esOnly)))
                    .thenReturn(List.of(summary(esOnly, "EsOnly")));

            List<RecommendArticleResponse> result = service.getRelatedArticles(ARTICLE_UUID, 5);

            assertThat(result).extracting(RecommendArticleResponse::uuid)
                    .containsExactly(shared, esOnly);
            /** 驗證重複的 shared 沒被放進批次查詢（只查 esOnly） */
            verify(articleFacade).getPublishedArticlesByUuids(List.of(esOnly));
        }

        @Test
        @DisplayName("第三層最新文章查詢傳入 remaining*2")
        void recentLayer_receivesRemainingTimesTwo() {
            /** limit=5，前兩層各 0 篇，remaining=5 → recent 應收到 5*2=10 */
            ArticleBasicInfo basicInfo = new ArticleBasicInfo(ARTICLE_UUID, Collections.emptyList());
            when(articleFacade.getPublishedArticleBasicInfo(ARTICLE_UUID))
                    .thenReturn(Optional.of(basicInfo));
            when(searchFacade.findSimilarArticles(eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(articleFacade.getRecentPublishedArticles(eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(Collections.emptyList());

            service.getRelatedArticles(ARTICLE_UUID, 5);

            verify(articleFacade).getRecentPublishedArticles(ARTICLE_UUID, 10);
        }

        @Test
        @DisplayName("第一層即超量時截斷到 limit，且不查 ES/最新")
        void tagLayerOverflow_truncatedToLimitAndSkipsLowerLayers() {
            ArticleBasicInfo basicInfo = new ArticleBasicInfo(ARTICLE_UUID, List.of(TAG_ID_1));
            /** 第一層回 5 篇，但 limit=3 → 截斷成 3 */
            when(articleFacade.getPublishedArticleBasicInfo(ARTICLE_UUID))
                    .thenReturn(Optional.of(basicInfo));
            when(articleFacade.getArticlesByTagIds(anyList(), eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(summaries(5));

            List<RecommendArticleResponse> result = service.getRelatedArticles(ARTICLE_UUID, 3);

            assertThat(result).hasSize(3);
            verify(searchFacade, never()).findSimilarArticles(any(), anyInt());
            verify(articleFacade, never()).getRecentPublishedArticles(any(), anyInt());
        }

        @Test
        @DisplayName("三層皆無結果時回傳空列表")
        void allLayersEmpty_returnsEmptyList() {
            ArticleBasicInfo basicInfo = new ArticleBasicInfo(ARTICLE_UUID, List.of(TAG_ID_1));
            when(articleFacade.getPublishedArticleBasicInfo(ARTICLE_UUID))
                    .thenReturn(Optional.of(basicInfo));
            when(articleFacade.getArticlesByTagIds(anyList(), eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(searchFacade.findSimilarArticles(eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(articleFacade.getRecentPublishedArticles(eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(Collections.emptyList());

            List<RecommendArticleResponse> result = service.getRelatedArticles(ARTICLE_UUID, 5);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("ES 回傳全為重複（newUuids 為空）時不發批次查詢，續走第三層")
        void esAllDuplicates_skipsBatchLookupAndFallsToRecent() {
            ArticleBasicInfo basicInfo = new ArticleBasicInfo(ARTICLE_UUID, List.of(TAG_ID_1));
            UUID shared = UUID.randomUUID();
            UUID recentUuid = UUID.randomUUID();
            when(articleFacade.getPublishedArticleBasicInfo(ARTICLE_UUID))
                    .thenReturn(Optional.of(basicInfo));
            when(articleFacade.getArticlesByTagIds(anyList(), eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(List.of(summary(shared, "Shared")));
            /** ES 只回已存在的 shared → newUuids 空 → 不應呼叫 getPublishedArticlesByUuids */
            when(searchFacade.findSimilarArticles(eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(List.of(shared));
            when(articleFacade.getRecentPublishedArticles(eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(List.of(summary(recentUuid, "Recent")));

            List<RecommendArticleResponse> result = service.getRelatedArticles(ARTICLE_UUID, 5);

            assertThat(result).extracting(RecommendArticleResponse::uuid)
                    .containsExactly(shared, recentUuid);
            verify(articleFacade, never()).getPublishedArticlesByUuids(anyList());
        }
    }

    private RecommendArticleResponse resp(String title) {
        return new RecommendArticleResponse(UUID.randomUUID(), title, "slug-" + title,
                "sum", "Author", List.of("tag"), 1L, 0L, LocalDateTime.now());
    }
}
