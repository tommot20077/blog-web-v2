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

    /**
     * 我的全部收藏文章 id（最新優先，不分頁）。
     *
     * <p>供 {@code BookmarkQueryService} 做「先過濾可見性、再分頁」用。
     * 原本 SQL 分頁 ＋ 應用層過濾會使 total 高估、每頁筆數不一致
     * （前端以 {@code pages} 畫分頁器，高估會產生空尾頁）。</p>
     *
     * <p><b>傳輸量界限</b>：與該使用者的收藏數 B 成正比，payload 僅 id（8 bytes/筆）。
     * 個人部落格量級為數十至數百。<b>重評門檻：任一使用者 B &gt; 5,000。</b></p>
     *
     * @param userId 使用者主鍵
     * @return 全部收藏文章主鍵，依收藏時間新到舊
     */
    @Select("""
            SELECT article_id FROM user_bookmarks
             WHERE user_id = #{userId}
             ORDER BY created_at DESC
            """)
    List<Long> findAllMyBookmarkedArticleIds(@Param("userId") Long userId);
}
