package dowob.xyz.blog.architecture.fixture.tableaccess.module.comment.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

/**
 * CrossModuleBoundaryTest 的反向驗證 fixture——<b>故意違規</b>：
 * comment 模組以各種「不是 {@code FROM users} / {@code JOIN users} 的樣子」存取 user 模組的 {@code users}，
 * 驗證表名比對不被逗號式 JOIN、雙引號識別字、TRUNCATE 繞過。
 *
 * @author Yuan
 * @version 1.0
 */
public interface CommentSqlSyntaxMapper {

    /** 違規：逗號式隱含 JOIN。 */
    @Select("SELECT c.id FROM comments c, users u WHERE c.user_uuid = u.uuid")
    String commaJoin();

    /** 違規：逗號式 JOIN，表在第三個位置且帶 schema。 */
    @Select("SELECT c.id FROM comments c, comment_likes l, public.users u WHERE l.user_uuid = u.uuid")
    String commaJoinThird();

    /** 違規：雙引號識別字。 */
    @Select("SELECT * FROM \"users\"")
    String quotedTable();

    /** 違規：schema 未加引號、表加引號。 */
    @Select("SELECT * FROM comments c JOIN public.\"users\" u ON u.uuid = c.user_uuid")
    String quotedTableWithSchema();

    /** 違規：schema 與表都加引號。 */
    @Select("SELECT * FROM \"public\".\"users\"")
    String fullyQuoted();

    /** 違規：TRUNCATE TABLE。 */
    @Delete("TRUNCATE TABLE users")
    int truncateTable();

    /** 違規：TRUNCATE 多表，users 在逗號之後。 */
    @Delete("TRUNCATE comments, users")
    int truncateList();
}
