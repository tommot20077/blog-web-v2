package dowob.xyz.blog.module.reading.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Article Like MyBatis Mapper - batch is-liked 查詢。
 *
 * <p>從 ArticleMapper 抽出，跟著 ArticleLike service 一起搬到 reading 模組。</p>
 *
 * <p>SQL 使用 user_article_likes（V15 migration 已從 article_likes RENAME）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface ArticleLikeMapper {

    /**
     * 批次查詢「當前使用者按讚過哪些文章」。
     *
     * @param userId     使用者主鍵
     * @param articleIds 要查詢的文章 PK 集合
     * @return 已按讚的 article_id 集合
     */
    @Select({
        "<script>",
        "SELECT article_id FROM user_article_likes",
        " WHERE user_id = #{userId}",
        "   AND article_id IN",
        "<foreach collection='articleIds' item='id' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        "</script>"
    })
    List<Long> findLikedArticleIdsByUser(@Param("userId") Long userId,
                                          @Param("articleIds") List<Long> articleIds);
}
