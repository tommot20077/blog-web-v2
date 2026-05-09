package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.reading.mapper.ArticleLikeMapper;
import dowob.xyz.blog.module.reading.model.ArticleLike;
import dowob.xyz.blog.module.reading.repository.ArticleLikeRepository;
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
class ArticleLikeServiceTest {

    @Mock private ArticleLikeRepository likeRepo;
    @Mock private ArticleLikeMapper articleLikeMapper;
    @Mock private ArticleFacade articleFacade;
    @InjectMocks private ArticleLikeService service;

    private final Long userId = 1L;
    private final Long articleId = 100L;

    @Test
    void likeArticle_firstTime_createsRowAndIncrementsCount() {
        when(likeRepo.findByUserIdAndArticleId(userId, articleId)).thenReturn(Optional.empty());

        service.likeArticle(userId, articleId);

        verify(likeRepo, times(1)).save(any(ArticleLike.class));
        verify(articleFacade, times(1)).incrementLikeCount(articleId);
    }

    @Test
    void likeArticle_alreadyLiked_isIdempotentNoChange() {
        ArticleLike existing = new ArticleLike();
        existing.setUserId(userId);
        existing.setArticleId(articleId);
        when(likeRepo.findByUserIdAndArticleId(userId, articleId)).thenReturn(Optional.of(existing));

        service.likeArticle(userId, articleId);

        verify(likeRepo, never()).save(any());
        verify(articleFacade, never()).incrementLikeCount(any());
    }

    @Test
    void unlikeArticle_existing_deletesRowAndDecrementsCount() {
        // delete 影響 1 row → 應 decrement
        when(likeRepo.deleteByUserIdAndArticleId(userId, articleId)).thenReturn(1);

        service.unlikeArticle(userId, articleId);

        verify(likeRepo, times(1)).deleteByUserIdAndArticleId(userId, articleId);
        verify(articleFacade, times(1)).decrementLikeCount(articleId);
    }

    @Test
    void unlikeArticle_notLiked_isIdempotentNoChange() {
        // delete 影響 0 rows（已被別 tx 刪掉，或本來就沒按過）→ 不該 decrement
        when(likeRepo.deleteByUserIdAndArticleId(userId, articleId)).thenReturn(0);

        service.unlikeArticle(userId, articleId);

        verify(likeRepo, times(1)).deleteByUserIdAndArticleId(userId, articleId);
        verify(articleFacade, never()).decrementLikeCount(any());
    }

    @Test
    void isLiked_returnsTrueWhenLiked() {
        when(likeRepo.findByUserIdAndArticleId(userId, articleId))
                .thenReturn(Optional.of(new ArticleLike()));

        assertThat(service.isLiked(userId, articleId)).isTrue();
    }

    @Test
    void isLiked_returnsFalseWhenNotLiked() {
        when(likeRepo.findByUserIdAndArticleId(userId, articleId)).thenReturn(Optional.empty());

        assertThat(service.isLiked(userId, articleId)).isFalse();
    }

    @Test
    void batchIsLiked_returnsCorrectFlagsForEachId() {
        List<Long> articleIds = List.of(1L, 2L, 3L);
        when(articleLikeMapper.findLikedArticleIdsByUser(eq(userId), eq(articleIds)))
                .thenReturn(List.of(1L, 3L));

        Set<Long> liked = service.batchIsLiked(userId, articleIds);

        assertThat(liked).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void batchIsLiked_unauthenticated_returnsEmptySet() {
        Set<Long> liked = service.batchIsLiked(null, List.of(1L, 2L));

        assertThat(liked).isEmpty();
        verify(articleLikeMapper, never()).findLikedArticleIdsByUser(any(), any());
    }
}
