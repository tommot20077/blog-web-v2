package dowob.xyz.blog.module.comment.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.comment.exception.CommentErrorCode;
import dowob.xyz.blog.module.comment.mapper.CommentMapper;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.repository.CommentLikeRepository;
import dowob.xyz.blog.module.comment.repository.CommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 留言按讚 Service。
 *
 * <p>提供 idempotent like / unlike，並維護 comments.like_count 反正規化欄位。
 * 軟刪除留言不可被按讚（C0104）。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Service
@RequiredArgsConstructor
public class CommentLikeService {

    private final CommentLikeRepository likeRepo;
    private final CommentRepository commentRepo;
    private final CommentMapper commentMapper;

    /**
     * 按讚（idempotent，並發安全）。
     *
     * <p>以單一 {@code INSERT ... ON CONFLICT (user_uuid, comment_id) DO NOTHING} 寫入，依 affected rows 決定
     * 是否 {@code like_count + 1}：回傳 1 才加，0 代表先前（或並發中對方）已按過，直接視為成功。
     * 不再先 find 再 save、也不再 catch {@code DataIntegrityViolationException}——後者在 Postgres 會讓
     * 當前交易進入 aborted 狀態並被標成 rollback-only，雙擊落敗者最後得到 500（RACE-01）。</p>
     *
     * @param commentUuid 留言 UUID
     * @param userUuid    按讚者 UUID
     */
    @Transactional
    public void likeComment(UUID commentUuid, UUID userUuid) {
        Comment c = commentRepo.findByUuid(commentUuid)
                .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));
        if (c.getDeletedAt() != null) {
            throw new BusinessException(CommentErrorCode.COMMENT_DELETED);
        }
        int inserted = commentMapper.insertLikeIgnoreConflict(userUuid, c.getId());
        if (inserted == 1) {
            commentMapper.incrementLikeCount(c.getId());
        }
    }

    /**
     * 取消讚（idempotent）。
     *
     * <p>並發安全：依 delete affected rows 決定 decrement，避免 like_count 漂移。</p>
     *
     * @param commentUuid 留言 UUID
     * @param userUuid    取消讚的使用者 UUID
     */
    @Transactional
    public void unlikeComment(UUID commentUuid, UUID userUuid) {
        Comment c = commentRepo.findByUuid(commentUuid)
                .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));
        int affected = likeRepo.deleteByUserUuidAndCommentId(userUuid, c.getId());
        if (affected > 0) {
            commentMapper.decrementLikeCount(c.getId());
        }
    }
}
