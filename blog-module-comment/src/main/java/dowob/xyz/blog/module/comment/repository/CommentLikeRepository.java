package dowob.xyz.blog.module.comment.repository;

import dowob.xyz.blog.module.comment.model.CommentLike;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommentLikeRepository extends CrudRepository<CommentLike, Long> {
    Optional<CommentLike> findByUserUuidAndCommentId(UUID userUuid, Long commentId);
    long countByCommentId(Long commentId);

    /**
     * 刪除指定使用者對指定留言的按讚記錄。
     *
     * @return 實際被刪除的 row 數（0 = 沒這筆，1 = 成功刪除）
     */
    int deleteByUserUuidAndCommentId(UUID userUuid, Long commentId);
}
