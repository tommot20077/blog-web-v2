package dowob.xyz.blog.module.article.mapper;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.config.UUIDTypeHandler;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.module.article.model.Article;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/**
 * 文章 MyBatis Mapper
 *
 * <p>
 * 負責複雜查詢，例如分頁列表、篩選與原子性更新。
 * 簡單 CRUD 仍使用 ArticleRepository。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface ArticleMapper {

    /**
     * 分頁查詢已發布文章（公開列表）
     *
     * @param offset 偏移量
     * @param size   每頁筆數
     * @return 文章列表
     */
    @Select("SELECT * FROM articles WHERE status = 'PUBLISHED' ORDER BY created_at DESC LIMIT #{size} OFFSET #{offset}")
    List<Article> findPublishedPage(@Param("offset") long offset, @Param("size") int size);

    /**
     * 計算已發布文章總筆數
     *
     * @return 總筆數
     */
    @Select("SELECT COUNT(*) FROM articles WHERE status = 'PUBLISHED'")
    long countPublished();

    /**
     * 根據作者 ID 與狀態篩選文章
     *
     * @param authorId 作者資料庫主鍵
     * @param status   文章狀態
     * @param offset   偏移量
     * @param size     每頁筆數
     * @return 文章列表
     */
    @Select("SELECT * FROM articles WHERE author_id = #{authorId} AND status = #{status} ORDER BY created_at DESC LIMIT #{size} OFFSET #{offset}")
    List<Article> findByAuthorIdAndStatus(
            @Param("authorId") Long authorId,
            @Param("status") ArticleStatus status,
            @Param("offset") long offset,
            @Param("size") int size);

    /**
     * 根據作者 ID 分頁查詢（不篩選狀態）
     *
     * @param authorId 作者資料庫主鍵
     * @param offset   偏移量
     * @param size     每頁筆數
     * @return 文章列表
     */
    @Select("SELECT * FROM articles WHERE author_id = #{authorId} ORDER BY created_at DESC LIMIT #{size} OFFSET #{offset}")
    List<Article> findByAuthorIdPaged(
            @Param("authorId") Long authorId,
            @Param("offset") long offset,
            @Param("size") int size);

    /**
     * 計算作者的文章總筆數
     *
     * @param authorId 作者資料庫主鍵
     * @return 總筆數
     */
    @Select("SELECT COUNT(*) FROM articles WHERE author_id = #{authorId}")
    long countByAuthorId(@Param("authorId") Long authorId);

    /**
     * 分頁查詢待審文章（按提交時間升冪，供管理員審核）
     *
     * @param offset 偏移量
     * @param size   每頁筆數
     * @return 文章列表
     */
    @Select("SELECT * FROM articles WHERE status = 'PENDING_REVIEW' ORDER BY created_at ASC LIMIT #{size} OFFSET #{offset}")
    List<Article> findPendingReviewPage(@Param("offset") long offset, @Param("size") int size);

    /**
     * 計算待審文章總筆數
     *
     * @return 總筆數
     */
    @Select("SELECT COUNT(*) FROM articles WHERE status = 'PENDING_REVIEW'")
    long countPendingReview();

    /**
     * 查詢文章的所有標籤
     *
     * <p>
     * 透過 article_tags 關聯表查詢對應的 tags 資料，
     * 供建立 {@link dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent} 使用。
     * 使用 {@link ConstructorArgs} 明確指定 Record 建構子參數對應，
     * 因 Java Record 無無參建構子，MyBatis 需此提示才能正確映射。
     * V9 migration 後 article_tags.article_id 改為 UUID 型別，
     * 故改以文章公開 UUID 查詢並加上 ::uuid 強制轉型。
     * </p>
     *
     * @param articleUuid 文章公開 UUID
     * @return 標籤資訊列表
     */
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, typeHandler = UUIDTypeHandler.class, name = "id"),
            @Arg(column = "name", javaType = String.class, name = "name"),
            @Arg(column = "slug", javaType = String.class, name = "slug")
    })
    @Select("SELECT t.id, t.name, t.slug FROM tags t " +
            "INNER JOIN article_tags art ON t.id = art.tag_id " +
            "WHERE art.article_id = #{articleUuid}::uuid")
    List<TagInfo> findTagsByArticleUuid(@Param("articleUuid") UUID articleUuid);

    /**
     * 查詢所有已發布文章（供全量重建 Elasticsearch 索引使用）
     *
     * @return 所有已發布文章列表
     */
    @Select("SELECT * FROM articles WHERE status = 'PUBLISHED' ORDER BY published_at DESC")
    List<Article> findAllPublished();

    /**
     * 根據文章公開 UUID 查詢 DB 中儲存的瀏覽計數
     *
     * @param uuid 文章公開 UUID
     * @return 瀏覽計數，若文章不存在則回傳 null
     */
    @Select("SELECT view_count FROM articles WHERE uuid = #{uuid}::uuid")
    Long findViewCountByUuid(@Param("uuid") UUID uuid);

    /**
     * 以批次增量更新文章瀏覽計數（原子性加法）
     *
     * @param uuid  文章公開 UUID
     * @param delta 要增加的數量
     */
    @Update("UPDATE articles SET view_count = view_count + #{delta} WHERE uuid = #{uuid}::uuid")
    void incrementViewCountBatch(@Param("uuid") UUID uuid, @Param("delta") long delta);

    /**
     * 根據分類 slug 分頁查詢已發布文章
     *
     * @param categorySlug 分類 slug
     * @param offset       偏移量
     * @param size         每頁筆數
     * @return 文章列表
     */
    @Select("SELECT a.* FROM articles a " +
            "INNER JOIN article_categories ac ON a.id = ac.article_id " +
            "INNER JOIN categories c ON ac.category_id = c.id " +
            "WHERE a.status = 'PUBLISHED' AND c.slug = #{categorySlug} " +
            "ORDER BY a.created_at DESC LIMIT #{size} OFFSET #{offset}")
    List<Article> findPublishedPageByCategorySlug(
            @Param("categorySlug") String categorySlug,
            @Param("offset") long offset,
            @Param("size") int size);

    /**
     * 根據分類 slug 計算已發布文章總筆數
     *
     * @param categorySlug 分類 slug
     * @return 總筆數
     */
    @Select("SELECT COUNT(DISTINCT a.id) FROM articles a " +
            "INNER JOIN article_categories ac ON a.id = ac.article_id " +
            "INNER JOIN categories c ON ac.category_id = c.id " +
            "WHERE a.status = 'PUBLISHED' AND c.slug = #{categorySlug}")
    long countPublishedByCategorySlug(@Param("categorySlug") String categorySlug);
}
