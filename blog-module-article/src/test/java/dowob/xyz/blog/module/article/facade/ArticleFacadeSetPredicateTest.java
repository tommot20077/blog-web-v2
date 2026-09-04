package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.dto.ArticleNavRef;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleFacadeSetPredicateTest {

    @Mock private ArticleMapper articleMapper;
    @InjectMocks private ArticleFacadeImpl facade;

    @Nested
    class CountPublishedBySeriesIds {

        @Test
        void 空輸入不查詢直接回空map() {
            assertThat(facade.countPublishedBySeriesIds(List.of())).isEmpty();
        }

        @Test
        void 只回傳有公開文章的series且計數正確() {
            when(articleMapper.countPublishedBySeriesIds(List.of(1L, 2L, 3L)))
                    .thenReturn(List.of(
                            new ArticleMapper.SeriesPublishedCountRow(1L, 3),
                            new ArticleMapper.SeriesPublishedCountRow(3L, 1)));

            Map<Long, Integer> result = facade.countPublishedBySeriesIds(List.of(1L, 2L, 3L));

            assertThat(result).containsOnlyKeys(1L, 3L);
            assertThat(result.get(1L)).isEqualTo(3);
            assertThat(result.get(3L)).isEqualTo(1);
        }
    }

    @Nested
    class NavQueries {

        @Test
        void 首篇沒有prev() {
            when(articleMapper.findPrevPublishedInSeries(10L, 1)).thenReturn(null);
            assertThat(facade.findPrevPublishedInSeries(10L, 1)).isEmpty();
        }

        @Test
        void 有prev時包成ArticleNavRef() {
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
        void 末篇沒有next() {
            when(articleMapper.findNextPublishedInSeries(10L, 9)).thenReturn(null);
            assertThat(facade.findNextPublishedInSeries(10L, 9)).isEmpty();
        }
    }

    @Nested
    class FilterReadableIds {

        @Test
        void 空輸入不查詢直接回空清單() {
            assertThat(facade.filterReadableIds(List.of(), 1L, false)).isEmpty();
        }

        @Test
        void 匿名只看得到published且維持輸入順序() {
            when(articleMapper.findVisibilityRowsByIds(List.of(3L, 1L, 2L)))
                    .thenReturn(List.of(
                            new ArticleMapper.ArticleVisibilityRow(1L, "PUBLISHED", 99L),
                            new ArticleMapper.ArticleVisibilityRow(2L, "ARCHIVED", 99L),
                            new ArticleMapper.ArticleVisibilityRow(3L, "PUBLISHED", 99L)));

            List<Long> result = facade.filterReadableIds(List.of(3L, 1L, 2L), null, false);

            assertThat(result).containsExactly(3L, 1L);
        }

        @Test
        void 作者本人看得到自己的非公開文章() {
            when(articleMapper.findVisibilityRowsByIds(List.of(1L, 2L)))
                    .thenReturn(List.of(
                            new ArticleMapper.ArticleVisibilityRow(1L, "ARCHIVED", 42L),
                            new ArticleMapper.ArticleVisibilityRow(2L, "DRAFT", 99L)));

            List<Long> result = facade.filterReadableIds(List.of(1L, 2L), 42L, false);

            assertThat(result).containsExactly(1L);
        }

        @Test
        void admin看得到全部() {
            when(articleMapper.findVisibilityRowsByIds(List.of(1L, 2L)))
                    .thenReturn(List.of(
                            new ArticleMapper.ArticleVisibilityRow(1L, "ARCHIVED", 99L),
                            new ArticleMapper.ArticleVisibilityRow(2L, "DRAFT", 99L)));

            List<Long> result = facade.filterReadableIds(List.of(1L, 2L), 7L, true);

            assertThat(result).containsExactly(1L, 2L);
        }

        @Test
        void 已刪除的id不會出現在結果中() {
            when(articleMapper.findVisibilityRowsByIds(List.of(1L, 404L)))
                    .thenReturn(List.of(
                            new ArticleMapper.ArticleVisibilityRow(1L, "PUBLISHED", 99L)));

            assertThat(facade.filterReadableIds(List.of(1L, 404L), null, false))
                    .containsExactly(1L);
        }
    }
}
