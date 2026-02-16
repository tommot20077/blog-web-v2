package dowob.xyz.blog.module.tag.repository;

import dowob.xyz.blog.module.tag.model.ArticleTag;
import dowob.xyz.blog.module.tag.model.Tag;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * 文章標籤關聯資料存取介面
 *
 * <p>
 * 基於 Spring Data JDBC，使用 {@code @Query} 方法操作 {@code article_tags} 中間表，
 * 提供文章-標籤關聯的查詢、新增與刪除功能。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface ArticleTagRepository extends Repository<ArticleTag, UUID> {

    /**
     * 依文章 ID 查詢其所有標籤
     *
     * @param articleId 文章 ID
     * @return 標籤列表
     */
    @Query("SELECT t.* FROM tags t JOIN article_tags at2 ON t.id = at2.tag_id WHERE at2.article_id = :articleId")
    List<Tag> findTagsByArticleId(@Param("articleId") UUID articleId);

    /**
     * 刪除指定文章與標籤的關聯
     *
     * @param articleId 文章 ID
     * @param tagId     標籤 ID
     */
    @Modifying
    @Query("DELETE FROM article_tags WHERE article_id = :articleId AND tag_id = :tagId")
    void deleteByArticleIdAndTagId(@Param("articleId") UUID articleId, @Param("tagId") UUID tagId);

    /**
     * 新增文章與標籤的關聯
     *
     * @param articleId 文章 ID
     * @param tagId     標籤 ID
     */
    @Modifying
    @Query("INSERT INTO article_tags (article_id, tag_id) VALUES (:articleId, :tagId)")
    void save(@Param("articleId") UUID articleId, @Param("tagId") UUID tagId);
}
