package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.dto.ArticleNavRef;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;

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
    @InjectMocks private ArticleFacadeImpl facade;

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

    @Nested
    class FilterReadableIds {

        @Test
        @DisplayName("空輸入不查詢，直接回空清單")
        void emptyInputReturnsEmptyListWithoutQuery() {
            assertThat(facade.filterReadableIds(List.of(), 1L, false)).isEmpty();
        }

        @Test
        @DisplayName("匿名只看得到 published，且維持輸入順序")
        void anonymousOnlySeesPublishedAndKeepsInputOrder() {
            when(articleMapper.findVisibilityRowsByIds(List.of(3L, 1L, 2L)))
                    .thenReturn(List.of(
                            new ArticleMapper.ArticleVisibilityRow(1L, "PUBLISHED", 99L),
                            new ArticleMapper.ArticleVisibilityRow(2L, "ARCHIVED", 99L),
                            new ArticleMapper.ArticleVisibilityRow(3L, "PUBLISHED", 99L)));

            List<Long> result = facade.filterReadableIds(List.of(3L, 1L, 2L), null, false);

            assertThat(result).containsExactly(3L, 1L);
        }

        @Test
        @DisplayName("作者本人看得到自己的非公開文章")
        void authorCanSeeOwnNonPublicArticle() {
            when(articleMapper.findVisibilityRowsByIds(List.of(1L, 2L)))
                    .thenReturn(List.of(
                            new ArticleMapper.ArticleVisibilityRow(1L, "ARCHIVED", 42L),
                            new ArticleMapper.ArticleVisibilityRow(2L, "DRAFT", 99L)));

            List<Long> result = facade.filterReadableIds(List.of(1L, 2L), 42L, false);

            assertThat(result).containsExactly(1L);
        }

        @Test
        @DisplayName("admin 看得到全部")
        void adminCanSeeAll() {
            when(articleMapper.findVisibilityRowsByIds(List.of(1L, 2L)))
                    .thenReturn(List.of(
                            new ArticleMapper.ArticleVisibilityRow(1L, "ARCHIVED", 99L),
                            new ArticleMapper.ArticleVisibilityRow(2L, "DRAFT", 99L)));

            List<Long> result = facade.filterReadableIds(List.of(1L, 2L), 7L, true);

            assertThat(result).containsExactly(1L, 2L);
        }

        @Test
        @DisplayName("已刪除的 id 不會出現在結果中")
        void deletedIdDoesNotAppearInResult() {
            when(articleMapper.findVisibilityRowsByIds(List.of(1L, 404L)))
                    .thenReturn(List.of(
                            new ArticleMapper.ArticleVisibilityRow(1L, "PUBLISHED", 99L)));

            assertThat(facade.filterReadableIds(List.of(1L, 404L), null, false))
                    .containsExactly(1L);
        }

        @Test
        @DisplayName("輸入超過批次上限時切批查詢，且結果仍維持輸入順序")
        void whenInputExceedsBatchLimit_splitsIntoBatchesAndKeepsInputOrder() {
            int total = BatchedQuery.BATCH_SIZE + 100;
            List<Long> descendingIds = new ArrayList<>(IntStream.rangeClosed(1, total)
                    .mapToObj(Long::valueOf)
                    .toList());
            Collections.reverse(descendingIds);
            List<Integer> observedBatchSizes = new ArrayList<>();
            when(articleMapper.findVisibilityRowsByIds(anyCollection())).thenAnswer(invocation -> {
                Collection<Long> batch = invocation.getArgument(0);
                observedBatchSizes.add(batch.size());
                return batch.stream()
                        .map(id -> new ArticleMapper.ArticleVisibilityRow(id, "PUBLISHED", 99L))
                        .toList();
            });

            List<Long> result = facade.filterReadableIds(descendingIds, null, false);

            assertThat(observedBatchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
            assertThat(result).containsExactlyElementsOf(descendingIds);
        }
    }
}
