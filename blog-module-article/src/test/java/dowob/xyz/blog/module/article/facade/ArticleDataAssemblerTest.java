package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.service.ArticleIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleDataAssembler} 單元測試。
 *
 * <p>驗證 M6-a 之後 ArticleData 的 authorUuid <b>直接取自 {@code articles.author_uuid}</b>（不再經 users 查詢，
 * 也沒有 Long 的作者主鍵）；seriesUuid（M6-b 才改）仍經 {@link SeriesFacade} <b>批次一次取得</b>，不得逐筆查詢，
 * 且 SeriesFacade 以 {@link ObjectProvider} 延遲取得（打斷 SeriesFacadeImpl → ArticleFacade 的 bean 循環）。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleDataAssembler 單元測試")
class ArticleDataAssemblerTest {

    @Mock private ObjectProvider<SeriesFacade> seriesFacadeProvider;
    @Mock private SeriesFacade seriesFacade;

    private ArticleDataAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ArticleDataAssembler(new ArticleIdentityResolver(seriesFacadeProvider));
    }

    private static Article article(long id, UUID authorUuid, Long seriesId) {
        Article a = new Article();
        a.setId(id);
        a.setUuid(UUID.randomUUID());
        a.setAuthorUuid(authorUuid);
        a.setStatus(ArticleStatus.PUBLISHED);
        a.setSeriesId(seriesId);
        a.setSeriesPosition(seriesId == null ? null : 1);
        return a;
    }

    @Test
    @DisplayName("單篇文章 → authorUuid 直接取自文章，seriesUuid 經 SeriesFacade 填值")
    void toArticleData_singleArticle_fillsAuthorAndSeriesUuid() {
        UUID authorUuid = UUID.randomUUID();
        UUID seriesUuid = UUID.randomUUID();
        when(seriesFacadeProvider.getObject()).thenReturn(seriesFacade);
        when(seriesFacade.findUuidsByIds(anyCollection())).thenReturn(Map.of(3L, seriesUuid));

        ArticleData data = assembler.toArticleData(article(1L, authorUuid, 3L));

        assertThat(data.authorUuid()).isEqualTo(authorUuid);
        assertThat(data.seriesId()).isEqualTo(3L);
        assertThat(data.seriesUuid()).isEqualTo(seriesUuid);
    }

    @Test
    @DisplayName("文章無系列 → seriesUuid 為 null，且完全不取得 SeriesFacade")
    void toArticleData_noSeries_seriesUuidNullAndSeriesFacadeUntouched() {
        UUID authorUuid = UUID.randomUUID();

        ArticleData data = assembler.toArticleData(article(1L, authorUuid, null));

        assertThat(data.authorUuid()).isEqualTo(authorUuid);
        assertThat(data.seriesUuid()).isNull();
        verifyNoInteractions(seriesFacadeProvider, seriesFacade);
    }

    @Test
    @DisplayName("多篇文章 → 系列只批次查詢一次（不逐筆），送出的 id 已去重、不含 null；作者不發任何查詢")
    void toArticleDataList_multipleArticles_resolvesSeriesUuidsInOneBatch() {
        UUID authorA = UUID.randomUUID();
        UUID authorB = UUID.randomUUID();
        UUID seriesX = UUID.randomUUID();
        UUID seriesY = UUID.randomUUID();
        List<Article> articles = List.of(
                article(1L, authorA, 100L),
                article(2L, authorA, 100L),
                article(3L, authorB, 200L),
                article(4L, authorB, null));
        when(seriesFacadeProvider.getObject()).thenReturn(seriesFacade);
        when(seriesFacade.findUuidsByIds(anyCollection())).thenReturn(Map.of(100L, seriesX, 200L, seriesY));

        List<ArticleData> result = assembler.toArticleDataList(articles);

        assertThat(result).extracting(ArticleData::authorUuid).containsExactly(authorA, authorA, authorB, authorB);
        assertThat(result).extracting(ArticleData::seriesUuid).containsExactly(seriesX, seriesX, seriesY, null);
        assertThat(result).extracting(ArticleData::id).containsExactly(1L, 2L, 3L, 4L);

        verify(seriesFacade, times(1)).findUuidsByIds(anyCollection());
        verify(seriesFacadeProvider, times(1)).getObject();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> seriesIds = ArgumentCaptor.forClass(Collection.class);
        verify(seriesFacade).findUuidsByIds(seriesIds.capture());
        assertThat(seriesIds.getValue()).containsExactlyInAnyOrder(100L, 200L);
    }

    @Test
    @DisplayName("系列 id 在 SeriesFacade 查無 → 該篇 seriesUuid 為 null，不拋錯，authorUuid 不受影響")
    void toArticleDataList_seriesNotFound_seriesUuidNull() {
        UUID authorUuid = UUID.randomUUID();
        when(seriesFacadeProvider.getObject()).thenReturn(seriesFacade);
        when(seriesFacade.findUuidsByIds(anyCollection())).thenReturn(Map.of());

        List<ArticleData> result = assembler.toArticleDataList(List.of(article(1L, authorUuid, 999L)));

        assertThat(result.get(0).seriesId()).isEqualTo(999L);
        assertThat(result.get(0).seriesUuid()).isNull();
        assertThat(result.get(0).authorUuid()).isEqualTo(authorUuid);
    }

    @Test
    @DisplayName("空輸入 → 回傳空清單，不發任何查詢")
    void toArticleDataList_empty_noQueries() {
        assertThat(assembler.toArticleDataList(List.of())).isEmpty();
        assertThat(assembler.toArticleDataList(null)).isEmpty();

        verifyNoInteractions(seriesFacadeProvider, seriesFacade);
    }
}
