package dowob.xyz.blog.module.series.mapper;

import dowob.xyz.blog.module.series.model.SeriesWithAuthor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * Series MyBatis Mapper。
 *
 * <p>負責複雜 JOIN（series + users）、反正規化計數 update。
 * prev/next 導覽與 PUBLISHED 計數查詢已移至 {@code ArticleFacade}
 * （見 {@code dowob.xyz.blog.infrastructure.facade.ArticleFacade}）——
 * 那 3 條查詢零個 series 欄位，是純 articles 查詢，本來就不該放在這個 Mapper。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface SeriesMapper {

    /**
     * 列表（公開）：只列「實際含至少一篇 PUBLISHED 文章」的 series，最新優先。
     *
     * <p>可見性與 article_count 反正規化欄位<b>解耦</b>——以 EXISTS 子查詢判斷真實公開內容，
     * 避免文章 unpublish 後計數漂移導致「只含草稿」的 series 曝光給匿名訪客；
     * article_count 投影亦改為即時 PUBLISHED 計數，與詳情端點口徑一致。</p>
     */
    @Select("""
            SELECT s.id, s.uuid, s.title, s.slug, s.description, s.cover_image_url,
                   s.author_id,
                   (SELECT COUNT(*) FROM articles a
                     WHERE a.series_id = s.id AND a.status = 'PUBLISHED') AS article_count,
                   s.created_at, s.updated_at,
                   u.uuid AS author_uuid, u.nickname AS author_nickname, u.avatar_url AS author_avatar_url
              FROM series s LEFT JOIN users u ON s.author_id = u.id
             WHERE EXISTS (SELECT 1 FROM articles a
                            WHERE a.series_id = s.id AND a.status = 'PUBLISHED')
             ORDER BY s.created_at DESC
             LIMIT #{size} OFFSET #{offset}
            """)
    List<SeriesWithAuthor> findPublic(@Param("size") int size, @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*) FROM series s
             WHERE EXISTS (SELECT 1 FROM articles a
                            WHERE a.series_id = s.id AND a.status = 'PUBLISHED')
            """)
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
     * 批次取得 series 基本資訊（給 ArticleQueryService.enrich 用，避免 N+1）。
     *
     * <p>原版本以 articleIds 為入參並 JOIN articles 取 series_id（ARCH-13 第 7 處）。
     * 但呼叫端在 article 模組內、手上已有 {@code ArticleData.seriesId}，
     * 故改為直接收 seriesIds，本查詢不再碰 articles。</p>
     *
     * @param seriesIds series 主鍵集合
     * @return series 基本資訊列
     */
    @Select({
        "<script>",
        "SELECT id AS seriesId, uuid AS seriesUuid, title AS seriesTitle",
        "  FROM series",
        " WHERE id IN",
        "<foreach collection='seriesIds' item='id' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        "</script>"
    })
    List<SeriesBasicRow> findBasicInfoBySeriesIds(@Param("seriesIds") Collection<Long> seriesIds);

    /**
     * {@link #findBasicInfoBySeriesIds} 的投影列。
     *
     * @param seriesId    series 主鍵
     * @param seriesUuid  series 公開 UUID
     * @param seriesTitle series 標題
     */
    record SeriesBasicRow(Long seriesId, java.util.UUID seriesUuid, String seriesTitle) {}
}
