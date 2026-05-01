package dowob.xyz.blog.module.reading.repository;

import dowob.xyz.blog.module.reading.model.UserReadingProgress;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 閱讀進度 Repository。
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public interface UserReadingProgressRepository extends CrudRepository<UserReadingProgress, Long> {
    Optional<UserReadingProgress> findByUserIdAndArticleId(Long userId, Long articleId);
    List<UserReadingProgress> findByUserIdAndArticleIdIn(Long userId, List<Long> articleIds);
}
