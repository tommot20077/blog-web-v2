package dowob.xyz.blog.module.search.service;

import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.model.dto.response.SearchIndexStatusResponse;
import dowob.xyz.blog.module.search.model.dto.response.SearchResultResponse;
import dowob.xyz.blog.module.search.repository.ArticleSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
                .author(new ArticleDocument.AuthorInfo(1L, "yuan", "Yuan"))
                .tags(List.of(new ArticleDocument.TagInfo(UUID.randomUUID(), "Java", "java")))
                .publishedAt(LocalDateTime.now())
                .viewCount(100L)
                .likeCount(50L)
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
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of());
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);
            when(zSetOperations.incrementScore(anyString(), anyString(), anyDouble())).thenReturn(1.0);

            searchService.search("java", null, "relevance", 1, 10, 42L);

            verify(zSetOperations).incrementScore("search:hot", "java", 1.0);
            verify(listOperations).leftPush("search:history:42", "java");
            verify(listOperations).trim("search:history:42", 0, 19);
        }

        /**
         * 匿名使用者搜尋時，只記錄熱門詞，不記錄個人歷史
         */
        @Test
        @DisplayName("匿名使用者搜尋，應只記錄熱門搜尋，不記錄個人歷史")
        void search_withKeywordAndNullUserId_doesNotRecordHistory() {
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of());
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

            searchService.search(null, null, "relevance", 1, 10, 1L);

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
        @DisplayName("sort=hot 時，應以 viewCount 和 likeCount 降序排序")
        void search_withHotSort_sortsByViewAndLikeCount() {
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of());
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

            PageResult<SearchResultResponse> result = searchService.search(null, null, "hot", 1, 10, null);

            assertThat(result).isNotNull();
            verify(elasticsearchOperations).search(any(Query.class), eq(ArticleDocument.class));
        }

        @Test
        @DisplayName("文章 tags 為 null 時，tagNames 應回傳空列表")
        void search_whenDocTagsNull_tagNamesIsEmpty() {
            ArticleDocument doc = ArticleDocument.builder()
                    .id(UUID.randomUUID().toString())
                    .title("No Tags")
                    .summary("summary")
                    .slug("no-tags")
                    .author(new ArticleDocument.AuthorInfo(1L, "yuan", "Yuan"))
                    .tags(null)
                    .publishedAt(LocalDateTime.now())
                    .viewCount(0L)
                    .likeCount(0L)
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
                    .viewCount(0L)
                    .likeCount(0L)
                    .status("PUBLISHED")
                    .build();
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of(doc));
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

            PageResult<SearchResultResponse> result = searchService.search(null, null, "relevance", 1, 10, null);

            assertThat(result.getRecords().get(0).getAuthorNickname()).isNull();
        }

        @Test
        @DisplayName("同時指定關鍵字與標籤時，應同時應用 MultiMatch 與 Nested Query")
        void search_withKeywordAndTag_appliesBothFilters() {
            SearchHits<ArticleDocument> hits = mockSearchHits(List.of());
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);
            when(zSetOperations.incrementScore(anyString(), anyString(), anyDouble())).thenReturn(1.0);

            PageResult<SearchResultResponse> result = searchService.search("Spring", "java", "relevance", 1, 10, 1L);

            assertThat(result).isNotNull();
            verify(zSetOperations).incrementScore("search:hot", "spring", 1.0);
            verify(listOperations).leftPush("search:history:1", "spring");
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
            when(listOperations.range("search:history:7", 0, 19))
                    .thenReturn(List.of("spring boot", "java基礎"));

            List<String> history = searchService.getHistory(7L);

            assertThat(history).containsExactly("spring boot", "java基礎");
            verify(listOperations).range("search:history:7", 0, 19);
        }

        /**
         * Redis 回傳 null 時，應回傳空列表
         */
        @Test
        @DisplayName("Redis 回傳 null 時，應回傳空列表")
        void getHistory_whenRedisReturnsNull_returnsEmptyList() {
            when(listOperations.range(anyString(), anyLong(), anyLong())).thenReturn(null);

            List<String> history = searchService.getHistory(7L);

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
        @DisplayName("應刪除 Redis search:history:{userId} key")
        void clearHistory_deletesRedisKey() {
            searchService.clearHistory(99L);

            verify(redisTemplate).delete("search:history:99");
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
    }

    /**
     * reindexAll() 方法測試群組
     */
    @Nested
    @DisplayName("reindexAll() 全量重建索引")
    class ReindexAllTests {

        /**
         * 重建流程會掃描索引現況以找出幽靈 document；預設 stub 成「索引為空」，
         * 個別測試需要幽靈情境時再自行覆寫。
         */
        @BeforeEach
        void stubEmptyIndexScan() {
            SearchHits<ArticleDocument> emptyHits = mockSearchHits(List.of());
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class)))
                    .thenReturn(emptyHits);
        }

        /**
         * 幽靈 document ＝ ES 裡有、但 DB 已經不是 PUBLISHED（被刪除或下架）的文件。
         *
         * <p>原本 reindexAll 只做 saveAll，從不刪任何 document，所以
         * 「MQ 失手也會被下次 reindexAll 清掉」這個安全網事實上不存在——
         * 管理員點「重建索引」也修不好已下架文章仍搜尋得到的問題
         * （見 ai-docs/backlog/2026-07-29-index-cache-rebuild-completeness.md）。</p>
         */
        @Test
        @DisplayName("重建後應刪除「ES 有但 DB 已非 PUBLISHED」的幽靈 document")
        void reindexAll_removesGhostDocumentsMissingFromDatabase() {
            UUID liveUuid = UUID.randomUUID();
            String ghostId = UUID.randomUUID().toString();
            ArticleIndexData live = new ArticleIndexData(
                    liveUuid, "仍公開的文章", "still-public",
                    "summary", "content",
                    1L, "yuan", "Yuan",
                    LocalDateTime.now(), 0L, 0L, List.of());
            when(articleFacade.findAllPublishedForIndex()).thenReturn(List.of(live));
            SearchHits<ArticleDocument> indexedHits = mockSearchHits(List.of(
                    ArticleDocument.builder().id(liveUuid.toString()).build(),
                    ArticleDocument.builder().id(ghostId).build()));
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class)))
                    .thenReturn(indexedHits);

            searchService.reindexAll();

            verify(articleSearchRepository).saveAll(any());
            verify(articleSearchRepository).deleteAllById(List.of(ghostId));
        }

        @Test
        @DisplayName("索引與 DB 一致時，不應刪除任何 document")
        void reindexAll_whenIndexMatchesDatabase_deletesNothing() {
            UUID liveUuid = UUID.randomUUID();
            ArticleIndexData live = new ArticleIndexData(
                    liveUuid, "仍公開的文章", "still-public",
                    "summary", "content",
                    1L, "yuan", "Yuan",
                    LocalDateTime.now(), 0L, 0L, List.of());
            when(articleFacade.findAllPublishedForIndex()).thenReturn(List.of(live));
            SearchHits<ArticleDocument> indexedHits = mockSearchHits(List.of(
                    ArticleDocument.builder().id(liveUuid.toString()).build()));
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class)))
                    .thenReturn(indexedHits);

            searchService.reindexAll();

            verify(articleSearchRepository, never()).deleteAllById(any());
        }

        /**
         * 幽靈清除是重建的附加保險，掃描失敗不該讓「索引其實已重建成功」對外報錯
         * （與時間戳寫入同一套 best-effort 判準）。
         */
        @Test
        @DisplayName("掃描索引失敗時，不應讓已完成的重建對外拋錯")
        void reindexAll_whenGhostScanFails_doesNotThrow() {
            when(articleFacade.findAllPublishedForIndex()).thenReturn(List.of());
            when(elasticsearchOperations.search(any(Query.class), eq(ArticleDocument.class)))
                    .thenThrow(new RuntimeException("ES down"));

            assertThatCode(() -> searchService.reindexAll()).doesNotThrowAnyException();

            verify(articleSearchRepository).saveAll(any());
        }

        /**
         * 應透過 ArticleFacade 取得所有文章並批次儲存至 ES
         */
        @Test
        @DisplayName("應從 ArticleFacade 取得文章並批次儲存")
        void reindexAll_fetchesFromFacadeAndSavesAll() {
            ArticleIndexData data = new ArticleIndexData(
                    UUID.randomUUID(), "Spring Boot 教學", "spring-boot-tutorial",
                    "本文介紹 Spring Boot", "Spring Boot 是一個框架",
                    1L, "yuan", "Yuan",
                    LocalDateTime.now(), 0L, 0L, List.of());
            when(articleFacade.findAllPublishedForIndex()).thenReturn(List.of(data));

            searchService.reindexAll();

            verify(articleFacade).findAllPublishedForIndex();
            verify(articleSearchRepository).saveAll(any());
        }

        /**
         * 無已發布文章時，應儲存空列表
         */
        @Test
        @DisplayName("無已發布文章時，應呼叫 saveAll 儲存空列表")
        void reindexAll_whenNoArticles_savesEmptyList() {
            when(articleFacade.findAllPublishedForIndex()).thenReturn(List.of());

            searchService.reindexAll();

            verify(articleSearchRepository).saveAll(List.of());
        }

        @Test
        @DisplayName("ArticleIndexData tags 為 null 時，轉換後 tags 為空列表")
        void reindexAll_whenTagsNull_convertsToEmptyTagList() {
            ArticleIndexData data = new ArticleIndexData(
                    UUID.randomUUID(), "No Tags Article", "no-tags",
                    "summary", "content",
                    1L, "yuan", "Yuan",
                    LocalDateTime.now(), 0L, 0L, null);
            when(articleFacade.findAllPublishedForIndex()).thenReturn(List.of(data));

            searchService.reindexAll();

            verify(articleSearchRepository).saveAll(any());
        }

        @Test
        @DisplayName("ArticleIndexData 含標籤時，轉換後 tags 正確映射")
        void reindexAll_withTags_convertsTagsCorrectly() {
            ArticleIndexData.TagData tagData = new ArticleIndexData.TagData(UUID.randomUUID(), "Java", "java");
            ArticleIndexData data = new ArticleIndexData(
                    UUID.randomUUID(), "Tagged Article", "tagged",
                    "summary", "content",
                    1L, "yuan", "Yuan",
                    LocalDateTime.now(), 10L, 5L, List.of(tagData));
            when(articleFacade.findAllPublishedForIndex()).thenReturn(List.of(data));

            searchService.reindexAll();

            verify(articleSearchRepository).saveAll(any());
        }

        /**
         * 全量重建完成後，應將當下時間以 ISO-8601 字串寫入 Redis 時間戳 Key
         */
        @Test
        @DisplayName("重建完成後，應寫入 search:reindex:at 時間戳")
        void reindexAll_afterCompletion_writesTimestampToRedis() {
            when(articleFacade.findAllPublishedForIndex()).thenReturn(List.of());

            searchService.reindexAll();

            verify(valueOperations).set(eq("search:reindex:at"), anyString());
        }

        /**
         * 時間戳只是給後台顯示用的附帶資訊，Redis 掛掉不應讓「索引其實已經重建成功」
         * 的動作對外回報失敗——否則管理員會重複點擊重建，而每次都真的重建了一遍。
         * 專案內 MQ 發送皆為 best-effort，此處保持一致。
         */
        @Test
        @DisplayName("Redis 時間戳寫入失敗時，不應讓已完成的重建對外拋錯")
        void reindexAll_whenTimestampWriteFails_doesNotThrow() {
            when(articleFacade.findAllPublishedForIndex()).thenReturn(List.of());
            doThrow(new RuntimeException("Redis down"))
                    .when(valueOperations).set(eq("search:reindex:at"), anyString());

            assertThatCode(() -> searchService.reindexAll()).doesNotThrowAnyException();

            verify(articleSearchRepository).saveAll(any());
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
