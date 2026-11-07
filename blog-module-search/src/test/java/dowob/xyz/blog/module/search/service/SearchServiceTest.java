package dowob.xyz.blog.module.search.service;

import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.index.ArticleIndexAdmin;
import dowob.xyz.blog.module.search.model.dto.response.SearchReindexJobResponse;
import dowob.xyz.blog.module.search.reindex.ReindexJobState;
import dowob.xyz.blog.module.search.reindex.ReindexJobStore;
import dowob.xyz.blog.module.search.reindex.ReindexJournal;
import dowob.xyz.blog.module.search.model.dto.response.SearchIndexStatusResponse;
import dowob.xyz.blog.module.search.model.dto.response.SearchResultResponse;
import dowob.xyz.blog.module.search.repository.ArticleSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SearchService 單元測試
 *
 * <p>
 * 使用 Mockito 隔離 Elasticsearch、Redis 與 ArticleFacade 依賴，
 * 驗證搜尋服務的業務邏輯正確性。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SearchService 單元測試")
class SearchServiceTest {

    /** 測試用使用者 uuid（搜尋紀錄 Redis key 以 uuid 為鍵） */
    private static final UUID USER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /**
     * Elasticsearch 操作工具（Mock）
     */
    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    /**
     * 文章 ES Repository（Mock）
     */
    @Mock
    private ArticleSearchRepository articleSearchRepository;

    /**
     * Redis 字串模板（Mock）
     */
    @Mock
    private StringRedisTemplate redisTemplate;

    /**
     * 文章 Facade（Mock）
     */
    @Mock
    private ArticleFacade articleFacade;

    /**
     * 使用者 Facade（Mock，重建索引時批次取得作者當下暱稱）
     */
    @Mock
    private UserFacade userFacade;

    /**
     * 文章索引管理（Mock，作者暱稱改寫經 alias）
     */
    @Mock
    private ArticleIndexAdmin indexAdmin;

    /**
     * 全量重建期間的變動紀錄（Mock）
     */
    @Mock
    private ReindexJournal reindexJournal;

    /**
     * 全量重建作業狀態（Mock）
     */
    @Mock
    private ReindexJobStore reindexJobStore;

    /**
     * 待測服務（自動注入上述 Mock）
     */
    @InjectMocks
    private SearchServiceImpl searchService;

    /**
     * Redis ZSet 操作 Mock
     */
    @Mock
    private ZSetOperations<String, String> zSetOperations;

    /**
     * Redis List 操作 Mock
     */
    @Mock
    private ListOperations<String, String> listOperations;

    /**
     * Redis String 值操作 Mock（用於重建時間戳讀寫）
     */
    @Mock
    private ValueOperations<String, String> valueOperations;

    /**
     * 測試前置：設定 Redis Template Mock 行為
     */
    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    /**
     * 建立 Mock SearchHits 工具方法
     *
     * @param documents 要包含的文件列表
     * @return Mock SearchHits
     */
    @SuppressWarnings("unchecked")
    private SearchHits<ArticleDocument> mockSearchHits(List<ArticleDocument> documents) {
        SearchHits<ArticleDocument> hits = mock(SearchHits.class);
        List<SearchHit<ArticleDocument>> searchHitList = documents.stream()
                .map(doc -> {
                    SearchHit<ArticleDocument> hit = mock(SearchHit.class);
                    when(hit.getContent()).thenReturn(doc);
                    return hit;
                })
                .toList();
        when(hits.getSearchHits()).thenReturn(searchHitList);
        when(hits.getTotalHits()).thenReturn((long) documents.size());
        return hits;
    }

    /**
     * 建立測試用 ArticleDocument
     *
     * @return 測試文章文件
     */
    private ArticleDocument buildTestDocument() {
        return ArticleDocument.builder()
                .id(UUID.randomUUID().toString())
                .title("Spring Boot 教學")
                .summary("本文介紹 Spring Boot 基礎")
                .content("Spring Boot 是一個 Java 框架...")
                .slug("spring-boot-tutorial")
                .author(new ArticleDocument.AuthorInfo(USER_UUID.toString(), "Yuan"))
                .tags(List.of(new ArticleDocument.TagInfo(UUID.randomUUID(), "Java", "java")))
                .publishedAt(LocalDateTime.now())
                .status("PUBLISHED")
                .build();
    }

    /**
     * search() 方法測試群組
     */
    @Nested
    @DisplayName("search() 全文搜尋")
    class SearchTests {

        /**
         * PERF-12：NativeQuery 必須帶 _source excludes content（結果 DTO 不用 content，省下大欄位傳輸）
         */
        @Test
        @DisplayName("PERF-12：查詢帶 _source 過濾，排除 content")
        void search_excludesContentFromSource() {
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of(buildTestDocument()));
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

            searchService.search(null, null, "relevance", 1, 10, null);

            ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
            verify(elasticsearchOperations).search(captor.capture(), eq(ArticleDocument.class));
            var sourceFilter = ((org.springframework.data.elasticsearch.client.elc.NativeQuery) captor.getValue()).getSourceFilter();
            assertThat(sourceFilter).isNotNull();
            assertThat(sourceFilter.getExcludes()).contains("content");
        }

        /**
         * PERF-12：from + size 超過 ES max_result_window(10000) 時回 400，而不是讓 ES 拒絕後變成 500
         */
        @Test
        @DisplayName("PERF-12：page*size 超過 10000 → BusinessException(PARAM_VALID_ERROR)，不呼叫 ES")
        void search_deepPagination_rejectedWith400() {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> searchService.search("java", null, "relevance", 101, 100, null))
                    .isInstanceOfSatisfying(dowob.xyz.blog.common.exception.BusinessException.class,
                            e -> assertThat(e.getCode()).isEqualTo(
                                    dowob.xyz.blog.common.api.errorcode.CommonErrorCode.PARAM_VALID_ERROR.getCode()));
            verifyNoInteractions(elasticsearchOperations);
        }

        @Test
        @DisplayName("PERF-12：page*size 恰為 10000（邊界）仍可查詢")
        void search_pagingAtWindowBoundary_isAllowed() {
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of());
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

            searchService.search(null, null, "relevance", 100, 100, null);

            verify(elasticsearchOperations).search(any(Query.class), eq(ArticleDocument.class));
        }

        /**
         * 有關鍵字時，應執行 Elasticsearch 查詢並回傳結果
         */
        @Test
        @DisplayName("有關鍵字時，應呼叫 Elasticsearch 並回傳分頁結果")
        void search_withKeyword_executesElasticsearchAndReturnsPageResult() {
            ArticleDocument doc = buildTestDocument();
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of(doc));
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);
            when(zSetOperations.incrementScore(anyString(), anyString(), anyDouble())).thenReturn(1.0);

            PageResult<SearchResultResponse> result = searchService.search("Spring Boot", null, "relevance", 1, 10, null);

            assertThat(result).isNotNull();
            assertThat(result.getTotal()).isEqualTo(1);
            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getRecords().get(0).getTitle()).isEqualTo("Spring Boot 教學");
            verify(elasticsearchOperations).search(any(Query.class), eq(ArticleDocument.class));
        }

        /**
         * 有關鍵字且用戶已登入時，應記錄至熱門搜尋與個人歷史
         */
        @Test
        @DisplayName("有關鍵字且已登入，應記錄熱門搜尋與個人歷史")
        void search_withKeywordAndUserId_recordsToRedis() {
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of(buildTestDocument()));
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);
            when(zSetOperations.incrementScore(anyString(), anyString(), anyDouble())).thenReturn(1.0);

            searchService.search("java", null, "relevance", 1, 10, USER_UUID);

            verify(zSetOperations).incrementScore("search:hot", "java", 1.0);
            verify(listOperations).leftPush("search:history:" + USER_UUID, "java");
            verify(listOperations).trim("search:history:" + USER_UUID, 0, 19);
        }

        /**
         * 匿名使用者搜尋時，只記錄熱門詞，不記錄個人歷史
         */
        @Test
        @DisplayName("匿名使用者搜尋，應只記錄熱門搜尋，不記錄個人歷史")
        void search_withKeywordAndNullUserId_doesNotRecordHistory() {
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of(buildTestDocument()));
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);
            when(zSetOperations.incrementScore(anyString(), anyString(), anyDouble())).thenReturn(1.0);

            searchService.search("java", null, "relevance", 1, 10, null);

            verify(zSetOperations).incrementScore("search:hot", "java", 1.0);
            verify(listOperations, never()).leftPush(anyString(), anyString());
        }

        /**
         * 無關鍵字時，不應記錄至 Redis
         */
        @Test
        @DisplayName("無關鍵字時，不應記錄熱門搜尋或個人歷史")
        void search_withEmptyKeyword_doesNotRecordToRedis() {
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of());
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

            searchService.search(null, null, "relevance", 1, 10, USER_UUID);

            verify(zSetOperations, never()).incrementScore(anyString(), anyString(), anyDouble());
            verify(listOperations, never()).leftPush(anyString(), anyString());
        }

        /**
         * 搜尋結果應正確轉換為 SearchResultResponse
         */
        @Test
        @DisplayName("搜尋結果應正確映射標籤名稱與作者暱稱")
        void search_withResults_mapsToSearchResultResponse() {
            ArticleDocument doc = buildTestDocument();
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of(doc));
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);
            when(zSetOperations.incrementScore(anyString(), anyString(), anyDouble())).thenReturn(1.0);

            PageResult<SearchResultResponse> result = searchService.search("Spring", null, "relevance", 1, 10, null);

            SearchResultResponse response = result.getRecords().get(0);
            assertThat(response.getSlug()).isEqualTo("spring-boot-tutorial");
            assertThat(response.getAuthorNickname()).isEqualTo("Yuan");
            assertThat(response.getTagNames()).containsExactly("Java");
        }

        @Test
        @DisplayName("搜尋結果的 authorUuid 直接取自 ES 文件的 author.uuid，不查 UserFacade")
        void search_withResults_mapsAuthorUuidFromDocument() {
            ArticleDocument doc = buildTestDocument();
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of(doc));
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

            PageResult<SearchResultResponse> result = searchService.search(null, null, "relevance", 1, 10, null);

            assertThat(result.getRecords().get(0).getAuthorUuid()).isEqualTo(USER_UUID);
            verifyNoInteractions(userFacade);
        }

        @Test
        @DisplayName("ES 文件的 author.uuid 無法解析為 UUID（髒資料）時 authorUuid 為 null，不拋例外")
        void search_whenDocAuthorUuidMalformed_authorUuidIsNull() {
            ArticleDocument doc = buildTestDocument();
            doc.setAuthor(new ArticleDocument.AuthorInfo("not-a-uuid", "Yuan"));
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of(doc));
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

            PageResult<SearchResultResponse> result = searchService.search(null, null, "relevance", 1, 10, null);

            assertThat(result.getRecords().get(0).getAuthorUuid()).isNull();
            assertThat(result.getRecords().get(0).getAuthorNickname()).isEqualTo("Yuan");
        }

        @Test
        @DisplayName("指定 tag 篩選時，應加入 Nested Query 過濾")
        void search_withTag_executesNestedQuery() {
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of());
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

            PageResult<SearchResultResponse> result = searchService.search(null, "java", "relevance", 1, 10, null);

            assertThat(result).isNotNull();
            assertThat(result.getTotal()).isZero();
            verify(elasticsearchOperations).search(any(Query.class), eq(ArticleDocument.class));
        }

        @Test
        @DisplayName("sort=latest 時，應以 publishedAt 降序排序")
        void search_withLatestSort_sortsbyPublishedAt() {
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of());
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

            PageResult<SearchResultResponse> result = searchService.search(null, null, "latest", 1, 10, null);

            assertThat(result).isNotNull();
            verify(elasticsearchOperations).search(any(Query.class), eq(ArticleDocument.class));
        }

        @Test
        @DisplayName("B4：sort=hot 已移除，回 400 PARAM_VALID_ERROR 且不查詢 ES")
        void search_withHotSort_rejectedWith400() {
            assertThatThrownBy(() -> searchService.search(null, null, "hot", 1, 10, null))
                    .isInstanceOfSatisfying(dowob.xyz.blog.common.exception.BusinessException.class, e ->
                            assertThat(e.getErrorCode())
                                    .isEqualTo(dowob.xyz.blog.common.api.errorcode.CommonErrorCode.PARAM_VALID_ERROR));
            verifyNoInteractions(elasticsearchOperations);
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(strings = {"date", "popularity", "foo", "", "relevance2"})
        @DisplayName("sort 白名單：只接受 relevance／latest，其他值（含 date、popularity、foo、空字串）回 400 且不查詢 ES")
        void search_sortOutsideWhitelist_rejectedWith400(String sort) {
            assertThatThrownBy(() -> searchService.search("java", null, sort, 1, 10, null))
                    .isInstanceOfSatisfying(dowob.xyz.blog.common.exception.BusinessException.class, e ->
                            assertThat(e.getErrorCode())
                                    .isEqualTo(dowob.xyz.blog.common.api.errorcode.CommonErrorCode.PARAM_VALID_ERROR));
            verifyNoInteractions(elasticsearchOperations);
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(strings = {"relevance", "RELEVANCE", "latest", "LATEST", "Latest"})
        @DisplayName("sort 白名單：relevance／latest（不分大小寫）通過")
        void search_sortInWhitelist_passes(String sort) {
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of());
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

            PageResult<SearchResultResponse> result = searchService.search(null, null, sort, 1, 10, null);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("B4：sort=HOT（大小寫不同）同樣回 400")
        void search_withHotSortUpperCase_rejectedWith400() {
            assertThatThrownBy(() -> searchService.search("java", null, "HOT", 1, 10, null))
                    .isInstanceOf(dowob.xyz.blog.common.exception.BusinessException.class);
        }

        @Test
        @DisplayName("B4：結果的 viewCount／likeCount 取自 ArticleFacade（與詳情頁同源），不用 ES 文件的快照值")
        void search_countsComeFromArticleFacade_notFromDocument() {
            ArticleDocument doc = buildTestDocument();
            UUID uuid = UUID.fromString(doc.getId());
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of(doc));
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);
            when(articleFacade.getEngagementCounts(any()))
                    .thenReturn(Map.of(uuid, new dowob.xyz.blog.infrastructure.facade.dto.ArticleEngagementCounts(105L, 7L)));

            PageResult<SearchResultResponse> result = searchService.search(null, null, "relevance", 1, 10, null);

            assertThat(result.getRecords().get(0).getViewCount()).isEqualTo(105L);
            assertThat(result.getRecords().get(0).getLikeCount()).isEqualTo(7L);
        }

        @Test
        @DisplayName("B4：一頁 N 篇只呼叫 ArticleFacade.getEngagementCounts 一次（批次，無 N+1）")
        void search_nResults_singleBatchedFacadeCall() {
            List<ArticleDocument> docs = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                docs.add(buildTestDocument());
            }
            SearchHits<ArticleDocument> hits = mockSearchHits(docs);
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);
            when(articleFacade.getEngagementCounts(any())).thenReturn(Map.of());

            searchService.search(null, null, "relevance", 1, 10, null);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Collection<UUID>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
            verify(articleFacade, org.mockito.Mockito.times(1)).getEngagementCounts(captor.capture());
            assertThat(captor.getValue()).hasSize(5);
        }

        @Test
        @DisplayName("B4：facade 查無該篇計數時回 0，不拋例外")
        void search_whenFacadeHasNoCounts_defaultsToZero() {
            ArticleDocument doc = buildTestDocument();
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of(doc));
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);
            when(articleFacade.getEngagementCounts(any())).thenReturn(Map.of());

            PageResult<SearchResultResponse> result = searchService.search(null, null, "relevance", 1, 10, null);

            assertThat(result.getRecords().get(0).getViewCount()).isZero();
            assertThat(result.getRecords().get(0).getLikeCount()).isZero();
        }

        @Test
        @DisplayName("B4：沒有命中時不呼叫 ArticleFacade")
        void search_noHits_doesNotCallFacade() {
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of());
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

            searchService.search(null, null, "relevance", 1, 10, null);

            verify(articleFacade, never()).getEngagementCounts(any());
        }

        @Test
        @DisplayName("文章 tags 為 null 時，tagNames 應回傳空列表")
        void search_whenDocTagsNull_tagNamesIsEmpty() {
            ArticleDocument doc = ArticleDocument.builder()
                    .id(UUID.randomUUID().toString())
                    .title("No Tags")
                    .summary("summary")
                    .slug("no-tags")
                    .author(new ArticleDocument.AuthorInfo(USER_UUID.toString(), "Yuan"))
                    .tags(null)
                    .publishedAt(LocalDateTime.now())
                    .status("PUBLISHED")
                    .build();
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of(doc));
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

            PageResult<SearchResultResponse> result = searchService.search(null, null, "relevance", 1, 10, null);

            assertThat(result.getRecords().get(0).getTagNames()).isEmpty();
        }

        @Test
        @DisplayName("文章 author 為 null 時，authorNickname 應為 null")
        void search_whenDocAuthorNull_authorNicknameIsNull() {
            ArticleDocument doc = ArticleDocument.builder()
                    .id(UUID.randomUUID().toString())
                    .title("No Author")
                    .summary("summary")
                    .slug("no-author")
                    .author(null)
                    .tags(List.of())
                    .publishedAt(LocalDateTime.now())
                    .status("PUBLISHED")
                    .build();
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of(doc));
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

            PageResult<SearchResultResponse> result = searchService.search(null, null, "relevance", 1, 10, null);

            assertThat(result.getRecords().get(0).getAuthorNickname()).isNull();
            assertThat(result.getRecords().get(0).getAuthorUuid()).isNull();
        }

        @Test
        @DisplayName("同時指定關鍵字與標籤時，應同時應用 MultiMatch 與 Nested Query")
        void search_withKeywordAndTag_appliesBothFilters() {
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of(buildTestDocument()));
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);
            when(zSetOperations.incrementScore(anyString(), anyString(), anyDouble())).thenReturn(1.0);

            PageResult<SearchResultResponse> result = searchService.search("Spring", "java", "relevance", 1, 10, USER_UUID);

            assertThat(result).isNotNull();
            verify(zSetOperations).incrementScore("search:hot", "spring", 1.0);
            verify(listOperations).leftPush("search:history:" + USER_UUID, "spring");
        }

        @Test
        @DisplayName("SEC-05：搜尋無結果（total=0）→ 不寫入熱門詞")
        void search_withNoResults_doesNotRecordHotTerm() {
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of());
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

            searchService.search("zzz-no-such-term", null, "relevance", 1, 10, null);

            verify(zSetOperations, never()).incrementScore(anyString(), anyString(), anyDouble());
        }

        @Test
        @DisplayName("SEC-05：搜尋有結果 → 寫入熱門詞（正規化：小寫、trim、合併連續空白）")
        void search_withResults_recordsNormalizedHotTerm() {
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of(buildTestDocument()));
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);
            when(zSetOperations.incrementScore(anyString(), anyString(), anyDouble())).thenReturn(1.0);

            searchService.search("  Spring \t  Boot  ", null, "relevance", 1, 10, null);

            verify(zSetOperations).incrementScore("search:hot", "spring boot", 1.0);
        }

        @Test
        @DisplayName("SEC-05：正規化後恰 50 字 → 寫入；51 字 → 不寫入但搜尋照常回傳結果")
        void search_hotTermLengthLimit_fiftyRecorded_fiftyOneNotRecordedButSearchWorks() {
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of(buildTestDocument()));
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);
            when(zSetOperations.incrementScore(anyString(), anyString(), anyDouble())).thenReturn(1.0);
            String fifty = "a".repeat(50);
            String fiftyOne = "b".repeat(51);

            searchService.search(fifty, null, "relevance", 1, 10, null);
            PageResult<SearchResultResponse> result =
                    searchService.search(fiftyOne, null, "relevance", 1, 10, null);

            verify(zSetOperations).incrementScore("search:hot", fifty, 1.0);
            verify(zSetOperations, never()).incrementScore(eq("search:hot"), eq(fiftyOne), anyDouble());
            assertThat(result.getRecords()).hasSize(1);
        }

        @Test
        @DisplayName("SEC-05：匿名搜尋有結果 → 仍計入熱門詞，且不寫個人歷史")
        void search_anonymousWithResults_stillRecordsHotTerm() {
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of(buildTestDocument()));
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);
            when(zSetOperations.incrementScore(anyString(), anyString(), anyDouble())).thenReturn(1.0);

            searchService.search("kafka", null, "relevance", 1, 10, null);

            verify(zSetOperations).incrementScore("search:hot", "kafka", 1.0);
            verify(listOperations, never()).leftPush(anyString(), anyString());
        }

        @Test
        @DisplayName("SEC-05：登入使用者搜尋無結果 → 熱門詞不計，個人歷史照舊記錄")
        void search_loggedInWithNoResults_recordsHistoryButNotHotTerm() {
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of());
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

            searchService.search("nothing", null, "relevance", 1, 10, USER_UUID);

            verify(zSetOperations, never()).incrementScore(anyString(), anyString(), anyDouble());
            verify(listOperations).leftPush("search:history:" + USER_UUID, "nothing");
        }
    }

    /**
     * suggest() 方法測試群組
     */
    @Nested
    @DisplayName("suggest() 搜尋建議")
    class SuggestTests {

        /**
         * 有前綴時，應從熱門搜尋詞中過濾並回傳符合前綴者
         */
        @Test
        @DisplayName("有前綴時，應回傳符合前綴的熱門搜尋詞")
        void suggest_withPrefix_filtersMatchingTerms() {
            when(zSetOperations.reverseRange("search:hot", 0, 99))
                    .thenReturn(Set.of("java基礎", "javascript入門", "spring boot", "python"));

            List<String> suggestions = searchService.suggest("ja");

            assertThat(suggestions).containsExactlyInAnyOrder("java基礎", "javascript入門");
        }

        /**
         * 空前綴時，應回傳空列表
         */
        @Test
        @DisplayName("空前綴時，應回傳空列表")
        void suggest_withEmptyPrefix_returnsEmptyList() {
            List<String> suggestions = searchService.suggest("");

            assertThat(suggestions).isEmpty();
            verify(zSetOperations, never()).reverseRange(anyString(), anyLong(), anyLong());
        }

        /**
         * null 前綴時，應回傳空列表
         */
        @Test
        @DisplayName("null 前綴時，應回傳空列表")
        void suggest_withNullPrefix_returnsEmptyList() {
            List<String> suggestions = searchService.suggest(null);

            assertThat(suggestions).isEmpty();
        }

        /**
         * 熱門搜尋詞為 null 時（Redis 無資料），應回傳空列表
         */
        @Test
        @DisplayName("Redis 無熱門搜尋詞時，應回傳空列表")
        void suggest_whenHotSearchEmpty_returnsEmptyList() {
            when(zSetOperations.reverseRange("search:hot", 0, 99)).thenReturn(null);

            List<String> suggestions = searchService.suggest("java");

            assertThat(suggestions).isEmpty();
        }
    }

    /**
     * getHistory() 方法測試群組
     */
    @Nested
    @DisplayName("getHistory() 個人搜尋歷史")
    class GetHistoryTests {

        /**
         * 應從 Redis 取得指定用戶的搜尋歷史
         */
        @Test
        @DisplayName("應從 Redis 取得最近 20 筆搜尋歷史")
        void getHistory_returnsRedisListRange() {
            when(listOperations.range("search:history:" + USER_UUID, 0, 19))
                    .thenReturn(List.of("spring boot", "java基礎"));

            List<String> history = searchService.getHistory(USER_UUID);

            assertThat(history).containsExactly("spring boot", "java基礎");
            verify(listOperations).range("search:history:" + USER_UUID, 0, 19);
        }

        /**
         * Redis 回傳 null 時，應回傳空列表
         */
        @Test
        @DisplayName("Redis 回傳 null 時，應回傳空列表")
        void getHistory_whenRedisReturnsNull_returnsEmptyList() {
            when(listOperations.range(anyString(), anyLong(), anyLong())).thenReturn(null);

            List<String> history = searchService.getHistory(USER_UUID);

            assertThat(history).isEmpty();
        }
    }

    /**
     * clearHistory() 方法測試群組
     */
    @Nested
    @DisplayName("clearHistory() 清除搜尋歷史")
    class ClearHistoryTests {

        /**
         * 應刪除對應用戶的 Redis List key
         */
        @Test
        @DisplayName("應刪除 Redis search:history:{userUuid} key")
        void clearHistory_deletesRedisKey() {
            searchService.clearHistory(USER_UUID);

            verify(redisTemplate).delete("search:history:" + USER_UUID);
        }
    }

    /**
     * indexArticle() 方法測試群組
     */
    @Nested
    @DisplayName("indexArticle() 文章索引")
    class IndexArticleTests {

        /**
         * 應將文件儲存至 Elasticsearch Repository
         */
        @Test
        @DisplayName("應呼叫 Repository.save() 儲存文件")
        void indexArticle_savesToRepository() {
            ArticleDocument doc = buildTestDocument();

            searchService.indexArticle(doc);

            verify(articleSearchRepository).save(doc);
        }

        /**
         * D1：全量重建進行中，listener 的寫入只會進到舊索引（alias 仍指向它）；先記下這篇有變動，
         * 重建在切換 alias 前後回查 DB 補進新索引。必須「先記錄、再寫入」：反過來的話，寫入落在舊索引、
         * 記錄卻晚於切換後的補齊，這次變動就會隨切換消失。
         */
        @Test
        @DisplayName("D1：先把文章記進重建變動紀錄，再寫入 alias（順序不可顛倒）")
        void indexArticle_recordsJournalBeforeWriting() {
            ArticleDocument doc = buildTestDocument();

            searchService.indexArticle(doc);

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(reindexJournal, articleSearchRepository);
            order.verify(reindexJournal).recordArticle(doc.getId());
            order.verify(articleSearchRepository).save(doc);
        }

        @Test
        @DisplayName("D1：記錄變動失敗（Redis 故障）時不寫入、例外往外拋，由 MQ 容器重試")
        void indexArticle_journalFailure_propagatesWithoutWriting() {
            ArticleDocument doc = buildTestDocument();
            doThrow(new IllegalStateException("redis down")).when(reindexJournal).recordArticle(doc.getId());

            assertThatThrownBy(() -> searchService.indexArticle(doc)).hasMessage("redis down");
            verify(articleSearchRepository, never()).save(any());
        }
    }

    /**
     * deleteIndex() 方法測試群組
     */
    @Nested
    @DisplayName("deleteIndex() 移除文章索引")
    class DeleteIndexTests {

        /**
         * 應從 Elasticsearch Repository 刪除指定 ID
         */
        @Test
        @DisplayName("應呼叫 Repository.deleteById() 移除文件")
        void deleteIndex_deletesFromRepository() {
            String uuid = UUID.randomUUID().toString();

            searchService.deleteIndex(uuid);

            verify(articleSearchRepository).deleteById(uuid);
        }

        @Test
        @DisplayName("D1：下架／刪除同樣先記進重建變動紀錄，再從 alias 移除（重建補齊時會從新索引移除）")
        void deleteIndex_recordsJournalBeforeDeleting() {
            String uuid = UUID.randomUUID().toString();

            searchService.deleteIndex(uuid);

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(reindexJournal, articleSearchRepository);
            order.verify(reindexJournal).recordArticle(uuid);
            order.verify(articleSearchRepository).deleteById(uuid);
        }
    }

    /**
     * updateAuthorNickname() 方法測試群組
     */
    @Nested
    @DisplayName("updateAuthorNickname() 作者暱稱改寫")
    class UpdateAuthorNicknameTests {

        @Test
        @DisplayName("D1：先把作者記進重建變動紀錄，再經 alias 改寫；回傳被更新的文件數")
        void updateAuthorNickname_recordsJournalThenRewritesViaAlias() {
            UUID author = UUID.randomUUID();
            when(indexAdmin.updateAuthorNickname(author, "已刪除使用者#ab12", ArticleIndexAdmin.ALIAS)).thenReturn(2L);

            long updated = searchService.updateAuthorNickname(author, "已刪除使用者#ab12");

            assertThat(updated).isEqualTo(2L);
            org.mockito.InOrder order = org.mockito.Mockito.inOrder(reindexJournal, indexAdmin);
            order.verify(reindexJournal).recordAuthor(author);
            order.verify(indexAdmin).updateAuthorNickname(author, "已刪除使用者#ab12", ArticleIndexAdmin.ALIAS);
        }
    }

    /**
     * getIndexStatus() 方法測試群組
     */
    @Nested
    @DisplayName("getIndexStatus() 索引狀態查詢")
    class GetIndexStatusTests {

        /**
         * ES 查詢成功時，應回傳文件數、時間戳與 healthy=true
         */
        @Test
        @DisplayName("ES 健康時，應回傳正確文件數與健康狀態")
        void getIndexStatus_whenEsHealthy_returnsDocumentCountAndTimestamp() {
            when(articleSearchRepository.count()).thenReturn(13L);
            when(valueOperations.get("search:reindex:at")).thenReturn("2026-07-20T21:30:00");

            SearchIndexStatusResponse status = searchService.getIndexStatus();

            assertThat(status.getDocumentCount()).isEqualTo(13L);
            assertThat(status.getLastReindexAt()).isEqualTo("2026-07-20T21:30:00");
            assertThat(status.isHealthy()).isTrue();
        }

        /**
         * ES 查詢拋出例外時，不應拋出，應回傳 healthy=false 且 documentCount=null
         */
        @Test
        @DisplayName("ES 不可達時，應回傳 healthy=false 且 documentCount=null，不拋出")
        void getIndexStatus_whenEsThrows_returnsHealthyFalseWithNullCount() {
            when(articleSearchRepository.count()).thenThrow(new RuntimeException("ES down"));

            SearchIndexStatusResponse status = searchService.getIndexStatus();

            assertThat(status.isHealthy()).isFalse();
            assertThat(status.getDocumentCount()).isNull();
        }

        /**
         * 從未執行過 reindexAll() 時，Redis 無時間戳資料，lastReindexAt 應為 null
         */
        @Test
        @DisplayName("從未重建過時，lastReindexAt 應為 null")
        void getIndexStatus_whenNeverReindexed_lastReindexAtIsNull() {
            when(articleSearchRepository.count()).thenReturn(0L);
            when(valueOperations.get("search:reindex:at")).thenReturn(null);

            SearchIndexStatusResponse status = searchService.getIndexStatus();

            assertThat(status.getLastReindexAt()).isNull();
            assertThat(status.isHealthy()).isTrue();
        }

        /**
         * 介面 javadoc 承諾「本方法本身不拋出例外，避免拖垮儀表板整格顯示」，
         * 但原實作把 Redis 讀取放在 try 之外——Redis 掛掉時整個方法拋出，後台整格 500。
         * ES 有防護而 Redis 沒有，且 Redis 掛掉的機率不會比 ES 低。
         */
        @Test
        @DisplayName("Redis 不可達時，不應拋出；lastReindexAt 為 null，ES 健康仍回報 healthy=true")
        void getIndexStatus_whenRedisThrows_returnsStatusWithoutThrowing() {
            when(articleSearchRepository.count()).thenReturn(7L);
            when(valueOperations.get("search:reindex:at")).thenThrow(new RuntimeException("Redis down"));

            SearchIndexStatusResponse status = searchService.getIndexStatus();

            assertThat(status.getLastReindexAt()).isNull();
            assertThat(status.getDocumentCount()).isEqualTo(7L);
            assertThat(status.isHealthy())
                    .as("healthy 描述的是 Elasticsearch 可達性，不應被 Redis 故障影響")
                    .isTrue();
        }

        @Test
        @DisplayName("D1：狀態帶最近一次全量重建作業（狀態、進度、時間）")
        void getIndexStatus_includesLatestReindexJob() {
            SearchReindexJobResponse job = SearchReindexJobResponse.builder()
                    .jobId(UUID.randomUUID()).state(ReindexJobState.RUNNING).processed(200).total(450L).build();
            when(articleSearchRepository.count()).thenReturn(3L);
            when(reindexJobStore.current()).thenReturn(java.util.Optional.of(job));

            SearchIndexStatusResponse status = searchService.getIndexStatus();

            assertThat(status.getReindex()).isEqualTo(job);
        }

        @Test
        @DisplayName("D1：從未重建過時 reindex 為 null")
        void getIndexStatus_noReindexJob_isNull() {
            when(reindexJobStore.current()).thenReturn(java.util.Optional.empty());

            assertThat(searchService.getIndexStatus().getReindex()).isNull();
        }

        @Test
        @DisplayName("D1：讀取重建作業失敗（Redis 故障）不拋出，reindex 為 null，其餘欄位照常")
        void getIndexStatus_jobStoreFails_reindexNull() {
            when(articleSearchRepository.count()).thenReturn(7L);
            when(reindexJobStore.current()).thenThrow(new RuntimeException("Redis down"));

            SearchIndexStatusResponse status = searchService.getIndexStatus();

            assertThat(status.getReindex()).isNull();
            assertThat(status.getDocumentCount()).isEqualTo(7L);
            assertThat(status.isHealthy()).isTrue();
        }

        /** Redis 與 ES 同時故障時仍須回傳可顯示的結果，不可拋出 */
        @Test
        @DisplayName("Redis 與 ES 同時不可達時，回傳 healthy=false 且不拋出")
        void getIndexStatus_whenRedisAndEsBothFail_returnsUnhealthyWithoutThrowing() {
            when(valueOperations.get("search:reindex:at")).thenThrow(new RuntimeException("Redis down"));
            when(articleSearchRepository.count()).thenThrow(new RuntimeException("ES down"));

            SearchIndexStatusResponse status = searchService.getIndexStatus();

            assertThat(status.isHealthy()).isFalse();
            assertThat(status.getDocumentCount()).isNull();
            assertThat(status.getLastReindexAt()).isNull();
        }
    }
}
