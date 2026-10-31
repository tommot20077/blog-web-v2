package dowob.xyz.blog.architecture.fixture.tableaccess.module.comment.mapper;

import org.apache.ibatis.annotations.Select;

/**
 * CrossModuleBoundaryTest（SQL 表所屬）的反向驗證 fixture——<b>故意違規</b>：
 * comment 模組的 mapper 以 MyBatis {@code @Select} JOIN 了 user 模組的 {@code users}，
 * 並以<b>陣列</b>寫法宣告 SQL（驗證陣列攤平）。
 *
 * @author Yuan
 * @version 1.0
 */
public interface CommentUsersJoinMapper {

    /** 違規：comment 讀 users（Z2 起 users 不再是 reference data）。 */
    @Select({"SELECT c.id, u.nickname FROM comments c", "JOIN users u ON u.uuid = c.author_uuid"})
    String joinUsers();

    /** 違規：comment 不是 tag 參照資料的放行對象，不可讀 tags。 */
    @Select("SELECT t.name FROM tags t")
    String readTags();
}
