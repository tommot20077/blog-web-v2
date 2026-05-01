package dowob.xyz.blog.module.article.repository;

import dowob.xyz.blog.module.article.model.ArticleLike;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Article Like Repository
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public interface ArticleLikeRepository extends CrudRepository<ArticleLike, Long> {
    Optional<ArticleLike> findByUserIdAndArticleId(Long userId, Long articleId);
    void deleteByUserIdAndArticleId(Long userId, Long articleId);
}
