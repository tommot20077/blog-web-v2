package dowob.xyz.blog.module.reading.facade;

import dowob.xyz.blog.module.reading.service.ArticleLikeService;
import dowob.xyz.blog.module.reading.service.BookmarkService;
import dowob.xyz.blog.module.reading.service.ReadingProgressService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadingFacadeImplTest {

    @Mock
    private BookmarkService bookmarkService;

    @Mock
    private ReadingProgressService readingProgressService;

    @Mock
    private ArticleLikeService articleLikeService;

    @InjectMocks
    private ReadingFacadeImpl facade;

    @Test
    void batchIsLiked_delegatesToArticleLikeService() {
        when(articleLikeService.batchIsLiked(eq(1L), eq(List.of(10L, 20L))))
                .thenReturn(Set.of(10L));

        Set<Long> result = facade.batchIsLiked(1L, List.of(10L, 20L));

        assertThat(result).containsExactly(10L);
        verify(articleLikeService).batchIsLiked(1L, List.of(10L, 20L));
    }

    @Test
    void isLiked_delegatesToArticleLikeService() {
        when(articleLikeService.isLiked(1L, 10L)).thenReturn(true);

        assertThat(facade.isLiked(1L, 10L)).isTrue();
        verify(articleLikeService).isLiked(1L, 10L);
    }
}
