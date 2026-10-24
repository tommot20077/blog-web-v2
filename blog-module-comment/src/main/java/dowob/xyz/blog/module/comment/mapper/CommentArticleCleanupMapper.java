package dowob.xyz.blog.module.comment.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * 文章刪除連動用的 MyBatis Mapper：供 {@code CommentArticleDeletedConsumer} 與
 * {@code CommentOrphanReconciliationJob} 清除指向已刪除文章的留言。
 *
 * <p>只操作本模組的 {@code comments} 表；{@code comment_likes} 由模組內 FK
 * {@code ON DELETE CASCADE} 隨留言一併清除，這裡不另外處理。每個方法都是單一 SQL 敘述，
 * 本身即原子，不需要額外交易。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface CommentArticleCleanupMapper {

    /**
     * 刪除某篇文章的所有留言（含回覆；留言讚由 CASCADE 清除）。
     *
     * <p>天然冪等：文章已無留言時刪除 0 筆，不會出錯。</p>
     *
     * @param articleUuid 文章公開 UUID
     * @return 刪除的留言筆數
     */
    @Delete("DELETE FROM comments WHERE article_uuid = #{articleUuid}::uuid")
    int deleteByArticleUuid(@Param("articleUuid") UUID articleUuid);

    /**
     * 以 keyset 分頁取得 {@code comments} 中被參照的不重複文章 UUID。
     *
     * <p>排序與比較都由資料庫的 uuid 型別決定（位元組無號比較），不可改在 Java 端排序，
     * 見 {@code OrphanReconciliationJob#fetchReferencedUuids} 的說明。回傳 {@code uuid::text}，
     * 由呼叫端轉回 {@link UUID}（與既有 mapper 對 UUID 結果的處理一致，不依賴 type handler）。
     * 內層 {@code SELECT DISTINCT ... ORDER BY ... LIMIT} 取頁，外層再依相同順序輸出。</p>
     *
     * @param after 游標（不含）；null 表示從頭開始，此時不加條件
     * @param limit 本頁筆數上限
     * @return 升冪、去重的文章 UUID 字串
     */
    @Select({
        "<script>",
        "SELECT t.u::text FROM (",
        "  SELECT DISTINCT article_uuid AS u FROM comments",
        "  <if test='after != null'>WHERE article_uuid &gt; #{after}::uuid</if>",
        "  ORDER BY u LIMIT #{limit}",
        ") t ORDER BY t.u",
        "</script>"
    })
    List<String> findDistinctArticleUuidsAfter(@Param("after") UUID after, @Param("limit") int limit);

    /**
     * 刪除多篇文章的所有留言（含回覆；留言讚由 CASCADE 清除）。
     *
     * <p>呼叫端須保證集合非空，避免產生 {@code IN ()} 語法錯誤。</p>
     *
     * @param articleUuids 文章公開 UUID，至少一筆
     * @return 刪除的留言筆數
     */
    @Delete({
        "<script>",
        "DELETE FROM comments WHERE article_uuid IN",
        "<foreach collection='articleUuids' item='uuid' open='(' separator=',' close=')'>#{uuid}::uuid</foreach>",
        "</script>"
    })
    int deleteByArticleUuids(@Param("articleUuids") List<UUID> articleUuids);
}
