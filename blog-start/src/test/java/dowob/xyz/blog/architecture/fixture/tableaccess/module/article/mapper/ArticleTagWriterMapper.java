package dowob.xyz.blog.architecture.fixture.tableaccess.module.article.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

/**
 * CrossModuleBoundaryTest 的反向驗證 fixture——<b>故意違規</b>：
 * tag 參照資料的放行僅限唯讀；article 寫入 {@code article_tags}／{@code tags} 仍然違規。
 *
 * @author Yuan
 * @version 1.0
 */
public interface ArticleTagWriterMapper {

    /** 違規：寫入 article_tags。 */
    @Insert("INSERT INTO article_tags (article_id, tag_id) VALUES (#{a}, #{t})")
    int insertArticleTag();

    /** 違規：刪除 tags（DELETE FROM 看起來像讀取，必須依標註判定為寫入）。 */
    @Delete("DELETE FROM tags WHERE id = #{id}")
    int deleteTag();

    /** 違規：標成 @Select 也沒用，TRUNCATE 一律視為寫入。 */
    @Select("TRUNCATE TABLE article_tags")
    int truncateDisguisedAsSelect();
}
