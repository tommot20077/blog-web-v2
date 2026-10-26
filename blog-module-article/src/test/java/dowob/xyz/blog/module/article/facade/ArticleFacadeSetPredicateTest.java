package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.module.article.service.ArticleIdentityResolver;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleNavRef;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;

import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleFacadeSetPredicateTest {

    @Mock private ArticleMapper articleMapper;
    @Mock private ArticleService articleService;
    @Mock private ObjectProvider<SeriesFacade> seriesFacadeProvider;
    private ArticleFacadeImpl facade;

    /**
     * 手動建構 facade 並注入「真實」的 {@link ArticleDataAssembler}（包裝 mock 的 mapper 與 provider）。
     * 不用 {@code @InjectMocks}：它會把 assembler 注入為 null，日後任何測試觸及 findByIds 等方法就會 NPE。
     * 本測試不關心的其餘依賴傳 null（與先前 {@code @InjectMocks} 的行為一致）。
     */
    @BeforeEach
    void setUp() {
        facade = new ArticleFacadeImpl(articleMapper, null, null, articleService, null, null, null, null, null,
                new ArticleDataAssembler(new ArticleIdentityResolver(seriesFacadeProvider)), null, null, null);
    }

    @Nested
    class AssemblerWiring {

        @Test
        @DisplayName("facade 內的 ArticleDataAssembler 為真實物件：findByIds 不得因 assembler 為 null 而 NPE")
        void findByIds_withRealAssembler_returnsArticleData() {
            Article article = new Article();
            article.setId(1L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorUuid(UUID.randomUUID());
            when(articleService.findByIds(List.of(1L))).thenReturn(List.of(article));

            List<ArticleData> result = facade.findByIds(List.of(1L));

            assertThat(result).hasSize(1);
        }
    }

    @Nested
    class CountPublishedBySeriesIds {

        @Test
        @DisplayName("空輸入不查詢，直接回空 map")
        void emptyInputReturnsEmptyMapWithoutQuery() {
            assertThat(facade.countPublishedBySeriesIds(List.of())).isEmpty();
        }

        @Test
        @DisplayName("只回傳有公開文章的 series，且計數正確")
        void onlyReturnsSeriesWithPublishedArticlesAndCorrectCount() {
            when(articleMapper.countPublishedBySeriesIds(List.of(1L, 2L, 3L)))
                    .thenReturn(List.of(
                            new ArticleMapper.SeriesPublishedCountRow(1L, 3),
                            new ArticleMapper.SeriesPublishedCountRow(3L, 1)));

            Map<Long, Integer> result = facade.countPublishedBySeriesIds(List.of(1L, 2L, 3L));

            assertThat(result).containsOnlyKeys(1L, 3L);
            assertThat(result.get(1L)).isEqualTo(3);
            assertThat(result.get(3L)).isEqualTo(1);
        }

        @Test
        @DisplayName("輸入超過批次上限時切批查詢，各批結果合併為單一 map")
        void whenInputExceedsBatchLimit_splitsIntoBatchesAndMergesResult() {
            int total = BatchedQuery.BATCH_SIZE + 100;
            List<Long> seriesIds = IntStream.rangeClosed(1, total)
                    .mapToObj(Long::valueOf)
                    .toList();
            List<Integer> observedBatchSizes = new ArrayList<>();
            when(articleMapper.countPublishedBySeriesIds(anyCollection())).thenAnswer(invocation -> {
                Collection<Long> batch = invocation.getArgument(0);
                observedBatchSizes.add(batch.size());
                return batch.stream()
                        .map(id -> new ArticleMapper.SeriesPublishedCountRow(id, 1))
                        .toList();
            });

            Map<Long, Integer> result = facade.countPublishedBySeriesIds(seriesIds);

            assertThat(observedBatchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
            assertThat(result).hasSize(total);
            assertThat(result.get(1L)).isEqualTo(1);
            assertThat(result.get((long) total)).isEqualTo(1);
        }
    }

    @Nested
    class NavQueries {

        @Test
        @DisplayName("首篇沒有 prev")
        void firstArticleHasNoPrev() {
            when(articleMapper.findPrevPublishedInSeries(10L, 1)).thenReturn(null);
            assertThat(facade.findPrevPublishedInSeries(10L, 1)).isEmpty();
        }

        @Test
        @DisplayName("有 prev 時包成 ArticleNavRef")
        void whenPrevExists_wrapsAsArticleNavRef() {
            UUID uuid = UUID.randomUUID();
            when(articleMapper.findPrevPublishedInSeries(10L, 3))
                    .thenReturn(new ArticleNavRef(uuid, "前一篇", "prev-slug"));

            Optional<ArticleNavRef> result = facade.findPrevPublishedInSeries(10L, 3);

            assertThat(result).isPresent();
            assertThat(result.get().uuid()).isEqualTo(uuid);
            assertThat(result.get().title()).isEqualTo("前一篇");
            assertThat(result.get().slug()).isEqualTo("prev-slug");
        }

        @Test
        @DisplayName("末篇沒有 next")
        void lastArticleHasNoNext() {
            when(articleMapper.findNextPublishedInSeries(10L, 9)).thenReturn(null);
            assertThat(facade.findNextPublishedInSeries(10L, 9)).isEmpty();
        }
    }
}
