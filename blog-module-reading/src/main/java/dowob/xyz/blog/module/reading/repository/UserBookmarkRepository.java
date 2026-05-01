package dowob.xyz.blog.module.reading.repository;

import dowob.xyz.blog.module.reading.model.UserBookmark;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 文章收藏 Repository。
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public interface UserBookmarkRepository extends CrudRepository<UserBookmark, Long> {
    Optional<UserBookmark> findByUserIdAndArticleId(Long userId, Long articleId);
    void deleteByUserIdAndArticleId(Long userId, Long articleId);
}
