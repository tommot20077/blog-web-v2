package dowob.xyz.blog.module.comment.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.comment.exception.CommentErrorCode;
import dowob.xyz.blog.module.comment.mapper.CommentMapper;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.repository.CommentLikeRepository;
import dowob.xyz.blog.module.comment.repository.CommentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentLikeServiceTest {

    @Mock private CommentLikeRepository likeRepo;
    @Mock private CommentRepository commentRepo;
    @Mock private CommentMapper commentMapper;
    @InjectMocks private CommentLikeService service;

    private final UUID userUuid = UUID.randomUUID();
    private final UUID commentUuid = UUID.randomUUID();
    private final Long commentId = 100L;

    private Comment liveComment() {
        Comment c = new Comment();
        c.setId(commentId);
        c.setUuid(commentUuid);
        c.setDeletedAt(null);
        return c;
    }

    @Test
    @DisplayName("首次按讚：INSERT ... ON CONFLICT DO NOTHING 影響 1 列 → like_count +1")
    void likeComment_firstTime_insertsRowAndIncrementsCount() {
        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(liveComment()));
        when(commentMapper.insertLikeIgnoreConflict(userUuid, commentId)).thenReturn(1);

        service.likeComment(commentUuid, userUuid);

        verify(commentMapper).insertLikeIgnoreConflict(userUuid, commentId);
        verify(commentMapper).incrementLikeCount(commentId);
    }

    @Test
    @DisplayName("已按過讚（或並發落敗）：INSERT 影響 0 列 → 不加計數、不拋例外（idempotent）")
    void likeComment_alreadyLiked_isIdempotentAndDoesNotIncrement() {
        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(liveComment()));
        when(commentMapper.insertLikeIgnoreConflict(userUuid, commentId)).thenReturn(0);

        assertThatCode(() -> service.likeComment(commentUuid, userUuid)).doesNotThrowAnyException();

        verify(commentMapper, never()).incrementLikeCount(any());
    }

    @Test
    @DisplayName("RACE-01：按讚不再經 repository 的 find / save，也不靠 try-catch DataIntegrityViolationException")
    void likeComment_doesNotUseRepositoryFastPathOrSave() {
        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(liveComment()));
        when(commentMapper.insertLikeIgnoreConflict(userUuid, commentId)).thenReturn(1);

        service.likeComment(commentUuid, userUuid);

        verifyNoInteractions(likeRepo);
    }

    @Test
    void likeComment_softDeleted_throwsCommentDeleted() {
        Comment c = liveComment();
        c.setDeletedAt(LocalDateTime.now());
        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.likeComment(commentUuid, userUuid))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommentErrorCode.COMMENT_DELETED.getMessage());
        verify(commentMapper, never()).insertLikeIgnoreConflict(any(), any());
    }

    @Test
    void likeComment_commentNotFound_throwsCommentNotFound() {
        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.likeComment(commentUuid, userUuid))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommentErrorCode.COMMENT_NOT_FOUND.getMessage());
    }

    @Test
    void unlikeComment_existing_deletesAndDecrements() {
        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(liveComment()));
        // delete 影響 1 row → 應 decrement
        when(likeRepo.deleteByUserUuidAndCommentId(userUuid, commentId)).thenReturn(1);

        service.unlikeComment(commentUuid, userUuid);

        verify(likeRepo).deleteByUserUuidAndCommentId(userUuid, commentId);
        verify(commentMapper).decrementLikeCount(commentId);
    }

    @Test
    void unlikeComment_notLiked_isIdempotent() {
        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(liveComment()));
        // delete 影響 0 rows → 不該 decrement
        when(likeRepo.deleteByUserUuidAndCommentId(userUuid, commentId)).thenReturn(0);

        service.unlikeComment(commentUuid, userUuid);

        verify(likeRepo).deleteByUserUuidAndCommentId(userUuid, commentId);
        verify(commentMapper, never()).decrementLikeCount(any());
    }
}
