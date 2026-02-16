package dowob.xyz.blog.module.tag.repository;

import dowob.xyz.blog.module.tag.model.Tag;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 標籤資料存取介面
 *
 * <p>
 * 基於 Spring Data JDBC 的標籤 Repository，
 * 提供依 Slug、名稱查詢及熱門標籤排行查詢等功能。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface TagRepository extends CrudRepository<Tag, UUID> {

    /**
     * 依 Slug 查詢標籤
     *
     * @param slug URL 友善標識
     * @return 標籤 Optional
     */
    Optional<Tag> findBySlug(String slug);

    /**
     * 依名稱查詢標籤
     *
     * @param name 標籤名稱
     * @return 標籤 Optional
     */
    Optional<Tag> findByName(String name);

    /**
     * 查詢使用次數排行前 20 的標籤
     *
     * @return 熱門標籤列表
     */
    List<Tag> findTop20ByOrderByUsageCountDesc();

    /**
     * 依名稱批量查詢標籤
     *
     * @param names 名稱陣列
     * @return 符合的標籤列表
     */
    @Query("SELECT * FROM tags WHERE name = ANY(:names)")
    List<Tag> findByNameIn(@Param("names") String[] names);
}
