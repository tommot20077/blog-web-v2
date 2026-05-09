package dowob.xyz.blog.module.series.mapper;

import dowob.xyz.blog.module.series.model.SeriesWithAuthor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
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
     * 找 prev：series 內 PUBLISHED 且 series_position 比 current 小的最大一筆。
     * 若無（第一篇）回傳 null。
     */
    @Select("""
            SELECT id, uuid, title, slug, series_position
              FROM articles
             WHERE series_id = #{seriesId}
               AND status = 'PUBLISHED'
               AND series_position < #{currentPosition}
             ORDER BY series_position DESC
             LIMIT 1
            """)
    NavRow findPrevNav(@Param("seriesId") Long seriesId,
                       @Param("currentPosition") Integer currentPosition);

    /**
     * 找 next：series 內 PUBLISHED 且 series_position 比 current 大的最小一筆。
     * 若無（最後一篇）回傳 null。
     */
    @Select("""
            SELECT id, uuid, title, slug, series_position
              FROM articles
             WHERE series_id = #{seriesId}
               AND status = 'PUBLISHED'
               AND series_position > #{currentPosition}
             ORDER BY series_position ASC
             LIMIT 1
            """)
    NavRow findNextNav(@Param("seriesId") Long seriesId,
                       @Param("currentPosition") Integer currentPosition);

    /**
     * 計算 series 內 PUBLISHED 文章總數（避免依賴 article_count 反正規化漂移）。
     */
    @Select("SELECT COUNT(*) FROM articles WHERE series_id = #{seriesId} AND status = 'PUBLISHED'")
    int countPublishedInSeries(@Param("seriesId") Long seriesId);

    /**
     * 批次取得 articles 對應的 series 基本資訊（給 ArticleQueryService.enrich 用，避免 N+1）。
     */
    @Select({
        "<script>",
        "SELECT a.id AS article_id, s.uuid AS series_uuid, s.title AS series_title",
        "  FROM articles a",
        "  JOIN series s ON a.series_id = s.id",
        " WHERE a.id IN",
        "<foreach collection='articleIds' item='id' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        "</script>"
    })
    List<ArticleSeriesRow> findSeriesByArticleIds(@Param("articleIds") List<Long> articleIds);

    @lombok.Data
    class ArticleSeriesRow {
        private Long articleId;
        private java.util.UUID seriesUuid;
        private String seriesTitle;
    }

    @lombok.Data
    class NavRow {
        private Long id;
        private java.util.UUID uuid;
        private String title;
        private String slug;
        private Integer seriesPosition;
    }
}
