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
     * 取回全部 series 主鍵，最新優先。
     *
     * <p>公開列表的第一段：series 為低基數實體（部落格量級為數十），
     * 全量取主鍵後，由 {@code ArticleFacade.countPublishedBySeriesIds} 完成
     * 「有無公開文章」的過濾（原本是直讀 articles 的 EXISTS 子查詢，ARCH-13）。</p>
     *
     * @return 全部 series 主鍵，依建立時間新到舊
     */
    @Select("SELECT id FROM series ORDER BY created_at DESC")
    List<Long> findAllIdsOrderByCreatedAtDesc();

    /**
     * 依主鍵批次取回 series 明細（含作者）。
     *
     * <p>公開列表的第三段：只查該頁的 id。{@code users} 為 reference data，
     * 依 {@code architecture.md} 可直接 JOIN。</p>
     *
     * <p>注意：本查詢<b>不投影 article_count</b>——列表的計數改用
     * {@code ArticleFacade.countPublishedBySeriesIds} 的即時值，
     * 不沿用 {@code series.article_count} 這個含非公開文章的反正規化欄位。</p>
     *
     * @param ids series 主鍵集合
     * @return series 明細列（順序不保證，由 caller 依輸入順序重排）
     */
    @Select({
        "<script>",
        "SELECT s.id, s.uuid, s.title, s.slug, s.description, s.cover_image_url,",
        "       s.author_id, s.created_at, s.updated_at,",
        "       u.uuid AS author_uuid, u.nickname AS author_nickname, u.avatar_url AS author_avatar_url",
        "  FROM series s LEFT JOIN users u ON s.author_id = u.id",
        " WHERE s.id IN",
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        "</script>"
    })
    List<SeriesWithAuthor> findByIdsWithAuthor(@Param("ids") List<Long> ids);

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
     * <p>原版本以 articleIds 為入參並跨表取 series_id（ARCH-13 第 7 處）。
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
