package dowob.xyz.blog.module.comment.repository;

import dowob.xyz.blog.module.comment.model.CommentLike;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CommentLikeRepository extends CrudRepository<CommentLike, Long> {
    Optional<CommentLike> findByUserIdAndCommentId(Long userId, Long commentId);
    long countByCommentId(Long commentId);
    void deleteByUserIdAndCommentId(Long userId, Long commentId);
}
