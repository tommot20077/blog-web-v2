package dowob.xyz.blog.module.comment.mapper;

import dowob.xyz.blog.module.comment.model.CommentWithAuthor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Comment MyBatis Mapper。
 *
 * <p>負責複雜查詢（JOIN users）與原子 update（counters / 軟刪除）。
 * 簡單 CRUD 由 CommentRepository 處理。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface CommentMapper {

    /**
     * 列出文章的 top-level 留言（含軟刪除佔位），JOIN users 取作者資訊。
     *
     * @param articleId 文章主鍵
     * @param sort      "newest" | "oldest"
     * @param limit     分頁筆數
     * @param offset    跳過筆數
     */
    @Select({
        "<script>",
        "SELECT c.id, c.uuid, c.article_id, c.parent_id, NULL AS parent_uuid,",
        "       c.user_id, c.content, c.content_html, c.like_count,",
        "       c.edited_at, c.deleted_at, c.deleted_by_role, c.created_at,",
        "       u.uuid AS author_uuid, u.nickname AS author_nickname,",
        "       u.avatar_url AS author_avatar_url",
        "  FROM comments c LEFT JOIN users u ON c.user_id = u.id",
        " WHERE c.article_id = #{articleId} AND c.parent_id IS NULL",
        " ORDER BY c.created_at",
        "<choose>",
        "  <when test='sort == &quot;oldest&quot;'>ASC</when>",
        "  <otherwise>DESC</otherwise>",
        "</choose>",
        " LIMIT #{limit} OFFSET #{offset}",
        "</script>"
    })
    List<CommentWithAuthor> findTopLevelByArticle(@Param("articleId") Long articleId,
                                                    @Param("sort") String sort,
                                                    @Param("limit") int limit,
                                                    @Param("offset") int offset);

    /**
     * 一次撈多個 top-level 的所有 replies（按 parent_id 分組、created_at ASC）。
     * 過濾軟刪除 leaf reply。
     */
    @Select({
        "<script>",
        "SELECT c.id, c.uuid, c.article_id, c.parent_id,",
        "       (SELECT p.uuid FROM comments p WHERE p.id = c.parent_id) AS parent_uuid,",
        "       c.user_id, c.content, c.content_html, c.like_count,",
        "       c.edited_at, c.deleted_at, c.deleted_by_role, c.created_at,",
        "       u.uuid AS author_uuid, u.nickname AS author_nickname,",
        "       u.avatar_url AS author_avatar_url",
        "  FROM comments c LEFT JOIN users u ON c.user_id = u.id",
        " WHERE c.parent_id IN",
        "<foreach collection='parentIds' item='id' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        "   AND c.deleted_at IS NULL",
        " ORDER BY c.parent_id, c.created_at ASC",
        "</script>"
    })
    List<CommentWithAuthor> findRepliesByParentIds(@Param("parentIds") List<Long> parentIds);

    /** 文章總留言數（含 reply、含軟刪除佔位） */
    @Select("SELECT COUNT(*) FROM comments WHERE article_id = #{articleId}")
    int countByArticle(@Param("articleId") Long articleId);

    /** 文章 top-level 留言數（用於分頁 totalElements） */
    @Select("SELECT COUNT(*) FROM comments WHERE article_id = #{articleId} AND parent_id IS NULL")
    int countTopLevelByArticle(@Param("articleId") Long articleId);

    /** 原子 +1 like_count */
    @Update("UPDATE comments SET like_count = like_count + 1 WHERE id = #{id}")
    int incrementLikeCount(@Param("id") Long id);

    /** 原子 -1 like_count（守衛 > 0） */
    @Update("UPDATE comments SET like_count = like_count - 1 WHERE id = #{id} AND like_count > 0")
    int decrementLikeCount(@Param("id") Long id);

    /** 軟刪除（service 層計算 deletedByRole 後傳入） */
    @Update("UPDATE comments SET deleted_at = CURRENT_TIMESTAMP, deleted_by_role = #{role} WHERE id = #{id}")
    int softDelete(@Param("id") Long id, @Param("role") String role);

    /**
     * 批次查詢「當前使用者是否按讚某些留言」，避免 N+1。
     * 回傳：當前使用者已按讚的 commentId 集合。
     */
    @Select({
        "<script>",
        "SELECT comment_id FROM comment_likes",
        " WHERE user_id = #{userId}",
        "   AND comment_id IN",
        "<foreach collection='commentIds' item='id' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        "</script>"
    })
    List<Long> findLikedCommentIdsByUser(@Param("userId") Long userId,
                                          @Param("commentIds") List<Long> commentIds);
}
