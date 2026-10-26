package dowob.xyz.blog.module.article.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.event.SeriesDeletedEvent;
import dowob.xyz.blog.module.article.support.ArticleSeriesITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ArticleSeriesDeletedConsumer} 整合測試（真實 Postgres；直接呼叫處理方法，不需要真 RabbitMQ）。
 *
 * <p>V32 起 {@code articles.series_uuid} 沒有指向 {@code series} 的 FK，原本由 {@code ON DELETE SET NULL}
 * 完成的「series 刪除 → 文章脫離系列」改由本 consumer（即時）與每日校正排程負責。測試中 series 並不存在於 DB
 * （article 不依賴 series 模組），只模擬「收到刪除事件」，以隔離出 consumer 本身的效果。
 * 刪除語意不變：文章本身保留，只清空系列歸屬。與原本 FK 的 {@code ON DELETE SET NULL} 的差異（皆為有意）：
 * {@code series_position} 一併清空（系列已不存在，位置沒有意義）；{@code version} 遞增（V32 前過期的整筆 save 會被
 * FK 擋下，之後會把 ghost 歸屬靜默寫回，遞增後過期的 save 會撞樂觀鎖）；{@code updated_at} 不動（與改造前相同）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleSeriesDeletedConsumer 整合測試")
class ArticleSeriesDeletedConsumerIT extends ArticleSeriesITSupport {

    private static final long DELIVERY_TAG = 7L;

    @Autowired private ArticleSeriesDeletedConsumer consumer;

    private static SeriesDeletedEvent eventOf(UUID seriesUuid) {
        return new SeriesDeletedEvent(UUID.randomUUID(), seriesUuid, Instant.now());
    }

    @Test
    @DisplayName("處理事件：該系列所有成員文章的 series_uuid 與 series_position 清空，文章保留；其他系列與無系列文章不受影響")
    void onSeriesDeleted_clearsOnlyThatSeriesMembers_articlesRemain() throws Exception {
        UUID deletedSeries = UUID.randomUUID();
        UUID otherSeries = UUID.randomUUID();
        UUID member1 = insertArticle(deletedSeries, 1, "PUBLISHED");
        UUID member2 = insertArticle(deletedSeries, 2, "DRAFT");
        UUID otherMember = insertArticle(otherSeries, 5, "PUBLISHED");
        UUID loner = insertArticle(null, null, "PUBLISHED");
        Channel channel = Mockito.mock(Channel.class);

        consumer.onSeriesDeleted(eventOf(deletedSeries), channel, DELIVERY_TAG);

        assertThat(seriesUuidOf(member1)).isNull();
        assertThat(positionOf(member1)).isNull();
        assertThat(seriesUuidOf(member2)).isNull();
        assertThat(positionOf(member2)).isNull();
        assertThat(seriesUuidOf(otherMember)).isEqualTo(otherSeries);
        assertThat(positionOf(otherMember)).isEqualTo(5);
        assertThat(seriesUuidOf(loner)).isNull();
        assertThat(countArticles()).as("刪除語意不變：文章留存，只清空歸屬").isEqualTo(4);
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("清空歸屬遞增 version（過期的整筆 save 會撞樂觀鎖，不會把 ghost 歸屬寫回），但不更新 updated_at（與改造前相同）")
    void onSeriesDeleted_bumpsVersionButNotUpdatedAt() throws Exception {
        UUID series = UUID.randomUUID();
        LocalDateTime fixed = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        UUID member = insertArticleWithAudit(series, 3, 4, fixed);

        consumer.onSeriesDeleted(eventOf(series), Mockito.mock(Channel.class), DELIVERY_TAG);

        assertThat(seriesUuidOf(member)).isNull();
        assertThat(versionOf(member)).isEqualTo(5);
        assertThat(updatedAtOf(member)).isEqualTo(fixed);
    }

    @Test
    @DisplayName("同一事件重複處理：第二次沒有成員可清，version 不會再遞增")
    void onSeriesDeleted_replay_doesNotBumpVersionAgain() throws Exception {
        UUID series = UUID.randomUUID();
        UUID member = insertArticleWithAudit(series, 1, 4, LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        SeriesDeletedEvent event = eventOf(series);

        consumer.onSeriesDeleted(event, Mockito.mock(Channel.class), 1L);
        consumer.onSeriesDeleted(event, Mockito.mock(Channel.class), 2L);

        assertThat(versionOf(member)).isEqualTo(5);
    }

    @Test
    @DisplayName("同一事件處理兩次：不出錯，第二次為 no-op，其他資料不受影響")
    void onSeriesDeleted_sameEventTwice_isIdempotent() throws Exception {
        UUID series = UUID.randomUUID();
        UUID otherSeries = UUID.randomUUID();
        UUID member = insertArticle(series, 1, "PUBLISHED");
        UUID other = insertArticle(otherSeries, 2, "PUBLISHED");
        SeriesDeletedEvent event = eventOf(series);
        Channel channel = Mockito.mock(Channel.class);

        consumer.onSeriesDeleted(event, channel, 1L);
        consumer.onSeriesDeleted(event, channel, 2L);

        assertThat(seriesUuidOf(member)).isNull();
        assertThat(seriesUuidOf(other)).isEqualTo(otherSeries);
        verify(channel, times(1)).basicAck(1L, false);
        verify(channel, times(1)).basicAck(2L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("沒有任何成員文章的系列被刪除：不出錯、仍 ack")
    void onSeriesDeleted_seriesWithoutMembers_acksWithoutError() throws Exception {
        UUID unrelated = insertArticle(UUID.randomUUID(), 1, "PUBLISHED");
        Channel channel = Mockito.mock(Channel.class);

        consumer.onSeriesDeleted(eventOf(UUID.randomUUID()), channel, DELIVERY_TAG);

        assertThat(seriesUuidOf(unrelated)).isNotNull();
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("缺 seriesUuid 的 payload：不清空任何文章，仍 ack")
    void onSeriesDeleted_nullSeriesUuid_changesNothingAndAcks() throws Exception {
        UUID series = UUID.randomUUID();
        UUID member = insertArticle(series, 1, "PUBLISHED");
        Channel channel = Mockito.mock(Channel.class);

        consumer.onSeriesDeleted(eventOf(null), channel, DELIVERY_TAG);

        assertThat(seriesUuidOf(member)).isEqualTo(series);
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
    }
}
