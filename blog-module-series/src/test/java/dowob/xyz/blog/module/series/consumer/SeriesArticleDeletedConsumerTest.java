package dowob.xyz.blog.module.series.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.module.article.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeriesArticleDeletedConsumerTest {

    @Mock private IdempotencyService idempotencyService;
    @Mock private SeriesMapper seriesMapper;
    @Mock private Channel channel;
    @InjectMocks private SeriesArticleDeletedConsumer consumer;

    private static final long DELIVERY_TAG = 42L;

    private ArticleDeletedEvent event(Long seriesId) {
        return new ArticleDeletedEvent(
            UUID.randomUUID(), 100L, UUID.randomUUID(), 1L,
            seriesId, List.of(), List.of(), Instant.now()
        );
    }

    @Test
    void onArticleDeleted_seriesIdNull_acksAndNoOp() throws Exception {
        ArticleDeletedEvent ev = event(null);

        consumer.onArticleDeleted(ev, channel, DELIVERY_TAG);

        verify(idempotencyService, never()).markProcessed(any(), any());
        verify(seriesMapper, never()).decrementArticleCount(any());
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void onArticleDeleted_firstTime_decrementsCountAndAcks() throws Exception {
        ArticleDeletedEvent ev = event(200L);
        when(idempotencyService.markProcessed(eq(ev.eventId()),
                eq(SeriesArticleDeletedConsumer.CONSUMER_NAME))).thenReturn(true);

        consumer.onArticleDeleted(ev, channel, DELIVERY_TAG);

        verify(seriesMapper, times(1)).decrementArticleCount(200L);
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void onArticleDeleted_replayed_skipsDecrementAndAcks() throws Exception {
        ArticleDeletedEvent ev = event(200L);
        when(idempotencyService.markProcessed(eq(ev.eventId()),
                eq(SeriesArticleDeletedConsumer.CONSUMER_NAME))).thenReturn(false);

        consumer.onArticleDeleted(ev, channel, DELIVERY_TAG);

        verify(seriesMapper, never()).decrementArticleCount(any());
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void onArticleDeleted_decrementThrows_nacksTowardsDlq() throws Exception {
        ArticleDeletedEvent ev = event(200L);
        when(idempotencyService.markProcessed(any(), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
            .when(seriesMapper).decrementArticleCount(200L);

        consumer.onArticleDeleted(ev, channel, DELIVERY_TAG);

        verify(channel, never()).basicAck(any(long.class), any(boolean.class));
        verify(channel, times(1)).basicNack(DELIVERY_TAG, false, false);
    }
}
