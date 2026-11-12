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
 * @version 2.0
 */
@Repository
public interface UserHighlightRepository extends CrudRepository<UserHighlight, Long> {
    Optional<UserHighlight> findByUuid(UUID uuid);

    /**
     * 查詢某使用者在某篇文章的所有劃線，依建立時間由舊到新。
     *
     * @param userUuid    使用者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @return 劃線列表
     */
    List<UserHighlight> findByUserUuidAndArticleUuidOrderByCreatedAtAsc(UUID userUuid, UUID articleUuid);
}
