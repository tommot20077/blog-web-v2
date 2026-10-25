package dowob.xyz.blog.module.reading.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * 文章刪除連動用的 MyBatis Mapper：供 {@code ReadingArticleDeletedConsumer} 與各個
 * {@code OrphanReconciliationJob} 子類別清除指向已刪除文章的 reading 資料。
 *
 * <p><b>每張表一組方法</b>：consumer 在同一個交易內依序呼叫各表的 {@code deleteXxxByArticleUuid}，
 * 每個 job 只用自己那張表的 {@code findDistinctXxxArticleUuidsAfter}／{@code deleteXxxByArticleUuids}。
 * 目前涵蓋 {@code user_article_likes}、{@code user_bookmarks}；M2-c 把 {@code user_highlights}、
 * {@code user_reading_progress} 改為 uuid 後，各加一組方法即可。每個方法都是單一 SQL 敘述，本身即原子。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface ReadingArticleCleanupMapper {

    // ───────────────────────── user_article_likes ─────────────────────────

    /**
     * 刪除某篇文章的所有按讚。天然冪等：已無按讚時刪除 0 筆，不會出錯。
     *
     * @param articleUuid 文章公開 UUID
     * @return 刪除的按讚筆數
     */
    @Delete("DELETE FROM user_article_likes WHERE article_uuid = #{articleUuid}::uuid")
    int deleteLikesByArticleUuid(@Param("articleUuid") UUID articleUuid);

    /**
     * 以 keyset 分頁取得 {@code user_article_likes} 中被參照的不重複文章 UUID。
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
        "  SELECT DISTINCT article_uuid AS u FROM user_article_likes",
        "  <if test='after != null'>WHERE article_uuid &gt; #{after}::uuid</if>",
        "  ORDER BY u LIMIT #{limit}",
        ") t ORDER BY t.u",
        "</script>"
    })
    List<String> findDistinctLikeArticleUuidsAfter(@Param("after") UUID after, @Param("limit") int limit);

    /**
     * 刪除多篇文章的所有按讚。呼叫端須保證集合非空，避免產生 {@code IN ()} 語法錯誤。
     *
     * @param articleUuids 文章公開 UUID，至少一筆
     * @return 刪除的按讚筆數
     */
    @Delete({
        "<script>",
        "DELETE FROM user_article_likes WHERE article_uuid IN",
        "<foreach collection='articleUuids' item='uuid' open='(' separator=',' close=')'>#{uuid}::uuid</foreach>",
        "</script>"
    })
    int deleteLikesByArticleUuids(@Param("articleUuids") List<UUID> articleUuids);

    // ───────────────────────── user_bookmarks ─────────────────────────

    /**
     * 刪除某篇文章的所有收藏。天然冪等：已無收藏時刪除 0 筆，不會出錯。
     *
     * @param articleUuid 文章公開 UUID
     * @return 刪除的收藏筆數
     */
    @Delete("DELETE FROM user_bookmarks WHERE article_uuid = #{articleUuid}::uuid")
    int deleteBookmarksByArticleUuid(@Param("articleUuid") UUID articleUuid);

    /**
     * 以 keyset 分頁取得 {@code user_bookmarks} 中被參照的不重複文章 UUID（語意同
     * {@link #findDistinctLikeArticleUuidsAfter}）。
     *
     * @param after 游標（不含）；null 表示從頭開始，此時不加條件
     * @param limit 本頁筆數上限
     * @return 升冪、去重的文章 UUID 字串
     */
    @Select({
        "<script>",
        "SELECT t.u::text FROM (",
        "  SELECT DISTINCT article_uuid AS u FROM user_bookmarks",
        "  <if test='after != null'>WHERE article_uuid &gt; #{after}::uuid</if>",
        "  ORDER BY u LIMIT #{limit}",
        ") t ORDER BY t.u",
        "</script>"
    })
    List<String> findDistinctBookmarkArticleUuidsAfter(@Param("after") UUID after, @Param("limit") int limit);

    /**
     * 刪除多篇文章的所有收藏。呼叫端須保證集合非空，避免產生 {@code IN ()} 語法錯誤。
     *
     * @param articleUuids 文章公開 UUID，至少一筆
     * @return 刪除的收藏筆數
     */
    @Delete({
        "<script>",
        "DELETE FROM user_bookmarks WHERE article_uuid IN",
        "<foreach collection='articleUuids' item='uuid' open='(' separator=',' close=')'>#{uuid}::uuid</foreach>",
        "</script>"
    })
    int deleteBookmarksByArticleUuids(@Param("articleUuids") List<UUID> articleUuids);
}
