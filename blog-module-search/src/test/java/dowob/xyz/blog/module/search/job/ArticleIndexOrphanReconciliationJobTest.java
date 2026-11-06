package dowob.xyz.blog.module.search.job;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.repository.ArticleSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleIndexOrphanReconciliationJob} 單元測試：子類別提供的三個鉤子、識別與排程標註
 * （框架行為由 blog-infrastructure 的測試涵蓋，整體流程見 {@code ArticleIndexOrphanReconciliationJobIT}）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleIndexOrphanReconciliationJob 單元測試")
class ArticleIndexOrphanReconciliationJobTest {

    private ElasticsearchOperations operations;
    private ArticleSearchRepository repository;
    private ArticleFacade articleFacade;
    private ArticleIndexOrphanReconciliationJob job;

    @BeforeEach
    void setUp() {
        operations = mock(ElasticsearchOperations.class);
        repository = mock(ArticleSearchRepository.class);
        articleFacade = mock(ArticleFacade.class);
        job = new ArticleIndexOrphanReconciliationJob(mock(StringRedisTemplate.class), operations, repository,
                articleFacade);
    }

    @SuppressWarnings("unchecked")
    private SearchHits<ArticleDocument> hitsOf(String... ids) {
        SearchHits<ArticleDocument> hits = mock(SearchHits.class);
        List<SearchHit<ArticleDocument>> list = new java.util.ArrayList<>();
        for (String id : ids) {
            SearchHit<ArticleDocument> hit = mock(SearchHit.class);
            when(hit.getId()).thenReturn(id);
            when(hit.getSortValues()).thenReturn(List.of(id));
            list.add(hit);
        }
        when(hits.getSearchHits()).thenReturn(list);
        return hits;
    }

    private NativeQuery captureQuery() {
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(operations).search(captor.capture(), eq(ArticleDocument.class));
        return (NativeQuery) captor.getValue();
    }

    @Test
    @DisplayName("module 與 table 識別為 search／blog_articles")
    void identity_isSearchAndBlogArticles() {
        assertThat(job.module()).isEqualTo("search");
        assertThat(job.table()).isEqualTo("blog_articles");
    }

    @Test
    @DisplayName("fetchReferencedUuids：把 ES 文件 id 轉回 UUID，保持 ES 回傳順序")
    void fetchReferencedUuids_parsesDocumentIds() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        SearchHits<ArticleDocument> hits = hitsOf(a.toString(), b.toString());
        when(operations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

        assertThat(job.fetchReferencedUuids(null, 500)).containsExactly(a, b);
    }

    @Test
    @DisplayName("fetchReferencedUuids：游標為 null 時從頭開始，且依文件 id 升冪、單頁筆數為 limit，不用深分頁")
    void fetchReferencedUuids_nullCursor_sortsAscendingWithoutSearchAfter() {
        SearchHits<ArticleDocument> hits = hitsOf();
        when(operations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

        job.fetchReferencedUuids(null, 7);

        NativeQuery query = captureQuery();
        assertThat(query.getSearchAfter()).isNullOrEmpty();
        assertThat(query.getSort().getOrderFor(ArticleIndexOrphanReconciliationJob.SORT_FIELD)).isNotNull();
        assertThat(query.getSort().getOrderFor(ArticleIndexOrphanReconciliationJob.SORT_FIELD).isAscending())
                .isTrue();
        assertThat(query.getPageable().getPageNumber()).as("keyset 分頁，永遠第 0 頁").isZero();
        assertThat(query.getPageable().getPageSize()).isEqualTo(7);
    }

    @Test
    @DisplayName("fetchReferencedUuids：有游標時以 search_after 帶入游標字串")
    void fetchReferencedUuids_withCursor_usesSearchAfter() {
        UUID cursor = UUID.randomUUID();
        SearchHits<ArticleDocument> hits = hitsOf();
        when(operations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

        job.fetchReferencedUuids(cursor, 10);

        assertThat(captureQuery().getSearchAfter()).containsExactly(cursor.toString());
    }

    @Test
    @DisplayName("fetchReferencedUuids：id 不是 UUID 的垃圾文件略過（不讓整次掃描失敗），交給全量重建清除")
    void fetchReferencedUuids_nonUuidId_isSkipped() {
        UUID a = UUID.randomUUID();
        SearchHits<ArticleDocument> hits = hitsOf("not-a-uuid", a.toString());
        when(operations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

        assertThat(job.fetchReferencedUuids(null, 10)).containsExactly(a);
    }

    @Test
    @DisplayName("fetchReferencedUuids：只掃有 uuid 欄位的文件（exists 過濾），缺欄位的舊文件等 reindex 後才納入")
    void fetchReferencedUuids_filtersDocumentsWithoutUuidField() {
        SearchHits<ArticleDocument> hits = hitsOf();
        when(operations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(hits);

        job.fetchReferencedUuids(null, 10);

        var esQuery = captureQuery().getQuery();
        assertThat(esQuery.isExists()).isTrue();
        assertThat(esQuery.exists().field()).isEqualTo(ArticleIndexOrphanReconciliationJob.SORT_FIELD);
    }

    @Test
    @DisplayName("fetchReferencedUuids：略過垃圾 id 後不足 limit，繼續以 ES 原始 sort 值往下取，直到湊滿（短頁只在 ES 真的沒資料時發生）")
    void fetchReferencedUuids_skippedGarbage_keepsFetchingUntilLimitOrExhausted() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        SearchHits<ArticleDocument> firstPage = hitsOf("garbage", a.toString(), b.toString());
        SearchHits<ArticleDocument> secondPage = hitsOf(c.toString());
        when(operations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(firstPage, secondPage);

        List<UUID> result = job.fetchReferencedUuids(null, 3);

        assertThat(result).containsExactly(a, b, c);
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(operations, org.mockito.Mockito.times(2)).search(captor.capture(), eq(ArticleDocument.class));
        NativeQuery second = (NativeQuery) captor.getAllValues().get(1);
        assertThat(second.getSearchAfter()).as("續取以最後一筆的原始 sort 值為游標").containsExactly(b.toString());
        assertThat(second.getPageable().getPageSize()).as("只補缺的筆數").isEqualTo(1);
    }

    @Test
    @DisplayName("fetchReferencedUuids：整頁都是垃圾 id 也不會回短頁，會繼續往下取")
    void fetchReferencedUuids_wholePageGarbage_continues() {
        UUID a = UUID.randomUUID();
        SearchHits<ArticleDocument> firstPage = hitsOf("g1", "g2");
        SearchHits<ArticleDocument> secondPage = hitsOf(a.toString());
        when(operations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(firstPage, secondPage);

        assertThat(job.fetchReferencedUuids(null, 2)).containsExactly(a);
    }

    @Test
    @DisplayName("fetchReferencedUuids：ES 沒有更多資料（回傳少於請求筆數）時才停止")
    void fetchReferencedUuids_exhausted_stops() {
        UUID a = UUID.randomUUID();
        SearchHits<ArticleDocument> page = hitsOf(a.toString());
        when(operations.search(any(Query.class), eq(ArticleDocument.class))).thenReturn(page);

        assertThat(job.fetchReferencedUuids(null, 5)).containsExactly(a);
        verify(operations, org.mockito.Mockito.times(1)).search(any(Query.class), eq(ArticleDocument.class));
    }

    @Test
    @DisplayName("filterExisting：委派 filterPublishedUuids（「仍為 PUBLISHED」），不使用不分狀態的 filterExistingUuids")
    void filterExisting_delegatesToFilterPublishedUuids() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(articleFacade.filterPublishedUuids(Set.of(a, b))).thenReturn(Set.of(a));

        assertThat(job.filterExisting(Set.of(a, b))).containsExactly(a);
        verify(articleFacade, never()).filterExistingUuids(any());
    }

    @Test
    @DisplayName("filterExisting：facade 拋例外時原樣往外拋（框架據此 FAILED、零刪除）")
    void filterExisting_facadeThrows_propagates() {
        when(articleFacade.filterPublishedUuids(any())).thenThrow(new IllegalStateException("down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> job.filterExisting(Set.of(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("removeOrphans：以字串 id 從 ES 刪除，回傳筆數")
    void removeOrphans_deletesByStringIds() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        int removed = job.removeOrphans(List.of(a, b));

        assertThat(removed).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<String>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).deleteAllById(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(a.toString(), b.toString());
    }

    @Test
    @DisplayName("removeOrphans：空集合不碰 ES")
    void removeOrphans_empty_doesNothing() {
        assertThat(job.removeOrphans(List.of())).isZero();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("有兩個建構子時，Spring 要用的公開建構子必須標 @Autowired（否則 context 啟動失敗：No default constructor）")
    void publicConstructor_isAutowired() throws Exception {
        var constructor = ArticleIndexOrphanReconciliationJob.class.getConstructor(
                StringRedisTemplate.class, ElasticsearchOperations.class, ArticleSearchRepository.class,
                ArticleFacade.class);

        assertThat(constructor.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class))
                .isTrue();
    }

    @Test
    @DisplayName("排序欄位為頂層 uuid keyword（_id 的 fielddata 預設停用，不可排序）")
    void sortField_isTopLevelUuidKeyword() {
        assertThat(ArticleIndexOrphanReconciliationJob.SORT_FIELD).isEqualTo("uuid");
    }

    @Test
    @DisplayName("scheduledRun 掛有 @Scheduled(cron = CRON)，且 CRON 不與其他校正排程相撞")
    void scheduledRun_isScheduledWithDedicatedCron() throws Exception {
        Method method = ArticleIndexOrphanReconciliationJob.class.getDeclaredMethod("scheduledRun");

        assertThat(method.getAnnotation(Scheduled.class).cron()).isEqualTo(ArticleIndexOrphanReconciliationJob.CRON);
        assertThat(ArticleIndexOrphanReconciliationJob.CRON).isEqualTo("0 29 4 * * *");
    }
}
