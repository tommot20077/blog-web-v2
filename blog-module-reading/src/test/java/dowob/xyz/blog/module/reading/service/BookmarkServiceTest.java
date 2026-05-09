package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.module.reading.mapper.BookmarkMapper;
import dowob.xyz.blog.module.reading.model.UserBookmark;
import dowob.xyz.blog.module.reading.repository.UserBookmarkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookmarkServiceTest {

    @Mock private UserBookmarkRepository repo;
    @Mock private BookmarkMapper mapper;
    @InjectMocks private BookmarkService service;

    private final Long userId = 1L;
    private final Long articleId = 100L;

    @Test
    void bookmark_firstTime_createsRow() {
        when(repo.findByUserIdAndArticleId(userId, articleId)).thenReturn(Optional.empty());

        service.bookmark(userId, articleId);

        verify(repo, times(1)).save(any(UserBookmark.class));
    }

    @Test
    void bookmark_alreadyBookmarked_isIdempotent() {
        when(repo.findByUserIdAndArticleId(userId, articleId))
                .thenReturn(Optional.of(new UserBookmark()));

        service.bookmark(userId, articleId);

        verify(repo, never()).save(any());
    }

    @Test
    void unbookmark_existing_deletesRow() {
        when(repo.findByUserIdAndArticleId(userId, articleId))
                .thenReturn(Optional.of(new UserBookmark()));

        service.unbookmark(userId, articleId);

        verify(repo, times(1)).deleteByUserIdAndArticleId(userId, articleId);
    }

    @Test
    void unbookmark_notBookmarked_isIdempotent() {
        when(repo.findByUserIdAndArticleId(userId, articleId)).thenReturn(Optional.empty());

        service.unbookmark(userId, articleId);

        verify(repo, never()).deleteByUserIdAndArticleId(any(), any());
    }

    @Test
    void isBookmarked_returnsTrueWhenBookmarked() {
        when(repo.findByUserIdAndArticleId(userId, articleId))
                .thenReturn(Optional.of(new UserBookmark()));

        assertThat(service.isBookmarked(userId, articleId)).isTrue();
    }

    @Test
    void batchIsBookmarked_returnsCorrectIdSet() {
        List<Long> articleIds = List.of(1L, 2L, 3L);
        when(mapper.findBookmarkedArticleIdsByUser(eq(userId), eq(articleIds)))
                .thenReturn(List.of(1L, 3L));

        Set<Long> result = service.batchIsBookmarked(userId, articleIds);

        assertThat(result).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void batchIsBookmarked_unauthenticated_returnsEmpty() {
        Set<Long> result = service.batchIsBookmarked(null, List.of(1L, 2L));

        assertThat(result).isEmpty();
        verify(mapper, never()).findBookmarkedArticleIdsByUser(any(), any());
    }
}
