package dowob.xyz.blog.module.reading.repository;

import dowob.xyz.blog.module.reading.model.ArticleLike;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Article Like Repository（reading 模組版）
 *
 * <p>T3 期間因 article 模組有同名 interface，用 bean name 區分：
 * 此 bean 名稱為 {@code readingArticleLikeRepository}，
 * article 模組舊版為 {@code articleLikeRepository}（T5 後刪除）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Repository("readingArticleLikeRepository")
public interface ArticleLikeRepository extends CrudRepository<ArticleLike, Long> {
    Optional<ArticleLike> findByUserIdAndArticleId(Long userId, Long articleId);

    /**
     * 刪除指定使用者對指定文章的按讚記錄。
     *
     * @return 實際被刪除的 row 數（0 = 沒這筆，1 = 成功刪除）
     */
    int deleteByUserIdAndArticleId(Long userId, Long articleId);
}
