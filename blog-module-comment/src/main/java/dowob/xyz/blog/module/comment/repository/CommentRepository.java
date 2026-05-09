package dowob.xyz.blog.module.comment.repository;

import dowob.xyz.blog.module.comment.model.Comment;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Comment Repository
 *
 * <p>提供基本 CRUD；複雜 JOIN 查詢由 CommentMapper 負責。</p>
 *
 * @author Yuan
 */
@Repository
public interface CommentRepository extends CrudRepository<Comment, Long> {
    Optional<Comment> findByUuid(UUID uuid);
}
