package dowob.xyz.blog.module.comment.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.comment.exception.CommentErrorCode;
import dowob.xyz.blog.module.comment.mapper.CommentMapper;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.model.CommentLike;
import dowob.xyz.blog.module.comment.repository.CommentLikeRepository;
import dowob.xyz.blog.module.comment.repository.CommentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentLikeServiceTest {

    @Mock private CommentLikeRepository likeRepo;
    @Mock private CommentRepository commentRepo;
    @Mock private CommentMapper commentMapper;
    @InjectMocks private CommentLikeService service;

    private final Long userId = 10L;
    private final UUID commentUuid = UUID.randomUUID();
    private final Long commentId = 100L;

    @Test
    void likeComment_firstTime_createsRowAndIncrementsCount() {
        Comment c = new Comment();
        c.setId(commentId);
        c.setUuid(commentUuid);
        c.setDeletedAt(null);
        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));
        when(likeRepo.findByUserIdAndCommentId(userId, commentId)).thenReturn(Optional.empty());

        service.likeComment(commentUuid, userId);

        verify(likeRepo).save(any(CommentLike.class));
        verify(commentMapper).incrementLikeCount(commentId);
    }

    @Test
    void likeComment_alreadyLiked_isIdempotent() {
        Comment c = new Comment();
        c.setId(commentId);
        c.setUuid(commentUuid);
        c.setDeletedAt(null);
        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));
        when(likeRepo.findByUserIdAndCommentId(userId, commentId))
                .thenReturn(Optional.of(new CommentLike()));

        service.likeComment(commentUuid, userId);

        verify(likeRepo, never()).save(any());
        verify(commentMapper, never()).incrementLikeCount(any());
    }

    @Test
    void likeComment_softDeleted_throwsCommentDeleted() {
        Comment c = new Comment();
        c.setId(commentId);
        c.setUuid(commentUuid);
        c.setDeletedAt(LocalDateTime.now());
        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.likeComment(commentUuid, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommentErrorCode.COMMENT_DELETED.getMessage());
    }

    @Test
    void unlikeComment_existing_deletesAndDecrements() {
        Comment c = new Comment();
        c.setId(commentId);
        c.setUuid(commentUuid);
        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));
        when(likeRepo.findByUserIdAndCommentId(userId, commentId))
                .thenReturn(Optional.of(new CommentLike()));

        service.unlikeComment(commentUuid, userId);

        verify(likeRepo).deleteByUserIdAndCommentId(userId, commentId);
        verify(commentMapper).decrementLikeCount(commentId);
    }

    @Test
    void unlikeComment_notLiked_isIdempotent() {
        Comment c = new Comment();
        c.setId(commentId);
        c.setUuid(commentUuid);
        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));
        when(likeRepo.findByUserIdAndCommentId(userId, commentId)).thenReturn(Optional.empty());

        service.unlikeComment(commentUuid, userId);

        verify(likeRepo, never()).deleteByUserIdAndCommentId(any(), any());
        verify(commentMapper, never()).decrementLikeCount(any());
    }
}
