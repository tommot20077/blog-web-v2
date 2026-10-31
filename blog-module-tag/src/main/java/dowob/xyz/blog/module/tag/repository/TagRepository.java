package dowob.xyz.blog.module.tag.repository;

import dowob.xyz.blog.module.tag.model.Tag;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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
     * 查詢全部標籤，依使用次數由多到少排序（無筆數上限）
     *
     * <p>供前端「標籤索引頁」標籤雲使用，回傳所有標籤及其文章數（usageCount）。</p>
     *
     * @return 依使用次數遞減排序的完整標籤列表
     */
    List<Tag> findAllByOrderByUsageCountDesc();

    /**
     * 依名稱批量查詢標籤
     *
     * @param names 名稱陣列
     * @return 符合的標籤列表
     */
    @Query("SELECT * FROM tags WHERE name = ANY(:names)")
    List<Tag> findByNameIn(@Param("names") String[] names);

    /**
     * 新增標籤；name 或 slug 任一已存在時不動作（RACE-08）。
     *
     * <p>{@code ON CONFLICT DO NOTHING} 不指定衝突目標，涵蓋 {@code tags_name_key} 與 {@code tags_slug_key}
     * 兩個 UNIQUE 約束：併發建立同名／同 slug 標籤時，後到者不會拋唯一鍵例外（也就不會讓外層交易進入失敗狀態），
     * 而是新增 0 列，由呼叫端重讀既有標籤。其餘欄位使用資料庫預設值（{@code usage_count = 0}）。</p>
     *
     * @param id        標籤 UUID
     * @param name      標籤名稱
     * @param slug      標籤 Slug
     * @param createdAt 建立時間
     * @return 實際新增的筆數（0 表示 name 或 slug 已存在）
     */
    @Modifying
    @Query("INSERT INTO tags (id, name, slug, created_at) VALUES (:id, :name, :slug, :createdAt) ON CONFLICT DO NOTHING")
    int insertIfAbsent(@Param("id") UUID id, @Param("name") String name, @Param("slug") String slug,
                       @Param("createdAt") LocalDateTime createdAt);
}
