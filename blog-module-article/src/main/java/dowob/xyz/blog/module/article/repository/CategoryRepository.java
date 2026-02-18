package dowob.xyz.blog.module.article.repository;

import dowob.xyz.blog.module.article.model.Category;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 分類 Repository（Spring Data JDBC，簡單 CRUD）
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public interface CategoryRepository extends CrudRepository<Category, Long> {

    Optional<Category> findByUuid(UUID uuid);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);
}
