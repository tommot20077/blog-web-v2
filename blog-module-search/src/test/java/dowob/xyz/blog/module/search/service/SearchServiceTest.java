package dowob.xyz.blog.module.search.service;

import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.module.search.document.ArticleDocument;
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
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
     * 測試前置：設定 Redis Template Mock 行為
     */
    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
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
            assertThat(result.getList()).hasSize(1);
            assertThat(result.getList().get(0).getTitle()).isEqualTo("Spring Boot 教學");
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

            SearchResultResponse response = result.getList().get(0);
            assertThat(response.getSlug()).isEqualTo("spring-boot-tutorial");
            assertThat(response.getAuthorNickname()).isEqualTo("Yuan");
            assertThat(response.getTagNames()).containsExactly("Java");
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
    }
}
