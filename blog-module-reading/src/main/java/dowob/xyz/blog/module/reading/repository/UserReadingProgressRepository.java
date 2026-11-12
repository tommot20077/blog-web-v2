package dowob.xyz.blog.module.reading.repository;

import dowob.xyz.blog.module.reading.model.UserReadingProgress;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 閱讀進度 Repository。
 *
 * @author Yuan
 * @version 2.0
 */
@Repository
public interface UserReadingProgressRepository extends CrudRepository<UserReadingProgress, Long> {

    /**
     * 查詢某使用者在某篇文章的閱讀進度。
     *
     * @param userUuid    使用者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @return 閱讀進度；無紀錄為 empty
     */
    Optional<UserReadingProgress> findByUserUuidAndArticleUuid(UUID userUuid, UUID articleUuid);

    /**
     * 批次查詢某使用者在多篇文章的閱讀進度。
     *
     * @param userUuid     使用者公開 UUID
     * @param articleUuids 文章公開 UUID 列表
     * @return 有紀錄的閱讀進度
     */
    List<UserReadingProgress> findByUserUuidAndArticleUuidIn(UUID userUuid, List<UUID> articleUuids);
}
