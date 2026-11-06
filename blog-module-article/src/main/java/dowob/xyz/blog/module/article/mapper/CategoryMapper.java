package dowob.xyz.blog.module.article.mapper;

import dowob.xyz.blog.module.article.model.Category;
import dowob.xyz.blog.module.article.model.CategoryWithArticleId;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 分類 MyBatis Mapper
 *
 * <p>
 * 負責分類複雜查詢與 article_categories junction 表操作。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface CategoryMapper {

    /**
     * 查詢所有分類（依 sort_order 升冪排序）
     *
     * @return 分類列表
     */
    @Select("SELECT id, uuid, name, slug, description, sort_order FROM categories ORDER BY sort_order ASC, created_at ASC")
    List<Category> findAll();

    /**
     * 根據 slug 查詢分類
     *
     * @param slug 分類 slug
     * @return 分類（可能不存在）
     */
    @Select("SELECT id, uuid, name, slug, description, sort_order FROM categories WHERE slug = #{slug}")
    Category findBySlug(@Param("slug") String slug);

    /**
     * 根據文章 ID 查詢分類列表
     *
     * @param articleId 文章資料庫主鍵
     * @return 分類列表
     */
    @Select("SELECT c.id, c.uuid, c.name, c.slug, c.description, c.sort_order " +
            "FROM categories c " +
            "INNER JOIN article_categories ac ON c.id = ac.category_id " +
            "WHERE ac.article_id = #{articleId} " +
            "ORDER BY c.sort_order ASC")
    List<Category> findCategoriesByArticleId(@Param("articleId") Long articleId);

    /**
     * 批次查詢多篇文章的分類（解決 N+1 問題）
     *
     * @param articleIds 文章資料庫主鍵列表
     * @return 帶有 articleId 的分類列表
     */
    @Select("<script>" +
            "SELECT c.id, c.uuid, c.name, c.slug, c.description, c.sort_order, ac.article_id " +
            "FROM categories c " +
            "INNER JOIN article_categories ac ON c.id = ac.category_id " +
            "WHERE ac.article_id IN " +
            "<foreach item='id' collection='articleIds' open='(' separator=',' close=')'>#{id}</foreach>" +
            " ORDER BY c.sort_order ASC" +
            "</script>")
    List<CategoryWithArticleId> findCategoriesByArticleIds(@Param("articleIds") List<Long> articleIds);

    /**
     * 計算特定分類下的文章數量
     *
     * @param categoryId 分類資料庫主鍵
     * @return 文章數量
     */
    @Select("SELECT COUNT(*) FROM article_categories WHERE category_id = #{categoryId}")
    long countArticlesByCategoryId(@Param("categoryId") Long categoryId);

    /**
     * 新增文章-分類關聯
     *
     * @param articleId  文章資料庫主鍵
     * @param categoryId 分類資料庫主鍵
     */
    @Insert("INSERT INTO article_categories (article_id, category_id) VALUES (#{articleId}, #{categoryId})")
    void insertArticleCategory(@Param("articleId") Long articleId, @Param("categoryId") Long categoryId);

    /**
     * 刪除文章的所有分類關聯（更新前清空）
     *
     * @param articleId 文章資料庫主鍵
     */
    @Delete("DELETE FROM article_categories WHERE article_id = #{articleId}")
    void deleteArticleCategoriesByArticleId(@Param("articleId") Long articleId);

    /**
     * 查詢文章目前掛的分類主鍵（文章存檔時與新集合比對差集，PERF-27）
     *
     * @param articleId 文章資料庫主鍵
     * @return 分類主鍵列表（無分類為空）
     */
    @Select("SELECT category_id FROM article_categories WHERE article_id = #{articleId}")
    List<Long> findCategoryIdsByArticleId(@Param("articleId") Long articleId);

    /**
     * 批次新增文章-分類關聯（單一語句；PERF-27）。呼叫端須保證集合非空且不含重複。
     *
     * @param articleId   文章資料庫主鍵
     * @param categoryIds 分類主鍵，至少一筆
     */
    @Insert("<script>INSERT INTO article_categories (article_id, category_id) VALUES "
            + "<foreach item='cid' collection='categoryIds' separator=','>(#{articleId}, #{cid})</foreach></script>")
    void insertArticleCategories(@Param("articleId") Long articleId, @Param("categoryIds") List<Long> categoryIds);

    /**
     * 批次刪除文章與指定分類的關聯（單一語句；只刪被移除的分類，PERF-27）。呼叫端須保證集合非空。
     *
     * @param articleId   文章資料庫主鍵
     * @param categoryIds 要移除的分類主鍵，至少一筆
     */
    @Delete("<script>DELETE FROM article_categories WHERE article_id = #{articleId} AND category_id IN "
            + "<foreach item='cid' collection='categoryIds' open='(' separator=',' close=')'>#{cid}</foreach></script>")
    void deleteArticleCategories(@Param("articleId") Long articleId, @Param("categoryIds") List<Long> categoryIds);

    /**
     * 根據分類 slug 查詢分類 ID（用於篩選）
     *
     * @param slug 分類 slug
     * @return 分類 ID（找不到回傳 null）
     */
    @Select("SELECT id FROM categories WHERE slug = #{slug}")
    Long findIdBySlug(@Param("slug") String slug);
}
