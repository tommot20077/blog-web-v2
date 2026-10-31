package dowob.xyz.blog.architecture.fixture.tableaccess.module.article.mapper;

import org.apache.ibatis.annotations.Select;

/**
 * CrossModuleBoundaryTest 的反向驗證 fixture——<b>故意違規</b>：
 * article 模組 JOIN {@code users}。舊守衛明文豁免 users，Z2 起 users 只有 user 模組可碰，article 也不例外。
 *
 * @author Yuan
 * @version 1.0
 */
public interface ArticleUsersJoinMapper {

    /** 違規。 */
    @Select("SELECT a.id, u.nickname FROM articles a JOIN users u ON u.uuid = a.author_uuid")
    String joinUsers();
}
