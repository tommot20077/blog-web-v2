package dowob.xyz.blog.architecture.fixture.tableaccess.module.user.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * CrossModuleBoundaryTest 的合規 fixture：user 模組存取自己的 {@code users}／{@code verification_tokens}。
 * 被判違規即為假陽性。
 *
 * @author Yuan
 * @version 1.0
 */
public interface UserOwnTablesMapper {

    /** 合規。 */
    @Select("SELECT * FROM users u LEFT JOIN verification_tokens v ON v.user_id = u.id")
    String select();

    /** 合規。 */
    @Update("UPDATE users SET nickname = #{nickname}")
    int update();
}
