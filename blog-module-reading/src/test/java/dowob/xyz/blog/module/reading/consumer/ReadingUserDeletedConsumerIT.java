package dowob.xyz.blog.module.reading.consumer;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.reading.support.ReadingCleanupITSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ReadingUserDeletedConsumer} 整合測試（真實 Postgres＋Redis；直接呼叫處理方法，不需要真 RabbitMQ）。
 *
 * <p>驗證 DATA-02 的 reading 面：使用者刪帳後，其收藏、劃線、閱讀進度（含 Redis 快取與待 flush 標記）被清除，
 * 他人資料不受影響；<b>按讚保留</b>（已無個資，刪除會讓 {@code articles.like_count} 與實際按讚數不符）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ReadingUserDeletedConsumer 整合測試")
class ReadingUserDeletedConsumerIT extends ReadingCleanupITSupport {

    @Autowired private ReadingUserDeletedConsumer consumer;
    @Autowired private StringRedisTemplate redisTemplate;

    private final UUID deletedUser = UUID.randomUUID();
    private final UUID bystander = UUID.randomUUID();

    private static UserDeletedEvent eventOf(UUID userUuid) {
        return new UserDeletedEvent(UUID.randomUUID(), userUuid, Instant.now());
    }

    /** 清掉可能遺留的失敗觸發器與 Redis 測試資料。 */
    @AfterEach
    void cleanUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_progress_delete_u ON user_reading_progress");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_progress_delete_u()");
        redisTemplate.delete(redisTemplate.keys("reading:progress:*"));
        redisTemplate.delete(RedisKeyConstant.READING_DIRTY_KEY);
    }

    private int countOf(String table, UUID userUuid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_uuid = ?", Integer.class, userUuid);
    }

    private void putProgressCache(UUID userUuid, UUID articleUuid, boolean dirty) {
        String member = userUuid + ":" + articleUuid;
        redisTemplate.opsForHash().put(RedisKeyConstant.READING_PROGRESS_PREFIX + member, "progress", "0.4");
        if (dirty) {
            redisTemplate.opsForSet().add(RedisKeyConstant.READING_DIRTY_KEY, member);
        }
    }

    @Test
    @DisplayName("處理事件：該使用者的收藏、劃線、閱讀進度全部消失，他人的保留")
    void onUserDeleted_removesOnlyThatUsersBookmarksHighlightsAndProgress() throws Exception {
        Article a1 = createArticle(ArticleStatus.PUBLISHED);
        Article a2 = createArticle(ArticleStatus.PUBLISHED);
        insertBookmark(deletedUser, a1.getUuid());
        insertBookmark(deletedUser, a2.getUuid());
        insertHighlight(deletedUser, a1.getUuid());
        insertProgress(deletedUser, a1.getUuid());
        insertBookmark(bystander, a1.getUuid());
        insertHighlight(bystander, a1.getUuid());
        insertProgress(bystander, a1.getUuid());

        consumer.onUserDeleted(eventOf(deletedUser));

        assertThat(countOf("user_bookmarks", deletedUser)).isZero();
        assertThat(countOf("user_highlights", deletedUser)).isZero();
        assertThat(countOf("user_reading_progress", deletedUser)).isZero();
        assertThat(countOf("user_bookmarks", bystander)).isEqualTo(1);
        assertThat(countOf("user_highlights", bystander)).isEqualTo(1);
        assertThat(countOf("user_reading_progress", bystander)).isEqualTo(1);
    }

    @Test
    @DisplayName("按讚保留：使用者的按讚紀錄與文章 like_count 都不變（決策：已無個資，刪除會讓計數漂移）")
    void onUserDeleted_keepsLikesAndArticleLikeCount() throws Exception {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        jdbc.update("UPDATE articles SET like_count = 2 WHERE uuid = ?", article.getUuid());
        insertLike(deletedUser, article.getUuid());
        insertLike(bystander, article.getUuid());

        consumer.onUserDeleted(eventOf(deletedUser));

        assertThat(countOf("user_article_likes", deletedUser)).isEqualTo(1);
        assertThat(countLikes(article.getUuid())).isEqualTo(2);
        assertThat(likeCountOf(article.getUuid())).isEqualTo(2);
    }

    @Test
    @DisplayName("Redis：該使用者的閱讀進度 hash 與待 flush 的 dirty 成員被清除，他人的保留")
    void onUserDeleted_clearsRedisProgressCacheAndDirtyMembers() throws Exception {
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        putProgressCache(deletedUser, a1, true);
        putProgressCache(deletedUser, a2, false);
        putProgressCache(bystander, a1, true);

        consumer.onUserDeleted(eventOf(deletedUser));

        assertThat(redisTemplate.hasKey(RedisKeyConstant.READING_PROGRESS_PREFIX + deletedUser + ":" + a1)).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeyConstant.READING_PROGRESS_PREFIX + deletedUser + ":" + a2)).isFalse();
        assertThat(redisTemplate.opsForSet().members(RedisKeyConstant.READING_DIRTY_KEY))
                .containsExactly(bystander + ":" + a1);
        assertThat(redisTemplate.hasKey(RedisKeyConstant.READING_PROGRESS_PREFIX + bystander + ":" + a1)).isTrue();
    }

    @Test
    @DisplayName("閱讀進度表刪除失敗：收藏與劃線的刪除一併回滾，並讓例外往外拋（交由容器重試）")
    void onUserDeleted_progressTableFails_rollsBackEarlierTables() throws Exception {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        insertBookmark(deletedUser, article.getUuid());
        insertHighlight(deletedUser, article.getUuid());
        insertProgress(deletedUser, article.getUuid());
        jdbc.execute("CREATE FUNCTION fail_progress_delete_u() RETURNS trigger AS $$ "
                + "BEGIN RAISE EXCEPTION 'progress delete blocked by test'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER trg_fail_progress_delete_u BEFORE DELETE ON user_reading_progress "
                + "FOR EACH ROW EXECUTE FUNCTION fail_progress_delete_u()");

        assertThatThrownBy(() -> consumer.onUserDeleted(eventOf(deletedUser))).isInstanceOf(RuntimeException.class);

        assertThat(countOf("user_bookmarks", deletedUser)).as("收藏的刪除已隨交易回滾").isEqualTo(1);
        assertThat(countOf("user_highlights", deletedUser)).as("劃線的刪除已隨交易回滾").isEqualTo(1);
        assertThat(countOf("user_reading_progress", deletedUser)).isEqualTo(1);
    }

    @Test
    @DisplayName("同一事件處理兩次：不出錯，第二次為 no-op，他人資料不受影響")
    void onUserDeleted_sameEventTwice_isIdempotent() throws Exception {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        insertBookmark(deletedUser, article.getUuid());
        insertBookmark(bystander, article.getUuid());
        UserDeletedEvent event = eventOf(deletedUser);

        consumer.onUserDeleted(event);
        consumer.onUserDeleted(event);

        assertThat(countOf("user_bookmarks", deletedUser)).isZero();
        assertThat(countOf("user_bookmarks", bystander)).isEqualTo(1);
    }

    @Test
    @DisplayName("缺 userUuid 的舊 payload：不刪除任何資料，仍正常返回")
    void onUserDeleted_nullUserUuid_removesNothing() throws Exception {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        insertBookmark(deletedUser, article.getUuid());

        consumer.onUserDeleted(eventOf(null));

        assertThat(countOf("user_bookmarks", deletedUser)).isEqualTo(1);
    }
}
