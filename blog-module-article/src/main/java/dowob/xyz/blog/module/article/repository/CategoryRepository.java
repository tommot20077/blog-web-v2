package dowob.xyz.blog.module.article.repository;

import dowob.xyz.blog.module.article.model.Category;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
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

    /**
     * 依 UUID 批次查詢分類（文章存檔時一次讀出，取代逐個 findByUuid，PERF-27）。
     *
     * @param uuids 分類 UUID
     * @return 存在的分類（查無者不在結果中）
     */
    List<Category> findAllByUuidIn(Collection<UUID> uuids);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);
}
