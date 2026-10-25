package dowob.xyz.blog.module.reading.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * Article Like MyBatis Mapper - 按讚寫入（ON CONFLICT）與 batch is-liked 查詢。
 *
 * <p>從 ArticleMapper 抽出，跟著 ArticleLike service 一起搬到 reading 模組。</p>
 *
 * <p>SQL 使用 user_article_likes（V15 migration 已從 article_likes RENAME）；
 * V26 起使用者與文章一律以 UUID 識別。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Mapper
public interface ArticleLikeMapper {

    /**
     * 新增按讚；已按過讚時什麼都不做（RACE-01）。
     *
     * <p>以 {@code UNIQUE (user_uuid, article_uuid)} 為衝突目標的 {@code ON CONFLICT DO NOTHING}：
     * 並發落敗者不會拋 {@code DataIntegrityViolationException}（因此不會把外層交易弄成 rollback-only），
     * 而是回傳 0。呼叫端依回傳值決定是否 {@code incrementLikeCount}。</p>
     *
     * @param userUuid    按讚者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @return 實際新增的列數：1 = 這次才按讚，0 = 先前已按過（或並發中對方先寫入）
     */
    @Insert("INSERT INTO user_article_likes (user_uuid, article_uuid, created_at) "
            + "VALUES (#{userUuid}::uuid, #{articleUuid}::uuid, CURRENT_TIMESTAMP) "
            + "ON CONFLICT (user_uuid, article_uuid) DO NOTHING")
    int insertIgnoreConflict(@Param("userUuid") UUID userUuid, @Param("articleUuid") UUID articleUuid);

    /**
     * 批次查詢「當前使用者按讚過哪些文章」。
     *
     * @param userUuid     使用者公開 UUID
     * @param articleUuids 要查詢的文章公開 UUID 集合
     * @return 已按讚的文章 UUID 集合
     */
    @Select({
        "<script>",
        "SELECT article_uuid FROM user_article_likes",
        " WHERE user_uuid = #{userUuid}::uuid",
        "   AND article_uuid IN",
        "<foreach collection='articleUuids' item='id' open='(' separator=',' close=')'>",
        "  #{id}::uuid",
        "</foreach>",
        "</script>"
    })
    List<UUID> findLikedArticleUuidsByUser(@Param("userUuid") UUID userUuid,
                                           @Param("articleUuids") List<UUID> articleUuids);
}
