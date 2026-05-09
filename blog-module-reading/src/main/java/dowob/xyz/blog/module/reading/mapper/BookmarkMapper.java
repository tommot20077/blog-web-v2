package dowob.xyz.blog.module.reading.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Bookmark MyBatis Mapper：批次查詢給 ArticleQueryService 用。
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface BookmarkMapper {

    /**
     * 批次查詢「使用者收藏過哪些文章」。
     */
    @Select({
        "<script>",
        "SELECT article_id FROM user_bookmarks",
        " WHERE user_id = #{userId}",
        "   AND article_id IN",
        "<foreach collection='articleIds' item='id' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        "</script>"
    })
    List<Long> findBookmarkedArticleIdsByUser(@Param("userId") Long userId,
                                                @Param("articleIds") List<Long> articleIds);

    /**
     * 我的收藏文章 id 列表（分頁，最新優先）。
     */
    @Select("""
            SELECT article_id FROM user_bookmarks
             WHERE user_id = #{userId}
             ORDER BY created_at DESC
             LIMIT #{size} OFFSET #{offset}
            """)
    List<Long> findMyBookmarkedArticleIds(@Param("userId") Long userId,
                                            @Param("size") int size,
                                            @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM user_bookmarks WHERE user_id = #{userId}")
    long countByUser(@Param("userId") Long userId);
}
