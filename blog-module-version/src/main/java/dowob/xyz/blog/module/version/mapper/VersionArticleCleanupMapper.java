package dowob.xyz.blog.module.version.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * 文章刪除連動用的 MyBatis Mapper：供 {@code VersionArticleDeletedConsumer} 與
 * {@code ArticleVersionOrphanReconciliationJob} 清除指向已刪除文章的版本快照。
 *
 * <p>V28 起 {@code article_versions} 以 {@code article_uuid} 識別文章、沒有 FK；原本由
 * 指向 articles 的 {@code ON DELETE CASCADE} 完成的「文章刪除 → 版本刪除」改由事件＋每日校正負責。
 * 每個方法都是單一 SQL 敘述，本身即原子。{@code user_preferences} 以使用者識別、使用者只軟刪除，
 * 不需要連動，故本 mapper 不涵蓋。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface VersionArticleCleanupMapper {

    /**
     * 刪除某篇文章的所有版本快照（含 AUTO、MANUAL、PUBLISHED）。天然冪等：已無快照時刪除 0 筆，不會出錯。
     *
     * @param articleUuid 文章公開 UUID
     * @return 刪除的快照筆數
     */
    @Delete("DELETE FROM article_versions WHERE article_uuid = #{articleUuid}::uuid")
    int deleteByArticleUuid(@Param("articleUuid") UUID articleUuid);

    /**
     * 以 keyset 分頁取得 {@code article_versions} 中被參照的不重複文章 UUID。
     *
     * <p>排序與比較都由資料庫的 uuid 型別決定（位元組無號比較），不可改在 Java 端排序，
     * 見 {@code OrphanReconciliationJob#fetchReferencedUuids} 的說明。回傳 {@code uuid::text}，
     * 由呼叫端轉回 {@link UUID}。內層 {@code SELECT DISTINCT ... ORDER BY ... LIMIT} 取頁，
     * 外層再依相同順序輸出。</p>
     *
     * @param after 游標（不含）；null 表示從頭開始，此時不加條件
     * @param limit 本頁筆數上限
     * @return 升冪、去重的文章 UUID 字串
     */
    @Select({
        "<script>",
        "SELECT t.u::text FROM (",
        "  SELECT DISTINCT article_uuid AS u FROM article_versions",
        "  <if test='after != null'>WHERE article_uuid &gt; #{after}::uuid</if>",
        "  ORDER BY u LIMIT #{limit}",
        ") t ORDER BY t.u",
        "</script>"
    })
    List<String> findDistinctArticleUuidsAfter(@Param("after") UUID after, @Param("limit") int limit);

    /**
     * 刪除多篇文章的所有版本快照。呼叫端須保證集合非空，避免產生 {@code IN ()} 語法錯誤。
     *
     * @param articleUuids 文章公開 UUID，至少一筆
     * @return 刪除的快照筆數
     */
    @Delete({
        "<script>",
        "DELETE FROM article_versions WHERE article_uuid IN",
        "<foreach collection='articleUuids' item='uuid' open='(' separator=',' close=')'>#{uuid}::uuid</foreach>",
        "</script>"
    })
    int deleteByArticleUuids(@Param("articleUuids") List<UUID> articleUuids);
}
