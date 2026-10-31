package dowob.xyz.blog.architecture.fixture.tableaccess.module.comment.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * CrossModuleBoundaryTest 的合規 fixture：comment 模組四種 MyBatis 標註只碰自己的表。被判違規即為假陽性。
 *
 * @author Yuan
 * @version 1.0
 */
public interface CommentOwnTablesMapper {

    /** 合規。 */
    @Select("SELECT * FROM comments WHERE id = #{id}")
    String select();

    /** 合規。 */
    @Insert("INSERT INTO comments (id) VALUES (#{id})")
    int insert();

    /** 合規。 */
    @Update("UPDATE comments SET body = #{body}")
    int update();

    /** 合規。 */
    @Delete("DELETE FROM comment_likes WHERE comment_id = #{id}")
    int delete();

    /** 合規：逗號在 SELECT 清單中，欄位名 users_count 不是表。 */
    @Select("SELECT c.id, users_count FROM comments c")
    String commaInSelectList();

    /** 合規：欄位 user_uuid 以 user 開頭但不是 users 表。 */
    @Select("SELECT c.id, c.user_uuid FROM comments c")
    String userUuidColumn();

    /** 合規：逗號出現在括號內與 ORDER BY，之後的 users_count 是欄位。 */
    @Select("SELECT c.id FROM comments c WHERE c.id IN (1, 2) ORDER BY c.id, users_count")
    String commaInParenAndOrderBy();

    /** 合規：自己的兩張表以逗號式 JOIN。 */
    @Select("SELECT c.id FROM comments c, comment_likes l WHERE l.comment_id = c.id")
    String ownCommaJoin();
}
