package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.service.ArticleQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookmarkQueryServiceTest {

    @Mock private BookmarkService bookmarkService;
    @Mock private ArticleFacade articleFacade;
    @Mock private ArticleQueryService articleQueryService;
    @InjectMocks private BookmarkQueryService service;

    private final Long userId = 1L;

    private List<ArticleSummaryResponse> summaries(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> ArticleSummaryResponse.builder().build())
                .toList();
    }

    @Test
    @DisplayName("total 為過濾後的可見數，而非收藏列數")
    void totalIsFilteredVisibleCountNotBookmarkRowCount() {
        when(bookmarkService.findAllMyBookmarkedArticleIds(userId))
                .thenReturn(List.of(1L, 2L, 3L, 4L, 5L));
        when(articleFacade.filterReadableIds(List.of(1L, 2L, 3L, 4L, 5L), userId, false))
                .thenReturn(List.of(1L, 3L));
        when(articleQueryService.getArticleSummariesByIds(List.of(1L, 3L)))
                .thenReturn(summaries(2));

        PageResult<ArticleSummaryResponse> result = service.listMyBookmarks(userId, false, 1, 20);

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getPages()).isEqualTo(1);
        assertThat(result.getRecords()).hasSize(2);
    }

    @Test
    @DisplayName("每頁筆數在有被過濾項時仍然填滿")
    void pageSizeStillFullyFilledWhenSomeItemsAreFiltered() {
        when(bookmarkService.findAllMyBookmarkedArticleIds(userId))
                .thenReturn(List.of(1L, 2L, 3L, 4L, 5L, 6L));
        when(articleFacade.filterReadableIds(List.of(1L, 2L, 3L, 4L, 5L, 6L), userId, false))
                .thenReturn(List.of(1L, 3L, 5L, 6L));
        when(articleQueryService.getArticleSummariesByIds(List.of(1L, 3L)))
                .thenReturn(summaries(2));

        PageResult<ArticleSummaryResponse> result = service.listMyBookmarks(userId, false, 1, 2);

        assertThat(result.getTotal()).isEqualTo(4L);
        assertThat(result.getPages()).isEqualTo(2);
        assertThat(result.getRecords()).hasSize(2);
    }

    @Test
    @DisplayName("第二頁取正確的切片")
    void secondPageTakesCorrectSlice() {
        when(bookmarkService.findAllMyBookmarkedArticleIds(userId))
                .thenReturn(List.of(1L, 2L, 3L, 4L));
        when(articleFacade.filterReadableIds(List.of(1L, 2L, 3L, 4L), userId, false))
                .thenReturn(List.of(1L, 2L, 3L, 4L));
        when(articleQueryService.getArticleSummariesByIds(List.of(3L, 4L)))
                .thenReturn(summaries(2));

        PageResult<ArticleSummaryResponse> result = service.listMyBookmarks(userId, false, 2, 2);

        assertThat(result.getRecords()).hasSize(2);
        verify(articleQueryService).getArticleSummariesByIds(List.of(3L, 4L));
    }

    @Test
    @DisplayName("無收藏時不呼叫下游")
    void whenNoBookmarks_doesNotCallDownstream() {
        when(bookmarkService.findAllMyBookmarkedArticleIds(userId)).thenReturn(List.of());

        PageResult<ArticleSummaryResponse> result = service.listMyBookmarks(userId, false, 1, 20);

        assertThat(result.getTotal()).isZero();
        assertThat(result.getRecords()).isEmpty();
        verify(articleFacade, never()).filterReadableIds(anyList(), any(), anyBoolean());
        verify(articleQueryService, never()).getArticleSummariesByIds(anyList());
    }

    @Test
    @DisplayName("超出範圍的頁回空清單，但 total 不變")
    void outOfRangePageReturnsEmptyListButTotalUnchanged() {
        when(bookmarkService.findAllMyBookmarkedArticleIds(userId)).thenReturn(List.of(1L, 2L));
        when(articleFacade.filterReadableIds(List.of(1L, 2L), userId, false))
                .thenReturn(List.of(1L, 2L));

        PageResult<ArticleSummaryResponse> result = service.listMyBookmarks(userId, false, 9, 20);

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getRecords()).isEmpty();
        verify(articleQueryService, never()).getArticleSummariesByIds(anyList());
    }

    @Test
    @DisplayName("admin 身分會傳遞給 facade")
    void adminFlagIsPassedToFacade() {
        when(bookmarkService.findAllMyBookmarkedArticleIds(userId)).thenReturn(List.of(1L));
        when(articleFacade.filterReadableIds(List.of(1L), userId, true)).thenReturn(List.of(1L));
        when(articleQueryService.getArticleSummariesByIds(List.of(1L))).thenReturn(summaries(1));

        service.listMyBookmarks(userId, true, 1, 20);

        verify(articleFacade).filterReadableIds(List.of(1L), userId, true);
    }

    @Test
    @DisplayName("page 乘 size 溢位 int 時不拋例外，且回空清單")
    void whenPageTimesSizeOverflowsInt_doesNotThrowAndReturnsEmptyList() {
        when(bookmarkService.findAllMyBookmarkedArticleIds(userId)).thenReturn(List.of(1L, 2L));
        when(articleFacade.filterReadableIds(List.of(1L, 2L), userId, false))
                .thenReturn(List.of(1L, 2L));

        // (page - 1) * size = 99_999_999 * 30 = 2_999_999_970，超過 Integer.MAX_VALUE，
        // 若以 int 相乘會溢位成負數，繞過範圍守衛直接進 subList 炸 IndexOutOfBoundsException。
        PageResult<ArticleSummaryResponse> result = service.listMyBookmarks(userId, false, 100_000_000, 30);

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getRecords()).isEmpty();
        verify(articleQueryService, never()).getArticleSummariesByIds(anyList());
    }
}
