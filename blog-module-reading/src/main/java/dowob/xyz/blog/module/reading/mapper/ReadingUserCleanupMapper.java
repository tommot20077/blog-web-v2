package dowob.xyz.blog.module.reading.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

/**
 * 使用者刪除帳號（DATA-02）連動用的 MyBatis Mapper：供 {@code ReadingUserDeletedConsumer}
 * 清除某位使用者在 reading 模組持有的個人資料。
 *
 * <p>涵蓋 {@code user_bookmarks}、{@code user_highlights}、{@code user_reading_progress}
 * （V26／V27 起皆以 {@code user_uuid} 識別使用者、無 FK）。<b>刻意不含 {@code user_article_likes}</b>：
 * 按讚紀錄已無個資（只剩已匿名化使用者的 uuid），刪除它會讓 {@code articles.like_count} 與實際按讚數不一致，
 * 還得額外對每篇文章遞減計數（併發、漂移風險）；保留則計數維持正確，與「保留已發布文章」的決策一致。</p>
 *
 * <p>每個方法都是單一 SQL 敘述，本身即原子，且天然冪等；三張表的 {@code user_uuid} 都有以其開頭的索引。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface ReadingUserCleanupMapper {

    /**
     * 刪除某位使用者的所有收藏。天然冪等：已無收藏時刪除 0 筆，不會出錯。
     *
     * @param userUuid 使用者公開 UUID
     * @return 刪除的收藏筆數
     */
    @Delete("DELETE FROM user_bookmarks WHERE user_uuid = #{userUuid}::uuid")
    int deleteBookmarksByUserUuid(@Param("userUuid") UUID userUuid);

    /**
     * 刪除某位使用者的所有劃線（含筆記）。天然冪等：已無劃線時刪除 0 筆，不會出錯。
     *
     * @param userUuid 使用者公開 UUID
     * @return 刪除的劃線筆數
     */
    @Delete("DELETE FROM user_highlights WHERE user_uuid = #{userUuid}::uuid")
    int deleteHighlightsByUserUuid(@Param("userUuid") UUID userUuid);

    /**
     * 刪除某位使用者的所有閱讀進度。天然冪等：已無進度時刪除 0 筆，不會出錯。
     *
     * @param userUuid 使用者公開 UUID
     * @return 刪除的閱讀進度筆數
     */
    @Delete("DELETE FROM user_reading_progress WHERE user_uuid = #{userUuid}::uuid")
    int deleteProgressByUserUuid(@Param("userUuid") UUID userUuid);
}
