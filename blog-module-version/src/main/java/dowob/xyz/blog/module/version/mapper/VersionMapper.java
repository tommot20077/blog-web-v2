package dowob.xyz.blog.module.version.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Version 模組 MyBatis Mapper — retention DELETE / count 等批次操作。
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface VersionMapper {

    /**
     * 滾動保留：刪掉 article 的 AUTO 類型快照中超過 retain 份的最舊那些。
     *
     * <p>用 OFFSET 跳過最新 retain 份，剩下的就是要刪的。</p>
     */
    @Update({
        "DELETE FROM article_versions",
        " WHERE id IN (",
        "   SELECT id FROM article_versions",
        "    WHERE article_id = #{articleId} AND type = 'AUTO'",
        "    ORDER BY created_at DESC",
        "    OFFSET #{retain}",
        " )"
    })
    int retainAuto(@Param("articleId") Long articleId, @Param("retain") int retain);

    /**
     * publish 凍結時清掉所有 AUTO 快照（保留 MANUAL / PUBLISHED）。
     */
    @Update("DELETE FROM article_versions WHERE article_id = #{articleId} AND type = 'AUTO'")
    int deleteAutoByArticle(@Param("articleId") Long articleId);

    /**
     * 計算 article 的 PUBLISHED 快照數（用於 freezePublished 算 vN）。
     */
    @Select("SELECT COUNT(*) FROM article_versions WHERE article_id = #{articleId} AND type = 'PUBLISHED'")
    int countPublished(@Param("articleId") Long articleId);
}
