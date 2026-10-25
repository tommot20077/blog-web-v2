package dowob.xyz.blog.module.reading.facade;

import dowob.xyz.blog.module.reading.model.dto.response.ProgressResponse;
import dowob.xyz.blog.module.reading.service.ArticleLikeService;
import dowob.xyz.blog.module.reading.service.BookmarkService;
import dowob.xyz.blog.module.reading.service.ReadingProgressService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReadingFacadeImpl} 單元測試：按讚、收藏與閱讀進度的六個查詢方法以 UUID 委派給對應 service。
 *
 * @author Yuan
 * @version 3.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReadingFacadeImpl")
class ReadingFacadeImplTest {

    @Mock
    private BookmarkService bookmarkService;

    @Mock
    private ReadingProgressService readingProgressService;

    @Mock
    private ArticleLikeService articleLikeService;

    @InjectMocks
    private ReadingFacadeImpl facade;

    private final UUID userUuid = UUID.randomUUID();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    @DisplayName("batchIsLiked 委派給 ArticleLikeService")
    void batchIsLiked_delegatesToArticleLikeService() {
        when(articleLikeService.batchIsLiked(eq(userUuid), eq(List.of(a, b)))).thenReturn(Set.of(a));

        Set<UUID> result = facade.batchIsLiked(userUuid, List.of(a, b));

        assertThat(result).containsExactly(a);
        verify(articleLikeService).batchIsLiked(userUuid, List.of(a, b));
    }

    @Test
    @DisplayName("isLiked 委派給 ArticleLikeService")
    void isLiked_delegatesToArticleLikeService() {
        when(articleLikeService.isLiked(userUuid, a)).thenReturn(true);

        assertThat(facade.isLiked(userUuid, a)).isTrue();
        verify(articleLikeService).isLiked(userUuid, a);
    }

    @Test
    @DisplayName("batchIsBookmarked 委派給 BookmarkService")
    void batchIsBookmarked_delegatesToBookmarkService() {
        when(bookmarkService.batchIsBookmarked(eq(userUuid), eq(List.of(a, b)))).thenReturn(Set.of(b));

        Set<UUID> result = facade.batchIsBookmarked(userUuid, List.of(a, b));

        assertThat(result).containsExactly(b);
        verify(bookmarkService).batchIsBookmarked(userUuid, List.of(a, b));
    }

    @Test
    @DisplayName("isBookmarked 委派給 BookmarkService")
    void isBookmarked_delegatesToBookmarkService() {
        when(bookmarkService.isBookmarked(userUuid, a)).thenReturn(true);

        assertThat(facade.isBookmarked(userUuid, a)).isTrue();
        verify(bookmarkService).isBookmarked(userUuid, a);
    }

    @Test
    @DisplayName("batchGetProgress 以 UUID 委派給 ReadingProgressService")
    void batchGetProgress_delegatesToReadingProgressServiceWithUuids() {
        Map<UUID, BigDecimal> progress = Map.of(a, new BigDecimal("0.40"));
        when(readingProgressService.batchGetProgress(userUuid, List.of(a, b))).thenReturn(progress);

        Map<UUID, BigDecimal> result = facade.batchGetProgress(userUuid, List.of(a, b));

        assertThat(result).isEqualTo(progress);
        verify(readingProgressService).batchGetProgress(userUuid, List.of(a, b));
    }

    @Test
    @DisplayName("getProgress：有紀錄回傳進度值")
    void getProgress_present_returnsProgressValue() {
        ProgressResponse resp = new ProgressResponse();
        resp.setProgress(new BigDecimal("0.75"));
        when(readingProgressService.get(userUuid, a)).thenReturn(Optional.of(resp));

        assertThat(facade.getProgress(userUuid, a)).isEqualByComparingTo("0.75");
    }

    @Test
    @DisplayName("getProgress：無紀錄回傳 null")
    void getProgress_absent_returnsNull() {
        when(readingProgressService.get(userUuid, a)).thenReturn(Optional.empty());

        assertThat(facade.getProgress(userUuid, a)).isNull();
    }
}
