package dowob.xyz.blog.module.series.mapper;

import dowob.xyz.blog.module.series.model.SeriesWithAuthor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Series MyBatis Mapper。
 *
 * <p>負責複雜 JOIN（series + users）、反正規化計數 update、以及 nav 查詢。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface SeriesMapper {

    /** 列表（公開）：只列 article_count > 0 的 series，最新優先 */
    @Select("""
            SELECT s.id, s.uuid, s.title, s.slug, s.description, s.cover_image_url,
                   s.author_id, s.article_count, s.created_at, s.updated_at,
                   u.uuid AS author_uuid, u.nickname AS author_nickname, u.avatar_url AS author_avatar_url
              FROM series s LEFT JOIN users u ON s.author_id = u.id
             WHERE s.article_count > 0
             ORDER BY s.created_at DESC
             LIMIT #{size} OFFSET #{offset}
            """)
    List<SeriesWithAuthor> findPublic(@Param("size") int size, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM series WHERE article_count > 0")
    long countPublic();

    /** 單篇 by slug（公開） */
    @Select("""
            SELECT s.id, s.uuid, s.title, s.slug, s.description, s.cover_image_url,
                   s.author_id, s.article_count, s.created_at, s.updated_at,
                   u.uuid AS author_uuid, u.nickname AS author_nickname, u.avatar_url AS author_avatar_url
              FROM series s LEFT JOIN users u ON s.author_id = u.id
             WHERE s.slug = #{slug}
            """)
    SeriesWithAuthor findBySlugWithAuthor(@Param("slug") String slug);

    /** 反正規化 +1 */
    @Update("UPDATE series SET article_count = article_count + 1 WHERE id = #{id}")
    int incrementArticleCount(@Param("id") Long id);

    /** 反正規化 -1（守衛 > 0） */
    @Update("UPDATE series SET article_count = article_count - 1 WHERE id = #{id} AND article_count > 0")
    int decrementArticleCount(@Param("id") Long id);

    /**
     * 撈 series 內某 article 的 prev/next（按 series_position 排序）。
     *
     * @return List 內為按 position 排序的 PUBLISHED articles 基本資訊
     */
    @Select("""
            SELECT id, uuid, title, slug, series_position
              FROM articles
             WHERE series_id = #{seriesId} AND status = 'PUBLISHED'
             ORDER BY series_position
            """)
    List<NavRow> findArticlesForNav(@Param("seriesId") Long seriesId);

    @lombok.Data
    class NavRow {
        private Long id;
        private java.util.UUID uuid;
        private String title;
        private String slug;
        private Integer seriesPosition;
    }
}
