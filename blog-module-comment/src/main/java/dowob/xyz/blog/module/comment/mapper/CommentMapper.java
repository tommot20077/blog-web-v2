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
     * 每個頂層留言各取前 N 則「未刪除」回覆（D3／PERF-22），依 created_at 升冪、id 為次序鍵。
     *
     * <p><b>為什麼用 {@code LATERAL ... LIMIT}</b>：對每個頂層各自走 {@code idx_comments_replies}
     * （parent_id, created_at）取前 N 列就停，資料庫端只讀 N × 頂層數 列；不是撈出全部回覆再由 Java 截斷
     * （熱門討論串的全部回覆含兩個 TEXT 欄位，可達數十 MB）。軟刪除的回覆先被濾掉，不佔名額。</p>
     *
     * <p>parent uuid 由外層的 {@code comments p} 直接取得（{@code p.id} 為主鍵，不會讓列數增加；
     * 父留言已軟刪除時仍帶出其 uuid）。結果依 parent_id 再 created_at 排序，呼叫端以 {@code groupingBy(parentId)} 消費。</p>
     *
     * @param parentIds 頂層留言主鍵（不得為空；由 {@code BatchedQuery} 切批）
     * @param perParent 每個頂層最多取幾則
     * @return 各頂層的前 N 則回覆
     */
    @Select({
        "<script>",
        "SELECT r.id, r.uuid, r.article_uuid, r.parent_id,",
        "       p.uuid AS parent_uuid,",
        "       r.user_uuid, r.content, r.content_html, r.like_count,",
        "       r.edited_at, r.deleted_at, r.deleted_by_role, r.created_at",
        "  FROM comments p",
        "  JOIN LATERAL (",
        "        SELECT c.id, c.uuid, c.article_uuid, c.parent_id, c.user_uuid, c.content, c.content_html,",
        "               c.like_count, c.edited_at, c.deleted_at, c.deleted_by_role, c.created_at",
        "          FROM comments c",
        "         WHERE c.parent_id = p.id AND c.deleted_at IS NULL",
        "         ORDER BY c.created_at ASC, c.id ASC",
        "         LIMIT #{perParent}",
        "       ) r ON TRUE",
        " WHERE p.id IN",
        "<foreach collection='parentIds' item='id' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        " ORDER BY p.id, r.created_at ASC, r.id ASC",
        "</script>"
    })
    List<CommentRow> findFirstRepliesByParentIds(@Param("parentIds") List<Long> parentIds,
                                                 @Param("perParent") int perParent);

    /**
     * 多個頂層各自的「未刪除」回覆總數（供列表的 {@code replyCount}）。
     *
     * <p>沒有任何未刪除回覆的頂層<b>不會出現在結果中</b>，呼叫端以 0 補。
     * MyBatis 對 record 建構子採位置對應（見 Ruling F1），SELECT 欄位順序須與 {@link ReplyCountRow} 一致。</p>
     *
     * @param parentIds 頂層留言主鍵（不得為空；由 {@code BatchedQuery} 切批，各頂層只出現在一批，結果可直接串接）
     * @return 各頂層的未刪除回覆數
     */
    @Select({
        "<script>",
        "SELECT parent_id AS parentId, COUNT(*) AS replyCount",
        "  FROM comments",
        " WHERE deleted_at IS NULL AND parent_id IN",
        "<foreach collection='parentIds' item='id' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        " GROUP BY parent_id",
        "</script>"
    })
    List<ReplyCountRow> countRepliesByParentIds(@Param("parentIds") List<Long> parentIds);

    /**
     * 單一頂層留言的回覆分頁（未刪除者；created_at 升冪、id 為次序鍵，與列表內嵌的前 N 則同序）。
     *
     * @param parentId 頂層留言主鍵
     * @param limit    分頁筆數
     * @param offset   跳過筆數
     * @return 該頁回覆
     */
    @Select({
        "SELECT c.id, c.uuid, c.article_uuid, c.parent_id,",
        "       p.uuid AS parent_uuid,",
        "       c.user_uuid, c.content, c.content_html, c.like_count,",
        "       c.edited_at, c.deleted_at, c.deleted_by_role, c.created_at",
        "  FROM comments c",
        "  LEFT JOIN comments p ON p.id = c.parent_id",
        " WHERE c.parent_id = #{parentId} AND c.deleted_at IS NULL",
        " ORDER BY c.created_at ASC, c.id ASC",
        " LIMIT #{limit} OFFSET #{offset}"
    })
    List<CommentRow> findReplyPage(@Param("parentId") Long parentId,
                                   @Param("limit") int limit,
                                   @Param("offset") int offset);

    /**
     * 單一頂層留言的「未刪除」回覆總數（回覆分頁的 total）。
     *
     * @param parentId 頂層留言主鍵
     * @return 未刪除回覆數
     */
    @Select("SELECT COUNT(*) FROM comments WHERE parent_id = #{parentId} AND deleted_at IS NULL")
    int countRepliesByParentId(@Param("parentId") Long parentId);

    /**
     * {@link #countRepliesByParentIds} 的投影列。
     *
     * @param parentId   頂層留言主鍵
     * @param replyCount 該頂層的未刪除回覆數
     */
    record ReplyCountRow(Long parentId, Long replyCount) {}

    /**
     * 文章的「未刪除留言數」（頂層加回覆合計），<b>不含任何已刪除的留言，包括頂層墓碑</b>（B5／DATA-06）。
     *
     * <p>與 {@code articles.comment_count} 同一口徑：新增留言時 +1，每次首次軟刪除（頂層、回覆、admin 刪除皆然）
     * 時 -1，因此該欄位恆等於本查詢的結果。墓碑雖仍出現在列表（讓下方回覆保有脈絡），
     * 但已不算一則「留言」，不計入此數。</p>
     */
    @Select("SELECT COUNT(*) FROM comments WHERE article_uuid = #{articleUuid}::uuid AND deleted_at IS NULL")
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
