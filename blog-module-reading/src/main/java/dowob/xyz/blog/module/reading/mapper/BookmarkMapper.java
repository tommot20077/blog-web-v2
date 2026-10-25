package dowob.xyz.blog.module.reading.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Bookmark MyBatis Mapper：收藏寫入（ON CONFLICT）與批次查詢。
 *
 * <p>V26 起使用者與文章一律以 UUID 識別。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Mapper
public interface BookmarkMapper {

    /**
     * 新增收藏；已收藏時什麼都不做（RACE-04）。
     *
     * <p>以 {@code UNIQUE (user_uuid, article_uuid)} 為衝突目標的 {@code ON CONFLICT DO NOTHING}：
     * 雙擊落敗者不會拋 {@code DataIntegrityViolationException}（不會把外層交易弄成 rollback-only、也不會 500）。
     * 收藏沒有反正規化計數，因此不需要依回傳值決定後續動作。</p>
     *
     * @param userUuid    收藏者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @param createdAt   建立時間，由 service 以 JVM 的 {@code LocalDateTime.now()} 傳入（與改造前的時間來源一致，
     *                    避免 JVM 與 DB 時區不同時，新舊資料的 {@code timestamp without time zone} 混在一起排序錯位）
     * @return 實際新增的列數：1 = 這次才收藏，0 = 先前已收藏（或並發中對方先寫入）
     */
    @Insert("INSERT INTO user_bookmarks (user_uuid, article_uuid, created_at) "
            + "VALUES (#{userUuid}::uuid, #{articleUuid}::uuid, #{createdAt}) "
            + "ON CONFLICT (user_uuid, article_uuid) DO NOTHING")
    int insertIgnoreConflict(@Param("userUuid") UUID userUuid, @Param("articleUuid") UUID articleUuid,
                             @Param("createdAt") LocalDateTime createdAt);

    /**
     * 批次查詢「使用者收藏過哪些文章」。
     *
     * @param userUuid     使用者公開 UUID
     * @param articleUuids 要查詢的文章公開 UUID 集合
     * @return 已收藏的文章 UUID 集合
     */
    @Select({
        "<script>",
        "SELECT article_uuid FROM user_bookmarks",
        " WHERE user_uuid = #{userUuid}::uuid",
        "   AND article_uuid IN",
        "<foreach collection='articleUuids' item='id' open='(' separator=',' close=')'>",
        "  #{id}::uuid",
        "</foreach>",
        "</script>"
    })
    List<UUID> findBookmarkedArticleUuidsByUser(@Param("userUuid") UUID userUuid,
                                                @Param("articleUuids") List<UUID> articleUuids);

    /**
     * 我的全部收藏文章 UUID（最新優先，不分頁）。
     *
     * <p>供 {@code BookmarkQueryService} 做「先過濾可見性、再分頁」用。
     * 原本 SQL 分頁 ＋ 應用層過濾會使 total 高估、每頁筆數不一致
     * （前端以 {@code pages} 畫分頁器，高估會產生空尾頁）。</p>
     *
     * <p>{@code created_at} 相同時以 {@code id} 遞減決定先後，使順序可重現。</p>
     *
     * <p><b>傳輸量界限</b>：與該使用者的收藏數 B 成正比，payload 僅 uuid（16 bytes/筆）。
     * 個人部落格量級為數十至數百。<b>重評門檻：任一使用者 B &gt; 5,000。</b></p>
     *
     * @param userUuid 使用者公開 UUID
     * @return 全部收藏文章 UUID，依收藏時間新到舊
     */
    @Select("""
            SELECT article_uuid FROM user_bookmarks
             WHERE user_uuid = #{userUuid}::uuid
             ORDER BY created_at DESC, id DESC
            """)
    List<UUID> findAllMyBookmarkedArticleUuids(@Param("userUuid") UUID userUuid);
}
