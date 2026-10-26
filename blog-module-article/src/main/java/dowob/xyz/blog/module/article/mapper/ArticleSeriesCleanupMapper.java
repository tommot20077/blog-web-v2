package dowob.xyz.blog.module.article.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/**
 * 系列刪除連動用的 MyBatis Mapper：供 {@code ArticleSeriesDeletedConsumer} 與
 * {@code ArticleSeriesOrphanReconciliationJob} 清空「指向已刪除系列」的文章歸屬。
 *
 * <p>V32 起 {@code articles.series_uuid} 沒有 FK；原本由指向 series 主鍵的 FK
 * （{@code ON DELETE SET NULL}）完成的「系列刪除 → 文章脫離系列」改由事件＋每日校正負責。
 * <b>只清空歸屬（{@code series_uuid}、{@code series_position} 設為 NULL），絕不刪除文章</b>：
 * 系列只是文章的可選關聯，文章本身仍是有效資料，所以本 mapper 沒有任何 DELETE 語句。</p>
 *
 * <p>與改造前的 FK 效果相同：<b>不遞增 {@code version}、不更新 {@code updated_at}</b>
 * （FK 的 SET NULL 不會觸發任何其他欄位變更）。每個方法都是單一 SQL 敘述，本身即原子，
 * 且天然冪等（已清空的列不再符合 WHERE 條件）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface ArticleSeriesCleanupMapper {

    /**
     * 清空某系列所有成員文章的系列歸屬。天然冪等：沒有成員時影響 0 列，不會出錯。
     *
     * @param seriesUuid 已被刪除的系列公開 UUID
     * @return 被清空歸屬的文章筆數
     */
    @Update("UPDATE articles SET series_uuid = NULL, series_position = NULL "
            + "WHERE series_uuid = #{seriesUuid}::uuid")
    int clearSeriesByUuid(@Param("seriesUuid") UUID seriesUuid);

    /**
     * 清空多個系列所有成員文章的系列歸屬。呼叫端須保證集合非空，避免產生 {@code IN ()} 語法錯誤。
     *
     * @param seriesUuids 已確認不存在的系列公開 UUID，至少一筆
     * @return 被清空歸屬的文章筆數
     */
    @Update({
        "<script>",
        "UPDATE articles SET series_uuid = NULL, series_position = NULL",
        " WHERE series_uuid IN",
        "<foreach collection='seriesUuids' item='uuid' open='(' separator=',' close=')'>#{uuid}::uuid</foreach>",
        "</script>"
    })
    int clearSeriesByUuids(@Param("seriesUuids") List<UUID> seriesUuids);

    /**
     * 以 keyset 分頁取得 {@code articles} 中被參照的不重複系列 UUID（只含有系列的文章）。
     *
     * <p>排序與比較都由資料庫的 uuid 型別決定（位元組無號比較），不可改在 Java 端排序，
     * 見 {@code OrphanReconciliationJob#fetchReferencedUuids} 的說明。回傳 {@code uuid::text}，
     * 由呼叫端轉回 {@link UUID}。{@code series_uuid IS NOT NULL} 與 partial index
     * {@code idx_articles_series_position} 的條件一致，可走該索引。內層
     * {@code SELECT DISTINCT ... ORDER BY ... LIMIT} 取頁，外層再依相同順序輸出。</p>
     *
     * @param after 游標（不含）；null 表示從頭開始
     * @param limit 本頁筆數上限
     * @return 升冪、去重的系列 UUID 字串
     */
    @Select({
        "<script>",
        "SELECT t.u::text FROM (",
        "  SELECT DISTINCT series_uuid AS u FROM articles",
        "  WHERE series_uuid IS NOT NULL",
        "  <if test='after != null'>AND series_uuid &gt; #{after}::uuid</if>",
        "  ORDER BY u LIMIT #{limit}",
        ") t ORDER BY t.u",
        "</script>"
    })
    List<String> findDistinctSeriesUuidsAfter(@Param("after") UUID after, @Param("limit") int limit);
}
