package dowob.xyz.blog.module.series.mapper;

import dowob.xyz.blog.module.series.model.Series;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Series MyBatis Mapper。
 *
 * <p>負責 series 自己的表的寫入（含 slug 衝突處理）、批次讀取與反正規化計數 update，<b>不 JOIN 任何其他模組的表</b>
 * （作者資訊由 {@code UserFacade.getDisplayInfoByUuids} 批次解析）。
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
     * 取回全部 series 的公開 UUID，最新優先。
     *
     * <p>公開列表的第一段：series 為低基數實體（部落格量級為數十），
     * 全量取 UUID 後，由 {@code ArticleFacade.countPublishedBySeriesUuids} 完成
     * 「有無公開文章」的過濾（原本是直讀 articles 的 EXISTS 子查詢，ARCH-13）。</p>
     *
     * @return 全部 series 的公開 UUID，依建立時間新到舊
     */
    @Select("SELECT uuid FROM series ORDER BY created_at DESC")
    List<UUID> findAllUuidsOrderByCreatedAtDesc();

    /**
     * 依公開 UUID 批次取回 series 明細（不含作者的暱稱與頭像）。
     *
     * <p>公開列表的第三段：只查該頁的 uuid。作者只帶 {@code author_uuid}，呼叫端收集整頁的作者 UUID 後
     * 以 {@code UserFacade.getDisplayInfoByUuids} 一次解析（不再跨表連結使用者資料）。</p>
     *
     * <p>注意：本查詢<b>不投影 article_count</b>——列表的計數改用
     * {@code ArticleFacade.countPublishedBySeriesUuids} 的即時值，
     * 不沿用 {@code series.article_count} 這個含非公開文章的反正規化欄位。</p>
     *
     * @param uuids series 公開 UUID 集合（大小以呼叫端的頁大小為界，不得為空）
     * @return series 明細列（順序不保證，由 caller 依輸入順序重排）
     */
    @Select({
        "<script>",
        "SELECT id, uuid, title, slug, description, cover_image_url, author_uuid, created_at, updated_at",
        "  FROM series",
        " WHERE uuid IN",
        "<foreach collection='uuids' item='u' open='(' separator=',' close=')'>",
        "  #{u}::uuid",
        "</foreach>",
        "</script>"
    })
    List<Series> findByUuids(@Param("uuids") List<UUID> uuids);

    /**
     * 新增 series；slug 已被使用時不寫入也不拋例外（RACE-15）。
     *
     * <p>{@code INSERT ... ON CONFLICT (slug) DO NOTHING}：兩個請求同時以相同 slug 建立，資料庫唯一約束決定
     * 贏家，落敗者得到 affected = 0，由 service 轉成 {@code SLUG_ALREADY_USED}；沒有 existsBySlug 先查再寫的
     * 競態，也不會讓 PostgreSQL 交易因唯一約束錯誤而中止。{@code created_at}／{@code updated_at}
     * 由呼叫端以 Java 時間（{@code LocalDateTime.now()}）傳入，與其他寫入路徑的 {@code @CreatedDate}／
     * {@code @LastModifiedDate} 同一個時鐘來源，不用資料庫的 CURRENT_TIMESTAMP。</p>
     *
     * @param series 要新增的 series（uuid、title、slug、description、coverImageUrl、authorUuid、articleCount、createdAt、updatedAt）
     * @return 受影響列數：1 表示新增成功，0 表示 slug 已被使用
     */
    @Insert("INSERT INTO series (uuid, title, slug, description, cover_image_url, author_uuid, article_count, "
            + "created_at, updated_at) "
            + "VALUES (#{uuid}::uuid, #{title}, #{slug}, #{description}, #{coverImageUrl}, #{authorUuid}::uuid, "
            + "#{articleCount}, #{createdAt}, #{updatedAt}) ON CONFLICT (slug) DO NOTHING")
    int insertIfSlugAbsent(Series series);

    /**
     * 以單一 UPDATE 更新 series 的基本欄位（RACE-15）：只有非 null 的參數才會更新，其餘保持原值。
     *
     * <p>slug 唯一性由資料庫約束保證：改成已被他人使用的 slug 會拋 {@code DuplicateKeyException}，
     * 由 service 轉成 {@code SLUG_ALREADY_USED}。呼叫端必須把本 UPDATE 當作交易內捕捉例外後的最後一個
     * DB 操作——PostgreSQL 的交易在錯誤後即中止，之後的任何 SQL 都會失敗。</p>
     *
     * @param id            series 主鍵
     * @param title         新標題；null 表示不更新
     * @param slug          新 slug；null 表示不更新
     * @param description   新描述；null 表示不更新
     * @param coverImageUrl 新封面網址；null 表示不更新
     * @param updatedAt     寫入的 updated_at（JVM 時間）
     * @return 受影響列數
     */
    @Update("UPDATE series SET title = COALESCE(#{title,jdbcType=VARCHAR}, title), "
            + "slug = COALESCE(#{slug,jdbcType=VARCHAR}, slug), "
            + "description = COALESCE(#{description,jdbcType=VARCHAR}, description), "
            + "cover_image_url = COALESCE(#{coverImageUrl,jdbcType=VARCHAR}, cover_image_url), "
            + "updated_at = #{updatedAt} WHERE id = #{id}")
    int updateBasicFields(@Param("id") Long id,
                          @Param("title") String title,
                          @Param("slug") String slug,
                          @Param("description") String description,
                          @Param("coverImageUrl") String coverImageUrl,
                          @Param("updatedAt") LocalDateTime updatedAt);

    /** 反正規化 +1 */
    @Update("UPDATE series SET article_count = article_count + 1 WHERE id = #{id}")
    int incrementArticleCount(@Param("id") Long id);

    /** 反正規化 -1（守衛 > 0） */
    @Update("UPDATE series SET article_count = article_count - 1 WHERE id = #{id} AND article_count > 0")
    int decrementArticleCount(@Param("id") Long id);

    /**
     * 以公開 UUID 反正規化 -1（守衛 &gt; 0）——供刪除事件 consumer 使用，consumer 不持有 series 主鍵。
     *
     * @param uuid series 公開 UUID
     * @return 受影響列數；series 已不存在或計數已為 0 時為 0
     */
    @Update("UPDATE series SET article_count = article_count - 1 WHERE uuid = #{uuid}::uuid AND article_count > 0")
    int decrementArticleCountByUuid(@Param("uuid") java.util.UUID uuid);

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

    /**
     * 以公開 UUID 批次取得 series 基本資訊（供 {@code SeriesFacade.batchGetSeriesBasicInfoByUuids}，M6-b）。
     *
     * <p>沿用 {@link SeriesBasicRow}（欄位順序 seriesId, seriesUuid, seriesTitle 不可調整，
     * MyBatis 對 record 建構子採位置對應）。</p>
     *
     * @param uuids series 公開 UUID 清單（不得為空，由 caller 保證，避免 {@code IN ()} 語法錯誤）
     * @return 每個存在的 series 的基本資訊列
     */
    @Select({
        "<script>",
        "SELECT id AS seriesId, uuid AS seriesUuid, title AS seriesTitle",
        "  FROM series",
        " WHERE uuid IN",
        "<foreach collection='list' item='uuid' open='(' separator=',' close=')'>#{uuid}::uuid</foreach>",
        "</script>"
    })
    List<SeriesBasicRow> findBasicInfoBySeriesUuids(@Param("list") List<java.util.UUID> uuids);

    /**
     * 在給定的 UUID 中篩出「目前仍存在」的 series。
     *
     * <p>供孤兒校正排程判斷系列是否已被刪除。回傳型別採 {@code String}（{@code uuid::text}），
     * 由 caller 轉回 {@link java.util.UUID}。</p>
     *
     * @param uuids 待查證的 series 公開 UUID（caller 須保證非空，避免 {@code IN ()} 語法錯誤）
     * @return 其中仍存在的 series UUID 字串列表
     */
    @Select("<script>" +
            "SELECT uuid::text AS uuid FROM series " +
            "WHERE uuid IN " +
            "<foreach collection='list' item='uuid' open='(' separator=',' close=')'>#{uuid}::uuid</foreach>" +
            "</script>")
    List<String> findExistingUuidsIn(@Param("list") List<java.util.UUID> uuids);

    /**
     * 批次取得 series 主鍵對應的公開 UUID（供 {@code SeriesFacade.findUuidsByIds}，設計 §3.1 過渡 API）。
     *
     * <p>只投影 id 與 uuid 兩欄，不載入 title 等其他欄位。</p>
     *
     * <p><b>注意</b>：MyBatis 對 record 建構子採位置對應，
     * SELECT 欄位順序（seriesId, seriesUuid）必須與 {@link SeriesUuidRow} 的元件順序一致，不可調整。</p>
     *
     * @param seriesIds series 主鍵集合（不得為空，由 caller 保證）
     * @return 每個存在的 series 的主鍵與 UUID
     */
    @Select({
        "<script>",
        "SELECT id AS seriesId, uuid AS seriesUuid",
        "  FROM series",
        " WHERE id IN",
        "<foreach collection='seriesIds' item='id' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        "</script>"
    })
    List<SeriesUuidRow> findUuidsByIds(@Param("seriesIds") Collection<Long> seriesIds);

    /**
     * {@link #findUuidsByIds} 的投影列。
     *
     * @param seriesId   series 主鍵
     * @param seriesUuid series 公開 UUID
     */
    record SeriesUuidRow(Long seriesId, java.util.UUID seriesUuid) {}

    /**
     * 批次取得 series 公開 UUID 對應的主鍵（供 {@code SeriesFacade.findIdsByUuids}，M4-a 過渡 API）。
     *
     * <p>只投影 id 與 uuid 兩欄；沿用 {@link SeriesUuidRow}（欄位順序 seriesId, seriesUuid 不可調整，
     * MyBatis 對 record 建構子採位置對應）。</p>
     *
     * @param uuids series 公開 UUID 清單（不得為空，由 caller 保證，避免 {@code IN ()} 語法錯誤）
     * @return 每個存在的 series 的主鍵與 UUID
     */
    @Select({
        "<script>",
        "SELECT id AS seriesId, uuid AS seriesUuid",
        "  FROM series",
        " WHERE uuid IN",
        "<foreach collection='uuids' item='u' open='(' separator=',' close=')'>",
        "  #{u}::uuid",
        "</foreach>",
        "</script>"
    })
    List<SeriesUuidRow> findIdsByUuids(@Param("uuids") List<java.util.UUID> uuids);
}
