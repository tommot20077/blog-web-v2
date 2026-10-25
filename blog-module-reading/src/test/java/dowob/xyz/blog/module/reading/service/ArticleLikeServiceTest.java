package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.reading.mapper.ArticleLikeMapper;
import dowob.xyz.blog.module.reading.model.ArticleLike;
import dowob.xyz.blog.module.reading.repository.ArticleLikeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleLikeService} 單元測試（跨模組識別一律為 UUID）。
 *
 * @author Yuan
 * @version 2.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleLikeService")
class ArticleLikeServiceTest {

    @Mock private ArticleLikeRepository likeRepo;
    @Mock private ArticleLikeMapper articleLikeMapper;
    @Mock private ArticleFacade articleFacade;
    @InjectMocks private ArticleLikeService service;

    private final UUID userUuid = UUID.randomUUID();
    private final UUID articleUuid = UUID.randomUUID();

    @Test
    @DisplayName("首次按讚：INSERT 影響 1 列 → like_count + 1")
    void likeArticle_firstTime_insertsRowAndIncrementsCount() {
        when(articleLikeMapper.insertIgnoreConflict(eq(userUuid), eq(articleUuid), any())).thenReturn(1);

        service.likeArticle(userUuid, articleUuid);

        verify(articleLikeMapper, times(1)).insertIgnoreConflict(eq(userUuid), eq(articleUuid), any());
        verify(articleFacade, times(1)).incrementLikeCount(articleUuid);
    }

    @Test
    @DisplayName("按讚：傳給 mapper 的 createdAt 是 service 以 JVM 時間產生的（不是 DB 的 CURRENT_TIMESTAMP）")
    void likeArticle_passesServiceGeneratedCreatedAtToMapper() {
        when(articleLikeMapper.insertIgnoreConflict(eq(userUuid), eq(articleUuid), any())).thenReturn(1);
        LocalDateTime before = LocalDateTime.now();

        service.likeArticle(userUuid, articleUuid);

        LocalDateTime after = LocalDateTime.now();
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(articleLikeMapper).insertIgnoreConflict(eq(userUuid), eq(articleUuid), captor.capture());
        assertThat(captor.getValue()).isNotNull().isBetween(before, after);
    }

    @Test
    @DisplayName("已按過讚：ON CONFLICT 影響 0 列 → 不增加 like_count（idempotent）")
    void likeArticle_alreadyLiked_isIdempotentNoChange() {
        when(articleLikeMapper.insertIgnoreConflict(eq(userUuid), eq(articleUuid), any())).thenReturn(0);

        service.likeArticle(userUuid, articleUuid);

        verify(articleFacade, never()).incrementLikeCount(any(UUID.class));
    }

    @Test
    @DisplayName("RACE-01：按讚不先 find、不經 repository.save，也不依賴 catch DataIntegrityViolationException")
    void likeArticle_doesNotUseFindThenSavePattern() {
        when(articleLikeMapper.insertIgnoreConflict(eq(userUuid), eq(articleUuid), any())).thenReturn(1);

        service.likeArticle(userUuid, articleUuid);

        verifyNoInteractions(likeRepo);
    }

    @Test
    @DisplayName("取消讚：delete 影響 1 列 → like_count - 1")
    void unlikeArticle_existing_deletesRowAndDecrementsCount() {
        when(likeRepo.deleteByUserUuidAndArticleUuid(userUuid, articleUuid)).thenReturn(1);

        service.unlikeArticle(userUuid, articleUuid);

        verify(likeRepo, times(1)).deleteByUserUuidAndArticleUuid(userUuid, articleUuid);
        verify(articleFacade, times(1)).decrementLikeCount(articleUuid);
    }

    @Test
    @DisplayName("取消讚：delete 影響 0 列（沒按過或已被他人刪除）→ 不 decrement")
    void unlikeArticle_notLiked_isIdempotentNoChange() {
        when(likeRepo.deleteByUserUuidAndArticleUuid(userUuid, articleUuid)).thenReturn(0);

        service.unlikeArticle(userUuid, articleUuid);

        verify(likeRepo, times(1)).deleteByUserUuidAndArticleUuid(userUuid, articleUuid);
        verify(articleFacade, never()).decrementLikeCount(any(UUID.class));
    }

    @Test
    @DisplayName("isLiked：有紀錄回 true")
    void isLiked_returnsTrueWhenLiked() {
        when(likeRepo.findByUserUuidAndArticleUuid(userUuid, articleUuid))
                .thenReturn(Optional.of(new ArticleLike()));

        assertThat(service.isLiked(userUuid, articleUuid)).isTrue();
    }

    @Test
    @DisplayName("isLiked：無紀錄回 false")
    void isLiked_returnsFalseWhenNotLiked() {
        when(likeRepo.findByUserUuidAndArticleUuid(userUuid, articleUuid)).thenReturn(Optional.empty());

        assertThat(service.isLiked(userUuid, articleUuid)).isFalse();
    }

    @Test
    @DisplayName("batchIsLiked：回傳 mapper 查到的 uuid 集合")
    void batchIsLiked_returnsCorrectSetForEachUuid() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<UUID> articleUuids = List.of(a, b, c);
        when(articleLikeMapper.findLikedArticleUuidsByUser(eq(userUuid), eq(articleUuids)))
                .thenReturn(List.of(a, c));

        Set<UUID> liked = service.batchIsLiked(userUuid, articleUuids);

        assertThat(liked).containsExactlyInAnyOrder(a, c);
    }

    @Test
    @DisplayName("batchIsLiked：未登入（userUuid 為 null）回空集合且不查詢")
    void batchIsLiked_unauthenticated_returnsEmptySet() {
        Set<UUID> liked = service.batchIsLiked(null, List.of(UUID.randomUUID()));

        assertThat(liked).isEmpty();
        verify(articleLikeMapper, never()).findLikedArticleUuidsByUser(any(), any());
    }
}
