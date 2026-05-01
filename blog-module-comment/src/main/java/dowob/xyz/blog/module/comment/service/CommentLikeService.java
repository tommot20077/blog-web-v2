package dowob.xyz.blog.module.comment.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.comment.exception.CommentErrorCode;
import dowob.xyz.blog.module.comment.mapper.CommentMapper;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.model.CommentLike;
import dowob.xyz.blog.module.comment.repository.CommentLikeRepository;
import dowob.xyz.blog.module.comment.repository.CommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 留言按讚 Service。
 *
 * <p>提供 idempotent like / unlike，並維護 comments.like_count 反正規化欄位。
 * 軟刪除留言不可被按讚（C0104）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class CommentLikeService {

    private final CommentLikeRepository likeRepo;
    private final CommentRepository commentRepo;
    private final CommentMapper commentMapper;

    @Transactional
    public void likeComment(UUID commentUuid, Long userId) {
        Comment c = commentRepo.findByUuid(commentUuid)
                .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));
        if (c.getDeletedAt() != null) {
            throw new BusinessException(CommentErrorCode.COMMENT_DELETED);
        }
        if (likeRepo.findByUserIdAndCommentId(userId, c.getId()).isPresent()) {
            return;     // idempotent
        }

        CommentLike like = new CommentLike();
        like.setUserId(userId);
        like.setCommentId(c.getId());
        like.setCreatedAt(LocalDateTime.now());
        likeRepo.save(like);
        commentMapper.incrementLikeCount(c.getId());
    }

    @Transactional
    public void unlikeComment(UUID commentUuid, Long userId) {
        Comment c = commentRepo.findByUuid(commentUuid)
                .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));
        if (likeRepo.findByUserIdAndCommentId(userId, c.getId()).isEmpty()) {
            return;     // idempotent
        }

        likeRepo.deleteByUserIdAndCommentId(userId, c.getId());
        commentMapper.decrementLikeCount(c.getId());
    }
}
