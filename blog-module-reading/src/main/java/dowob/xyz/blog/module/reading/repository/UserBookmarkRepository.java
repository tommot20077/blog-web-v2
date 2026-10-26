package dowob.xyz.blog.module.reading.repository;

import dowob.xyz.blog.module.reading.model.UserBookmark;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 文章收藏 Repository。跨模組識別一律為 UUID。
 *
 * @author Yuan
 * @version 2.0
 */
@Repository
public interface UserBookmarkRepository extends CrudRepository<UserBookmark, Long> {

    /**
     * 查詢指定使用者對指定文章的收藏紀錄。
     *
     * @param userUuid    使用者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @return 收藏紀錄；未收藏為 empty
     */
    Optional<UserBookmark> findByUserUuidAndArticleUuid(UUID userUuid, UUID articleUuid);

    /**
     * 取消收藏：單一 DELETE，不需先查。
     *
     * @param userUuid    使用者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @return 實際被刪除的 row 數（0 = 本來就沒收藏，1 = 成功刪除）
     */
    int deleteByUserUuidAndArticleUuid(UUID userUuid, UUID articleUuid);
}
