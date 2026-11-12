package dowob.xyz.blog.architecture.fixture.tableaccess.module.article.mapper;

import org.apache.ibatis.annotations.Select;

/**
 * CrossModuleBoundaryTest 的合規 fixture：article 唯讀 JOIN tag 參照資料
 * （Yuan 2026-10-31 決定放行）。被判違規即為假陽性。
 *
 * @author Yuan
 * @version 1.0
 */
public interface ArticleTagReaderMapper {

    /** 合規：自己的 articles 加上唯讀的 tags／article_tags。 */
    @Select("SELECT a.id, t.name FROM articles a JOIN article_tags at ON at.article_id = a.uuid JOIN tags t ON t.id = at.tag_id")
    String readTags();

    /** 合規：逗號式唯讀 JOIN tag 參照資料。 */
    @Select("SELECT a.id, t.name FROM articles a, article_tags at, tags t WHERE at.article_id = a.uuid AND t.id = at.tag_id")
    String readTagsCommaJoin();
}
