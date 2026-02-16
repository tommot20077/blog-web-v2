package dowob.xyz.blog.module.search.facade;

import dowob.xyz.blog.module.search.document.ArticleDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SearchFacadeImpl 單元測試
 *
 * <p>
 * 驗證 {@code findSimilarArticles} 使用正確的 ES index name，
 * 以及 ES 異常時安全降級回傳空列表的行為。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SearchFacadeImpl 單元測試")
class SearchFacadeImplTest {

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    private SearchFacadeImpl facade;

    /**
     * 建立受測物件
     */
    @BeforeEach
    void setUp() {
        facade = new SearchFacadeImpl(elasticsearchOperations);
    }

    @SuppressWarnings("unchecked")
    private SearchHits<ArticleDocument> emptyHits() {
        SearchHits<ArticleDocument> hits = mock(SearchHits.class);
        when(hits.getSearchHits()).thenReturn(List.of());
        return hits;
    }

    @Nested
    @DisplayName("findSimilarArticles()")
    class FindSimilarArticles {

        @Test
        @DisplayName("查詢成功時回傳 UUID 列表")
        @SuppressWarnings("unchecked")
        void whenQuerySucceeds_returnsUuidList() {
            UUID resultUuid = UUID.randomUUID();
            SearchHits<ArticleDocument> hits = mock(SearchHits.class);
            SearchHit<ArticleDocument> hit = mock(SearchHit.class);
            when(hit.getId()).thenReturn(resultUuid.toString());
            when(hits.getSearchHits()).thenReturn(List.of(hit));
            when(elasticsearchOperations.search(any(NativeQuery.class), eq(ArticleDocument.class)))
                    .thenReturn(hits);

            List<UUID> result = facade.findSimilarArticles(UUID.randomUUID(), 5);

            assertThat(result).containsExactly(resultUuid);
        }

        @Test
        @DisplayName("ES 異常時回傳空列表（降級容錯）")
        void whenEsThrows_returnsEmptyList() {
            when(elasticsearchOperations.search(any(NativeQuery.class), eq(ArticleDocument.class)))
                    .thenThrow(new RuntimeException("ES 不可用"));

            List<UUID> result = facade.findSimilarArticles(UUID.randomUUID(), 5);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("查詢時使用正確的 index name：blog_articles")
        void usesCorrectIndexName() {
            UUID articleUuid = UUID.randomUUID();
            /** 先建立 stub 結果，避免在 thenReturn 中建立巢狀 stub 引發 UnfinishedStubbing */
            SearchHits<ArticleDocument> empty = emptyHits();
            when(elasticsearchOperations.search(any(NativeQuery.class), eq(ArticleDocument.class)))
                    .thenReturn(empty);

            facade.findSimilarArticles(articleUuid, 5);

            ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
            verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ArticleDocument.class));

            NativeQuery query = queryCaptor.getValue();
            String queryString = query.getQuery().toString();
            assertThat(queryString).contains("blog_articles");
            assertThat(queryString).doesNotContain("\"articles\"");
        }
    }
}
