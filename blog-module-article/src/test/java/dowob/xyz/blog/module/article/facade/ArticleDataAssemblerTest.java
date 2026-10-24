package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleDataAssembler} 單元測試。
 *
 * <p>驗證設計 §3.1「過渡 API」：ArticleData 的 authorUuid（經 users reference data）與
 * seriesUuid（經 {@link SeriesFacade}）一律<b>批次一次取得</b>，不得逐筆查詢；
 * 且 SeriesFacade 以 {@link ObjectProvider} 延遲取得（打斷 SeriesFacadeImpl → ArticleFacade 的 bean 循環）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleDataAssembler 單元測試")
class ArticleDataAssemblerTest {

    @Mock private ArticleMapper articleMapper;
    @Mock private ObjectProvider<SeriesFacade> seriesFacadeProvider;
    @Mock private SeriesFacade seriesFacade;

    private ArticleDataAssembler assembler;
    private WarnLogCapture logCapture;

    @BeforeEach
    void setUp() {
        assembler = new ArticleDataAssembler(articleMapper, seriesFacadeProvider);
        logCapture = new WarnLogCapture(ArticleDataAssembler.class);
    }

    @AfterEach
    void detachLogAppender() {
        logCapture.close();
    }

    private List<String> warnings() {
        return logCapture.warnings();
    }

    private static Article article(long id, long authorId, Long seriesId) {
        Article a = new Article();
        a.setId(id);
        a.setUuid(UUID.randomUUID());
        a.setAuthorId(authorId);
        a.setStatus(ArticleStatus.PUBLISHED);
        a.setSeriesId(seriesId);
        a.setSeriesPosition(seriesId == null ? null : 1);
        return a;
    }

    @Test
    @DisplayName("單篇文章 → authorUuid 與 seriesUuid 都被填值")
    void toArticleData_singleArticle_fillsAuthorAndSeriesUuid() {
        UUID authorUuid = UUID.randomUUID();
        UUID seriesUuid = UUID.randomUUID();
        when(articleMapper.findUserUuidsByIds(anyCollection()))
                .thenReturn(List.of(new ArticleMapper.UserUuidRow(7L, authorUuid)));
        when(seriesFacadeProvider.getObject()).thenReturn(seriesFacade);
        when(seriesFacade.findUuidsByIds(anyCollection())).thenReturn(Map.of(3L, seriesUuid));

        ArticleData data = assembler.toArticleData(article(1L, 7L, 3L));

        assertThat(data.authorId()).isEqualTo(7L);
        assertThat(data.authorUuid()).isEqualTo(authorUuid);
        assertThat(data.seriesId()).isEqualTo(3L);
        assertThat(data.seriesUuid()).isEqualTo(seriesUuid);
    }

    @Test
    @DisplayName("文章無系列 → seriesUuid 為 null，且完全不取得 SeriesFacade")
    void toArticleData_noSeries_seriesUuidNullAndSeriesFacadeUntouched() {
        UUID authorUuid = UUID.randomUUID();
        when(articleMapper.findUserUuidsByIds(anyCollection()))
                .thenReturn(List.of(new ArticleMapper.UserUuidRow(7L, authorUuid)));

        ArticleData data = assembler.toArticleData(article(1L, 7L, null));

        assertThat(data.authorUuid()).isEqualTo(authorUuid);
        assertThat(data.seriesUuid()).isNull();
        verifyNoInteractions(seriesFacadeProvider, seriesFacade);
    }

    @Test
    @DisplayName("多篇文章 → 作者與系列各只批次查詢一次（不逐筆），且送出的 id 已去重、不含 null")
    void toArticleDataList_multipleArticles_resolvesUuidsInOneBatchEach() {
        UUID authorA = UUID.randomUUID();
        UUID authorB = UUID.randomUUID();
        UUID seriesX = UUID.randomUUID();
        UUID seriesY = UUID.randomUUID();
        List<Article> articles = List.of(
                article(1L, 7L, 100L),
                article(2L, 7L, 100L),
                article(3L, 8L, 200L),
                article(4L, 8L, null));
        when(articleMapper.findUserUuidsByIds(anyCollection())).thenReturn(List.of(
                new ArticleMapper.UserUuidRow(7L, authorA), new ArticleMapper.UserUuidRow(8L, authorB)));
        when(seriesFacadeProvider.getObject()).thenReturn(seriesFacade);
        when(seriesFacade.findUuidsByIds(anyCollection())).thenReturn(Map.of(100L, seriesX, 200L, seriesY));

        List<ArticleData> result = assembler.toArticleDataList(articles);

        assertThat(result).extracting(ArticleData::authorUuid).containsExactly(authorA, authorA, authorB, authorB);
        assertThat(result).extracting(ArticleData::seriesUuid).containsExactly(seriesX, seriesX, seriesY, null);
        assertThat(result).extracting(ArticleData::id).containsExactly(1L, 2L, 3L, 4L);

        verify(articleMapper, times(1)).findUserUuidsByIds(anyCollection());
        verify(seriesFacade, times(1)).findUuidsByIds(anyCollection());
        verify(seriesFacadeProvider, times(1)).getObject();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> userIds = ArgumentCaptor.forClass(Collection.class);
        verify(articleMapper).findUserUuidsByIds(userIds.capture());
        assertThat(userIds.getValue()).containsExactlyInAnyOrder(7L, 8L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> seriesIds = ArgumentCaptor.forClass(Collection.class);
        verify(seriesFacade).findUuidsByIds(seriesIds.capture());
        assertThat(seriesIds.getValue()).containsExactlyInAnyOrder(100L, 200L);
    }

    @Test
    @DisplayName("系列 id 在 SeriesFacade 查無 → 該篇 seriesUuid 為 null，不拋錯")
    void toArticleDataList_seriesNotFound_seriesUuidNull() {
        when(articleMapper.findUserUuidsByIds(anyCollection())).thenReturn(List.of());
        when(seriesFacadeProvider.getObject()).thenReturn(seriesFacade);
        when(seriesFacade.findUuidsByIds(anyCollection())).thenReturn(Map.of());

        List<ArticleData> result = assembler.toArticleDataList(List.of(article(1L, 7L, 999L)));

        assertThat(result.get(0).seriesId()).isEqualTo(999L);
        assertThat(result.get(0).seriesUuid()).isNull();
        assertThat(result.get(0).authorUuid()).isNull();
    }

    @Test
    @DisplayName("空輸入 → 回傳空清單，不發任何查詢")
    void toArticleDataList_empty_noQueries() {
        assertThat(assembler.toArticleDataList(List.of())).isEmpty();
        assertThat(assembler.toArticleDataList(null)).isEmpty();

        verifyNoInteractions(articleMapper, seriesFacadeProvider, seriesFacade);
    }

    @Test
    @DisplayName("作者數超過批次上限 → 經 BatchedQuery 切批，且每批不超過上限")
    void resolveAuthorUuids_overBatchSize_splitsIntoBatches() {
        int total = BatchedQuery.BATCH_SIZE + 100;
        List<Long> authorIds = IntStream.rangeClosed(1, total).mapToObj(Long::valueOf).toList();
        List<Integer> batchSizes = new ArrayList<>();
        when(articleMapper.findUserUuidsByIds(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> batch = invocation.getArgument(0);
            batchSizes.add(batch.size());
            return batch.stream().map(id -> new ArticleMapper.UserUuidRow(id, UUID.randomUUID())).toList();
        });

        Map<Long, UUID> result = assembler.resolveAuthorUuids(authorIds);

        assertThat(batchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
        assertThat(result).hasSize(total);
    }

    @Test
    @DisplayName("resolveAuthorUuids → null 與空集合不發查詢；含 null 元素時忽略該元素")
    void resolveAuthorUuids_nullAndEmpty_noQuery() {
        assertThat(assembler.resolveAuthorUuids(null)).isEmpty();
        assertThat(assembler.resolveAuthorUuids(List.of())).isEmpty();

        verify(articleMapper, never()).findUserUuidsByIds(anyCollection());
    }

    @Test
    @DisplayName("查不到作者 uuid（資料異常）→ authorUuid 仍為 null，並以 warn 記錄 article id 與 author id")
    void toArticleData_authorUuidNotFound_logsWarnWithArticleIdAndAuthorId() {
        when(articleMapper.findUserUuidsByIds(anyCollection())).thenReturn(List.of());

        ArticleData data = assembler.toArticleData(article(11L, 77L, null));

        assertThat(data.authorUuid()).isNull();
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("11").contains("77");
    }

    @Test
    @DisplayName("批次中只有部分作者查不到 → 只為查不到者各記一筆 warn")
    void toArticleDataList_partialAuthorMissing_warnsOnlyForMissing() {
        UUID authorA = UUID.randomUUID();
        when(articleMapper.findUserUuidsByIds(anyCollection()))
                .thenReturn(List.of(new ArticleMapper.UserUuidRow(7L, authorA)));

        List<ArticleData> result = assembler.toArticleDataList(List.of(
                article(1L, 7L, null), article(2L, 99L, null), article(3L, 99L, null)));

        assertThat(result).extracting(ArticleData::authorUuid).containsExactly(authorA, null, null);
        assertThat(warnings()).hasSize(2);
        assertThat(warnings()).allSatisfy(m -> assertThat(m).contains("99"));
    }

    @Test
    @DisplayName("作者 uuid 都查得到 → 不記 warn")
    void toArticleDataList_allAuthorsResolved_noWarn() {
        when(articleMapper.findUserUuidsByIds(anyCollection()))
                .thenReturn(List.of(new ArticleMapper.UserUuidRow(7L, UUID.randomUUID())));

        assembler.toArticleDataList(List.of(article(1L, 7L, null)));

        assertThat(warnings()).isEmpty();
    }
}
