package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.module.reading.mapper.BookmarkMapper;
import dowob.xyz.blog.module.reading.model.UserBookmark;
import dowob.xyz.blog.module.reading.repository.UserBookmarkRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * {@link BookmarkService} 單元測試（跨模組識別一律為 UUID）。
 *
 * @author Yuan
 * @version 2.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookmarkService")
class BookmarkServiceTest {

    @Mock private UserBookmarkRepository repo;
    @Mock private BookmarkMapper mapper;
    @InjectMocks private BookmarkService service;

    private final UUID userUuid = UUID.randomUUID();
    private final UUID articleUuid = UUID.randomUUID();

    @Test
    @DisplayName("收藏：以單一 INSERT ... ON CONFLICT DO NOTHING 寫入，不先 find、不經 repository.save（RACE-04）")
    void bookmark_insertsWithOnConflictDoNothing() {
        when(mapper.insertIgnoreConflict(userUuid, articleUuid)).thenReturn(1);

        service.bookmark(userUuid, articleUuid);

        verify(mapper, times(1)).insertIgnoreConflict(userUuid, articleUuid);
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("重複收藏：ON CONFLICT 影響 0 列，視為成功（idempotent），不拋例外")
    void bookmark_alreadyBookmarked_isIdempotent() {
        when(mapper.insertIgnoreConflict(userUuid, articleUuid)).thenReturn(0);

        service.bookmark(userUuid, articleUuid);

        verify(mapper, times(1)).insertIgnoreConflict(userUuid, articleUuid);
    }

    @Test
    @DisplayName("取消收藏：單一 DELETE，不需先查")
    void unbookmark_issuesSingleDeleteWithoutLookup() {
        service.unbookmark(userUuid, articleUuid);

        verify(repo, times(1)).deleteByUserUuidAndArticleUuid(userUuid, articleUuid);
        verify(repo, never()).findByUserUuidAndArticleUuid(any(), any());
    }

    @Test
    @DisplayName("isBookmarked：有紀錄回 true")
    void isBookmarked_returnsTrueWhenBookmarked() {
        when(repo.findByUserUuidAndArticleUuid(userUuid, articleUuid))
                .thenReturn(Optional.of(new UserBookmark()));

        assertThat(service.isBookmarked(userUuid, articleUuid)).isTrue();
    }

    @Test
    @DisplayName("isBookmarked：無紀錄回 false")
    void isBookmarked_returnsFalseWhenNotBookmarked() {
        when(repo.findByUserUuidAndArticleUuid(userUuid, articleUuid)).thenReturn(Optional.empty());

        assertThat(service.isBookmarked(userUuid, articleUuid)).isFalse();
    }

    @Test
    @DisplayName("batchIsBookmarked：回傳 mapper 查到的 uuid 集合")
    void batchIsBookmarked_returnsCorrectUuidSet() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<UUID> articleUuids = List.of(a, b, c);
        when(mapper.findBookmarkedArticleUuidsByUser(eq(userUuid), eq(articleUuids)))
                .thenReturn(List.of(a, c));

        Set<UUID> result = service.batchIsBookmarked(userUuid, articleUuids);

        assertThat(result).containsExactlyInAnyOrder(a, c);
    }

    @Test
    @DisplayName("batchIsBookmarked：未登入回空集合且不查詢")
    void batchIsBookmarked_unauthenticated_returnsEmpty() {
        Set<UUID> result = service.batchIsBookmarked(null, List.of(UUID.randomUUID()));

        assertThat(result).isEmpty();
        verify(mapper, never()).findBookmarkedArticleUuidsByUser(any(), any());
    }

    @Test
    @DisplayName("findAllMyBookmarkedArticleUuids：原樣回傳 mapper 的順序（最新收藏優先）")
    void findAllMyBookmarkedArticleUuids_keepsMapperOrder() {
        UUID newest = UUID.randomUUID();
        UUID oldest = UUID.randomUUID();
        when(mapper.findAllMyBookmarkedArticleUuids(userUuid)).thenReturn(List.of(newest, oldest));

        assertThat(service.findAllMyBookmarkedArticleUuids(userUuid)).containsExactly(newest, oldest);
    }
}
