package dowob.xyz.blog.module.article.mapper;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.module.article.model.Article;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

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
     * 原子性增加瀏覽次數（+1）
     *
     * @param id 文章資料庫主鍵
     */
    @Update("UPDATE articles SET view_count = view_count + 1 WHERE id = #{id}")
    void incrementViewCount(@Param("id") Long id);

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
}
