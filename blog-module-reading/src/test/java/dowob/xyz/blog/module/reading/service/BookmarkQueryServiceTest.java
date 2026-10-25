package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BookmarkQueryService} 單元測試：收藏 uuid 清單 → 可見性過濾 → 分頁 → 摘要組裝，
 * 全程只經 {@link ArticleFacade}，不依賴 article 模組的任何類別。
 *
 * @author Yuan
 * @version 2.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookmarkQueryService")
class BookmarkQueryServiceTest {

    @Mock private BookmarkService bookmarkService;
    @Mock private ArticleFacade articleFacade;
    @InjectMocks private BookmarkQueryService service;

    private final UUID userUuid = UUID.randomUUID();
    private final UUID u1 = UUID.randomUUID();
    private final UUID u2 = UUID.randomUUID();
    private final UUID u3 = UUID.randomUUID();
    private final UUID u4 = UUID.randomUUID();
    private final UUID u5 = UUID.randomUUID();
    private final UUID u6 = UUID.randomUUID();

    private List<ArticleSummaryResponse> summaries(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> ArticleSummaryResponse.builder().build())
                .toList();
    }

    @Test
    @DisplayName("total 為過濾後的可見數，而非收藏列數")
    void totalIsFilteredVisibleCountNotBookmarkRowCount() {
        when(bookmarkService.findAllMyBookmarkedArticleUuids(userUuid)).thenReturn(List.of(u1, u2, u3, u4, u5));
        when(articleFacade.filterReadableUuids(List.of(u1, u2, u3, u4, u5), userUuid, false))
                .thenReturn(List.of(u1, u3));
        when(articleFacade.getSummariesByUuids(List.of(u1, u3))).thenReturn(summaries(2));

        PageResult<ArticleSummaryResponse> result = service.listMyBookmarks(userUuid, false, 1, 20);

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getPages()).isEqualTo(1);
        assertThat(result.getRecords()).hasSize(2);
    }

    @Test
    @DisplayName("每頁筆數在有被過濾項時仍然填滿")
    void pageSizeStillFullyFilledWhenSomeItemsAreFiltered() {
        when(bookmarkService.findAllMyBookmarkedArticleUuids(userUuid))
                .thenReturn(List.of(u1, u2, u3, u4, u5, u6));
        when(articleFacade.filterReadableUuids(List.of(u1, u2, u3, u4, u5, u6), userUuid, false))
                .thenReturn(List.of(u1, u3, u5, u6));
        when(articleFacade.getSummariesByUuids(List.of(u1, u3))).thenReturn(summaries(2));

        PageResult<ArticleSummaryResponse> result = service.listMyBookmarks(userUuid, false, 1, 2);

        assertThat(result.getTotal()).isEqualTo(4L);
        assertThat(result.getPages()).isEqualTo(2);
        assertThat(result.getRecords()).hasSize(2);
    }

    @Test
    @DisplayName("第二頁取正確的切片")
    void secondPageTakesCorrectSlice() {
        when(bookmarkService.findAllMyBookmarkedArticleUuids(userUuid)).thenReturn(List.of(u1, u2, u3, u4));
        when(articleFacade.filterReadableUuids(List.of(u1, u2, u3, u4), userUuid, false))
                .thenReturn(List.of(u1, u2, u3, u4));
        when(articleFacade.getSummariesByUuids(List.of(u3, u4))).thenReturn(summaries(2));

        PageResult<ArticleSummaryResponse> result = service.listMyBookmarks(userUuid, false, 2, 2);

        assertThat(result.getRecords()).hasSize(2);
        verify(articleFacade).getSummariesByUuids(List.of(u3, u4));
    }

    @Test
    @DisplayName("無收藏時不呼叫下游")
    void whenNoBookmarks_doesNotCallDownstream() {
        when(bookmarkService.findAllMyBookmarkedArticleUuids(userUuid)).thenReturn(List.of());

        PageResult<ArticleSummaryResponse> result = service.listMyBookmarks(userUuid, false, 1, 20);

        assertThat(result.getTotal()).isZero();
        assertThat(result.getRecords()).isEmpty();
        verify(articleFacade, never()).filterReadableUuids(anyList(), any(), anyBoolean());
        verify(articleFacade, never()).getSummariesByUuids(anyList());
    }

    @Test
    @DisplayName("超出範圍的頁回空清單，但 total 不變")
    void outOfRangePageReturnsEmptyListButTotalUnchanged() {
        when(bookmarkService.findAllMyBookmarkedArticleUuids(userUuid)).thenReturn(List.of(u1, u2));
        when(articleFacade.filterReadableUuids(List.of(u1, u2), userUuid, false)).thenReturn(List.of(u1, u2));

        PageResult<ArticleSummaryResponse> result = service.listMyBookmarks(userUuid, false, 9, 20);

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getRecords()).isEmpty();
        verify(articleFacade, never()).getSummariesByUuids(anyList());
    }

    @Test
    @DisplayName("admin 身分會傳遞給 facade")
    void adminFlagIsPassedToFacade() {
        when(bookmarkService.findAllMyBookmarkedArticleUuids(userUuid)).thenReturn(List.of(u1));
        when(articleFacade.filterReadableUuids(List.of(u1), userUuid, true)).thenReturn(List.of(u1));
        when(articleFacade.getSummariesByUuids(List.of(u1))).thenReturn(summaries(1));

        service.listMyBookmarks(userUuid, true, 1, 20);

        verify(articleFacade).filterReadableUuids(List.of(u1), userUuid, true);
    }

    @Test
    @DisplayName("page 乘 size 溢位 int 時不拋例外，且回空清單")
    void whenPageTimesSizeOverflowsInt_doesNotThrowAndReturnsEmptyList() {
        when(bookmarkService.findAllMyBookmarkedArticleUuids(userUuid)).thenReturn(List.of(u1, u2));
        when(articleFacade.filterReadableUuids(List.of(u1, u2), userUuid, false)).thenReturn(List.of(u1, u2));

        // (page - 1) * size = 99_999_999 * 30 = 2_999_999_970，超過 Integer.MAX_VALUE，
        // 若以 int 相乘會溢位成負數，繞過範圍守衛直接進 subList 炸 IndexOutOfBoundsException。
        PageResult<ArticleSummaryResponse> result = service.listMyBookmarks(userUuid, false, 100_000_000, 30);

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getRecords()).isEmpty();
        verify(articleFacade, never()).getSummariesByUuids(anyList());
    }
}
