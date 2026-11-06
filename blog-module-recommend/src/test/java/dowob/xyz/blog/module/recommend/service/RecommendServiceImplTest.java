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
    @SuppressWarnings("unchecked")
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new RecommendServiceImpl(articleFacade, searchFacade, stringRedisTemplate, objectMapper);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        /* DATA-13：快取命中後會即時過濾非公開文章；預設視為「傳入的全部都是公開」 */
        when(articleFacade.filterPublishedUuids(any()))
                .thenAnswer(inv -> new java.util.HashSet<>((java.util.Collection<UUID>) inv.getArgument(0)));
    }

    private ArticleSummaryInfo summary(UUID uuid, String title) {
        return new ArticleSummaryInfo(uuid, title, "slug-" + title, "summary-" + title,
                "Author", UUID.randomUUID(), List.of("tag"), 100L, 10L, LocalDateTime.now());
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

    // ─────────────────────────── Related: PERF-26 / DATA-13 ───────────────────────────

    @Nested
    @DisplayName("getRelatedArticles - 快取 limit 與即時過濾非公開文章")
    class RelatedLimitAndVisibility {

        /** 以記憶體 Map 模擬 Redis String，使「第一次寫入、第二次讀取」可以串起來 */
        private void useInMemoryCache() {
            java.util.Map<String, String> store = new java.util.HashMap<>();
            when(valueOperations.get(anyString())).thenAnswer(inv -> store.get(inv.<String>getArgument(0)));
            org.mockito.Mockito.doAnswer(inv -> {
                store.put(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("先以 limit=1 請求、再以 limit=5 請求：第二次拿到 5 筆（快取不得被 limit=1 的結果污染）")
        void firstSmallLimitDoesNotPoisonLaterLargerLimit() {
            useInMemoryCache();
            List<ArticleSummaryInfo> pool = summaries(20);
            when(articleFacade.getPublishedArticleBasicInfo(ARTICLE_UUID))
                    .thenReturn(Optional.of(new ArticleBasicInfo(ARTICLE_UUID, List.of(TAG_ID_1))));
            /* 模擬真實 Facade：只回傳不超過呼叫端要求 limit 的筆數 */
            when(articleFacade.getArticlesByTagIds(anyList(), eq(ARTICLE_UUID), anyInt()))
                    .thenAnswer(inv -> pool.subList(0, Math.min(pool.size(), inv.<Integer>getArgument(2))));

            List<RecommendArticleResponse> first = service.getRelatedArticles(ARTICLE_UUID, 1);
            List<RecommendArticleResponse> second = service.getRelatedArticles(ARTICLE_UUID, 5);

            assertThat(first).hasSize(1);
            assertThat(second).hasSize(5);
            /* 第二次是快取命中，不應再次計算 */
            verify(articleFacade, times(1)).getPublishedArticleBasicInfo(ARTICLE_UUID);
        }

        @Test
        @DisplayName("快取未命中：一律以最大 limit(20) 向各層取資料")
        void cacheMiss_computesWithMaxLimit() {
            when(articleFacade.getPublishedArticleBasicInfo(ARTICLE_UUID))
                    .thenReturn(Optional.of(new ArticleBasicInfo(ARTICLE_UUID, List.of(TAG_ID_1))));
            when(articleFacade.getArticlesByTagIds(anyList(), eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(summaries(3));

            service.getRelatedArticles(ARTICLE_UUID, 1);

            verify(articleFacade).getArticlesByTagIds(anyList(), eq(ARTICLE_UUID), eq(20));
        }

        @Test
        @DisplayName("快取命中但其中一篇已下架：該篇不回傳")
        void cacheHit_unpublishedArticleIsFilteredOut() throws Exception {
            RecommendArticleResponse kept1 = resp("k1");
            RecommendArticleResponse unpublished = resp("gone");
            RecommendArticleResponse kept2 = resp("k2");
            when(valueOperations.get(RedisKeyConstant.getRelatedKey(ARTICLE_UUID.toString())))
                    .thenReturn(objectMapper.writeValueAsString(List.of(kept1, unpublished, kept2)));
            org.mockito.Mockito.doReturn(new java.util.HashSet<>(List.of(kept1.uuid(), kept2.uuid())))
                    .when(articleFacade).filterPublishedUuids(any());

            List<RecommendArticleResponse> result = service.getRelatedArticles(ARTICLE_UUID, 5);

            assertThat(result).extracting(RecommendArticleResponse::uuid)
                    .containsExactly(kept1.uuid(), kept2.uuid());
        }

        @Test
        @DisplayName("先過濾再切片：前段有已下架文章時，limit 仍盡量用後段公開文章補滿")
        void cacheHit_filtersBeforeSlicing() throws Exception {
            RecommendArticleResponse gone = resp("gone");
            RecommendArticleResponse a = resp("a");
            RecommendArticleResponse b = resp("b");
            RecommendArticleResponse c = resp("c");
            when(valueOperations.get(RedisKeyConstant.getRelatedKey(ARTICLE_UUID.toString())))
                    .thenReturn(objectMapper.writeValueAsString(List.of(gone, a, b, c)));
            org.mockito.Mockito.doReturn(new java.util.HashSet<>(List.of(a.uuid(), b.uuid(), c.uuid())))
                    .when(articleFacade).filterPublishedUuids(any());

            List<RecommendArticleResponse> result = service.getRelatedArticles(ARTICLE_UUID, 2);

            assertThat(result).extracting(RecommendArticleResponse::uuid).containsExactly(a.uuid(), b.uuid());
        }

        @Test
        @DisplayName("快取命中只對 Facade 做 1 次集合述詞查詢（不逐篇查）")
        void cacheHit_singleFilterCall() throws Exception {
            when(valueOperations.get(RedisKeyConstant.getRelatedKey(ARTICLE_UUID.toString())))
                    .thenReturn(objectMapper.writeValueAsString(List.of(resp("a"), resp("b"), resp("c"))));

            service.getRelatedArticles(ARTICLE_UUID, 5);

            verify(articleFacade, times(1)).filterPublishedUuids(any());
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
            /** 以最大 limit=20 計算，前兩層各 0 篇，remaining=20 → recent 應收到 20*2=40 */
            ArticleBasicInfo basicInfo = new ArticleBasicInfo(ARTICLE_UUID, Collections.emptyList());
            when(articleFacade.getPublishedArticleBasicInfo(ARTICLE_UUID))
                    .thenReturn(Optional.of(basicInfo));
            when(searchFacade.findSimilarArticles(eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(articleFacade.getRecentPublishedArticles(eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(Collections.emptyList());

            service.getRelatedArticles(ARTICLE_UUID, 5);

            /* PERF-26：一律以最大 limit(20) 計算 → remaining=20 → 20*2=40 */
            verify(articleFacade).getRecentPublishedArticles(ARTICLE_UUID, 40);
        }

        @Test
        @DisplayName("第一層即超量時以計算上限截斷、讀取時切到 limit，且不查 ES/最新")
        void tagLayerOverflow_truncatedToLimitAndSkipsLowerLayers() {
            ArticleBasicInfo basicInfo = new ArticleBasicInfo(ARTICLE_UUID, List.of(TAG_ID_1));
            /** 第一層回滿 20 篇（計算上限），limit=3 → 讀取時切成 3 */
            when(articleFacade.getPublishedArticleBasicInfo(ARTICLE_UUID))
                    .thenReturn(Optional.of(basicInfo));
            when(articleFacade.getArticlesByTagIds(anyList(), eq(ARTICLE_UUID), anyInt()))
                    .thenReturn(summaries(25));

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
                "sum", "Author", UUID.randomUUID(), List.of("tag"), 1L, 0L, LocalDateTime.now());
    }
}
