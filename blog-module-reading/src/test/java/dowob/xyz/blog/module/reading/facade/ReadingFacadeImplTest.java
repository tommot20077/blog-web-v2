package dowob.xyz.blog.module.reading.facade;

import dowob.xyz.blog.module.reading.service.ArticleLikeService;
import dowob.xyz.blog.module.reading.service.BookmarkService;
import dowob.xyz.blog.module.reading.service.ReadingProgressService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReadingFacadeImpl} 單元測試：按讚／收藏的四個查詢方法以 UUID 委派給對應 service。
 *
 * @author Yuan
 * @version 2.0
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
}
