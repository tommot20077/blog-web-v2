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
}
