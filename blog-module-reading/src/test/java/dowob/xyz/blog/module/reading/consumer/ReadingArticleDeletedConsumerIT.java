package dowob.xyz.blog.module.reading.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.reading.support.ReadingCleanupITSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ReadingArticleDeletedConsumer} 整合測試（真實 Postgres；直接呼叫處理方法，不需要真 RabbitMQ）。
 *
 * <p>V26 起 {@code user_article_likes}、{@code user_bookmarks} 沒有指向 {@code articles} 的 FK，
 * consumer 是「文章刪除 → 按讚／收藏刪除」的唯一即時機制（每日校正排程為事件遺失時的第二道保險）。
 * 測試中文章仍在、僅收到事件，以隔離出 consumer 本身的效果。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ReadingArticleDeletedConsumer 整合測試")
class ReadingArticleDeletedConsumerIT extends ReadingCleanupITSupport {

    private static final long DELIVERY_TAG = 7L;

    @Autowired private ReadingArticleDeletedConsumer consumer;

    private static ArticleDeletedEvent eventOf(UUID articleUuid) {
        return new ArticleDeletedEvent(UUID.randomUUID(), articleUuid, List.of(), List.of(), Instant.now(), null, null);
    }

    /** 確保即使測試中途失敗也不遺留會讓後續測試失敗的觸發器。 */
    @AfterEach
    void dropFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_bookmark_delete ON user_bookmarks");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_bookmark_delete()");
        jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_progress_delete ON user_reading_progress");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_progress_delete()");
    }

    @Test
    @DisplayName("處理事件：該文章的按讚與收藏全部消失，其他文章的保留")
    void onArticleDeleted_removesOnlyThatArticlesLikesAndBookmarks() throws Exception {
        Article target = createArticle(ArticleStatus.PUBLISHED);
        Article other = createArticle(ArticleStatus.PUBLISHED);
        insertLike(USER_UUID, target.getUuid());
        insertLike(OTHER_USER_UUID, target.getUuid());
        insertBookmark(USER_UUID, target.getUuid());
        insertLike(USER_UUID, other.getUuid());
        insertBookmark(USER_UUID, other.getUuid());
        Channel channel = Mockito.mock(Channel.class);

        consumer.onArticleDeleted(eventOf(target.getUuid()), channel, DELIVERY_TAG);

        assertThat(countLikes(target.getUuid())).isZero();
        assertThat(countBookmarks(target.getUuid())).isZero();
        assertThat(countLikes(other.getUuid())).isEqualTo(1);
        assertThat(countBookmarks(other.getUuid())).isEqualTo(1);
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("處理事件：該文章的劃線與閱讀進度也一併消失，其他文章的保留")
    void onArticleDeleted_removesOnlyThatArticlesHighlightsAndProgress() throws Exception {
        Article target = createArticle(ArticleStatus.PUBLISHED);
        Article other = createArticle(ArticleStatus.PUBLISHED);
        insertHighlight(USER_UUID, target.getUuid());
        insertHighlight(OTHER_USER_UUID, target.getUuid());
        insertProgress(USER_UUID, target.getUuid());
        insertProgress(OTHER_USER_UUID, target.getUuid());
        insertHighlight(USER_UUID, other.getUuid());
        insertProgress(USER_UUID, other.getUuid());
        Channel channel = Mockito.mock(Channel.class);

        consumer.onArticleDeleted(eventOf(target.getUuid()), channel, DELIVERY_TAG);

        assertThat(countHighlights(target.getUuid())).isZero();
        assertThat(countProgress(target.getUuid())).isZero();
        assertThat(countHighlights(other.getUuid())).isEqualTo(1);
        assertThat(countProgress(other.getUuid())).isEqualTo(1);
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("閱讀進度表刪除失敗：前三張表（含劃線）的刪除一併回滾，並 nack 送往 DLQ")
    void onArticleDeleted_progressTableFails_rollsBackEarlierTables() throws Exception {
        Article target = createArticle(ArticleStatus.PUBLISHED);
        insertLike(USER_UUID, target.getUuid());
        insertBookmark(USER_UUID, target.getUuid());
        insertHighlight(USER_UUID, target.getUuid());
        insertProgress(USER_UUID, target.getUuid());
        jdbc.execute("CREATE FUNCTION fail_progress_delete() RETURNS trigger AS $$ "
                + "BEGIN RAISE EXCEPTION 'progress delete blocked by test'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER trg_fail_progress_delete BEFORE DELETE ON user_reading_progress "
                + "FOR EACH ROW EXECUTE FUNCTION fail_progress_delete()");
        Channel channel = Mockito.mock(Channel.class);

        consumer.onArticleDeleted(eventOf(target.getUuid()), channel, DELIVERY_TAG);

        assertThat(countLikes(target.getUuid())).isEqualTo(1);
        assertThat(countBookmarks(target.getUuid())).isEqualTo(1);
        assertThat(countHighlights(target.getUuid())).as("劃線的刪除已隨交易回滾").isEqualTo(1);
        assertThat(countProgress(target.getUuid())).isEqualTo(1);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, times(1)).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    @DisplayName("同一事件處理兩次：不出錯，第二次為 no-op，其他資料不受影響")
    void onArticleDeleted_sameEventTwice_isIdempotent() throws Exception {
        Article target = createArticle(ArticleStatus.PUBLISHED);
        Article other = createArticle(ArticleStatus.PUBLISHED);
        insertLike(USER_UUID, target.getUuid());
        insertBookmark(USER_UUID, target.getUuid());
        insertLike(USER_UUID, other.getUuid());
        ArticleDeletedEvent event = eventOf(target.getUuid());
        Channel channel = Mockito.mock(Channel.class);

        consumer.onArticleDeleted(event, channel, 1L);
        consumer.onArticleDeleted(event, channel, 2L);

        assertThat(countLikes(target.getUuid())).isZero();
        assertThat(countBookmarks(target.getUuid())).isZero();
        assertThat(countLikes(other.getUuid())).isEqualTo(1);
        verify(channel, times(1)).basicAck(1L, false);
        verify(channel, times(1)).basicAck(2L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("缺 articleUuid 的舊 payload：不刪除任何資料，仍 ack")
    void onArticleDeleted_nullArticleUuid_removesNothingAndAcks() throws Exception {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        insertLike(USER_UUID, article.getUuid());
        insertBookmark(USER_UUID, article.getUuid());
        Channel channel = Mockito.mock(Channel.class);

        consumer.onArticleDeleted(eventOf(null), channel, DELIVERY_TAG);

        assertThat(countLikes(article.getUuid())).isEqualTo(1);
        assertThat(countBookmarks(article.getUuid())).isEqualTo(1);
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("兩張表在同一個交易內刪除：收藏刪除失敗時，按讚的刪除一併回滾，並 nack 送往 DLQ")
    void onArticleDeleted_secondTableFails_rollsBackFirstTableDeletion() throws Exception {
        Article target = createArticle(ArticleStatus.PUBLISHED);
        insertLike(USER_UUID, target.getUuid());
        insertBookmark(USER_UUID, target.getUuid());
        jdbc.execute("CREATE FUNCTION fail_bookmark_delete() RETURNS trigger AS $$ "
                + "BEGIN RAISE EXCEPTION 'bookmark delete blocked by test'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER trg_fail_bookmark_delete BEFORE DELETE ON user_bookmarks "
                + "FOR EACH ROW EXECUTE FUNCTION fail_bookmark_delete()");
        Channel channel = Mockito.mock(Channel.class);

        consumer.onArticleDeleted(eventOf(target.getUuid()), channel, DELIVERY_TAG);

        assertThat(countLikes(target.getUuid())).as("按讚的刪除已隨交易回滾").isEqualTo(1);
        assertThat(countBookmarks(target.getUuid())).isEqualTo(1);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, times(1)).basicNack(DELIVERY_TAG, false, false);
    }
}
