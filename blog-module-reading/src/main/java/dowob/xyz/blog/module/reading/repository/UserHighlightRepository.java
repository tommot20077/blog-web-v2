package dowob.xyz.blog.module.reading.repository;

import dowob.xyz.blog.module.reading.model.UserHighlight;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Highlight Repository。
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public interface UserHighlightRepository extends CrudRepository<UserHighlight, Long> {
    Optional<UserHighlight> findByUuid(UUID uuid);
    List<UserHighlight> findByUserIdAndArticleIdOrderByCreatedAtAsc(Long userId, Long articleId);
}
