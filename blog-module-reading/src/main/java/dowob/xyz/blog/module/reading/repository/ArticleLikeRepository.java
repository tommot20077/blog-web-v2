package dowob.xyz.blog.module.reading.repository;

import dowob.xyz.blog.module.reading.model.ArticleLike;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Article Like Repository（reading 模組）。跨模組識別一律為 UUID。
 *
 * @author Yuan
 * @version 2.0
 */
@Repository
public interface ArticleLikeRepository extends CrudRepository<ArticleLike, Long> {

    /**
     * 查詢指定使用者對指定文章的按讚紀錄。
     *
     * @param userUuid    使用者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @return 按讚紀錄；未按讚為 empty
     */
    Optional<ArticleLike> findByUserUuidAndArticleUuid(UUID userUuid, UUID articleUuid);

    /**
     * 刪除指定使用者對指定文章的按讚記錄。
     *
     * @param userUuid    使用者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @return 實際被刪除的 row 數（0 = 沒這筆，1 = 成功刪除）
     */
    int deleteByUserUuidAndArticleUuid(UUID userUuid, UUID articleUuid);
}
