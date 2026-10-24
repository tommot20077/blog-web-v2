package dowob.xyz.blog.module.comment.mapper;

import dowob.xyz.blog.module.comment.model.CommentRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/**
 * Comment MyBatis Mapper。
 *
 * <p>負責複雜查詢與原子 update（counters / 軟刪除 / 按讚）。簡單 CRUD 由 CommentRepository 處理。
 * 本 mapper 只讀寫 {@code comments}、{@code comment_likes} 兩張自己的表，不 JOIN 其他模組的表；
 * 作者資訊由 service 經 {@code UserFacade} 批次取得。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Mapper
public interface CommentMapper {

    /**
     * 列出文章的 top-level 留言（含軟刪除佔位）。
     *
     * @param articleUuid 文章公開 UUID
     * @param sort      "newest" | "oldest"
     * @param limit     分頁筆數
     * @param offset    跳過筆數
     */
    @Select({
        "<script>",
        "SELECT c.id, c.uuid, c.article_uuid, c.parent_id, NULL AS parent_uuid,",
        "       c.user_uuid, c.content, c.content_html, c.like_count,",
        "       c.edited_at, c.deleted_at, c.deleted_by_role, c.created_at",
        "  FROM comments c",
        " WHERE c.article_uuid = #{articleUuid}::uuid AND c.parent_id IS NULL",
        " ORDER BY c.created_at",
        "<choose>",
        "  <when test='sort == &quot;oldest&quot;'>ASC</when>",
        "  <otherwise>DESC</otherwise>",
        "</choose>",
        " LIMIT #{limit} OFFSET #{offset}",
        "</script>"
    })
    List<CommentRow> findTopLevelByArticle(@Param("articleUuid") UUID articleUuid,
                                           @Param("sort") String sort,
                                           @Param("limit") int limit,
                                           @Param("offset") int offset);

    /**
     * 一次撈多個 top-level 的所有 replies（按 parent_id 分組、created_at ASC）。
     * 過濾軟刪除 leaf reply。
     */
    @Select({
        "<script>",
        "SELECT c.id, c.uuid, c.article_uuid, c.parent_id,",
        "       (SELECT p.uuid FROM comments p WHERE p.id = c.parent_id) AS parent_uuid,",
        "       c.user_uuid, c.content, c.content_html, c.like_count,",
        "       c.edited_at, c.deleted_at, c.deleted_by_role, c.created_at",
        "  FROM comments c",
        " WHERE c.parent_id IN",
        "<foreach collection='parentIds' item='id' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        "   AND c.deleted_at IS NULL",
        " ORDER BY c.parent_id, c.created_at ASC",
        "</script>"
    })
    List<CommentRow> findRepliesByParentIds(@Param("parentIds") List<Long> parentIds);

    /**
     * 文章總留言數，與列表顯示規則一致：
     * - 保留 top-level 軟刪除留言（顯示為 [已刪除] tombstone）
     * - 排除軟刪除的 replies（列表 findRepliesByParentIds 會過濾，count 也應排除）
     */
    @Select("SELECT COUNT(*) FROM comments WHERE article_uuid = #{articleUuid}::uuid "
            + "AND (parent_id IS NULL OR deleted_at IS NULL)")
    int countByArticle(@Param("articleUuid") UUID articleUuid);

    /** 文章 top-level 留言數（用於分頁 totalElements） */
    @Select("SELECT COUNT(*) FROM comments WHERE article_uuid = #{articleUuid}::uuid AND parent_id IS NULL")
    int countTopLevelByArticle(@Param("articleUuid") UUID articleUuid);

    /**
     * 新增留言讚；已按過讚時什麼都不做（RACE-01）。
     *
     * <p>以 {@code UNIQUE (user_uuid, comment_id)} 為衝突目標的 {@code ON CONFLICT DO NOTHING}：
     * 並發落敗者不會拋 {@code DataIntegrityViolationException}（因此不會把外層交易弄成 rollback-only），
     * 而是回傳 0。呼叫端依回傳值決定是否 {@code incrementLikeCount}。</p>
     *
     * @param userUuid  按讚者公開 UUID
     * @param commentId 留言主鍵
     * @return 實際新增的列數：1 = 這次才按讚，0 = 先前已按過（或並發中對方先寫入）
     */
    @Insert("INSERT INTO comment_likes (user_uuid, comment_id, created_at) "
            + "VALUES (#{userUuid}::uuid, #{commentId}, CURRENT_TIMESTAMP) "
            + "ON CONFLICT (user_uuid, comment_id) DO NOTHING")
    int insertLikeIgnoreConflict(@Param("userUuid") UUID userUuid, @Param("commentId") Long commentId);

    /** 原子 +1 like_count */
    @Update("UPDATE comments SET like_count = like_count + 1 WHERE id = #{id}")
    int incrementLikeCount(@Param("id") Long id);

    /** 原子 -1 like_count（守衛 > 0） */
    @Update("UPDATE comments SET like_count = like_count - 1 WHERE id = #{id} AND like_count > 0")
    int decrementLikeCount(@Param("id") Long id);

    /**
     * 軟刪除（service 層計算 deletedByRole 後傳入）。
     *
     * <p>加上 {@code AND deleted_at IS NULL}（RACE-09）：兩個交易都讀到「尚未刪除」時，只有先 UPDATE 的那個會命中，
     * 後者回傳 0；呼叫端依回傳值決定是否扣 {@code articles.comment_count}，避免雙扣。
     * 同時保證先刪除者的 {@code deleted_at}、{@code deleted_by_role} 不會被後來者覆寫。</p>
     *
     * @return 實際被軟刪除的列數：1 = 這次才刪除，0 = 已被刪除（或不存在）
     */
    @Update("UPDATE comments SET deleted_at = CURRENT_TIMESTAMP, deleted_by_role = #{role} "
            + "WHERE id = #{id} AND deleted_at IS NULL")
    int softDelete(@Param("id") Long id, @Param("role") String role);

    /**
     * 批次查詢「當前使用者是否按讚某些留言」，避免 N+1。
     * 回傳：當前使用者已按讚的 commentId 集合。
     */
    @Select({
        "<script>",
        "SELECT comment_id FROM comment_likes",
        " WHERE user_uuid = #{userUuid}::uuid",
        "   AND comment_id IN",
        "<foreach collection='commentIds' item='id' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        "</script>"
    })
    List<Long> findLikedCommentIdsByUser(@Param("userUuid") UUID userUuid,
                                          @Param("commentIds") List<Long> commentIds);
}
