package dowob.xyz.blog.module.series.consumer;

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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @InjectMocks private SeriesArticleDeletedConsumer consumer;

    private ArticleDeletedEvent event(Long seriesId) {
        return new ArticleDeletedEvent(
            UUID.randomUUID(), 100L, UUID.randomUUID(), 1L,
            seriesId, List.of(), List.of(), Instant.now()
        );
    }

    @Test
    void onArticleDeleted_seriesIdNull_noOp() {
        ArticleDeletedEvent ev = event(null);

        consumer.onArticleDeleted(ev);

        verify(idempotencyService, never()).markProcessed(any(), any());
        verify(seriesMapper, never()).decrementArticleCount(any());
    }

    @Test
    void onArticleDeleted_firstTime_decrementsCount() {
        ArticleDeletedEvent ev = event(200L);
        when(idempotencyService.markProcessed(eq(ev.eventId()),
                eq(SeriesArticleDeletedConsumer.CONSUMER_NAME))).thenReturn(true);

        consumer.onArticleDeleted(ev);

        verify(seriesMapper, times(1)).decrementArticleCount(200L);
    }

    @Test
    void onArticleDeleted_replayed_skipsDecrement() {
        ArticleDeletedEvent ev = event(200L);
        when(idempotencyService.markProcessed(eq(ev.eventId()),
                eq(SeriesArticleDeletedConsumer.CONSUMER_NAME))).thenReturn(false);

        consumer.onArticleDeleted(ev);

        verify(seriesMapper, never()).decrementArticleCount(any());
    }

    @Test
    void onArticleDeleted_decrementThrows_reThrowToDLQ() {
        ArticleDeletedEvent ev = event(200L);
        when(idempotencyService.markProcessed(any(), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
            .when(seriesMapper).decrementArticleCount(200L);

        assertThatThrownBy(() -> consumer.onArticleDeleted(ev))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("DB down");
    }
}
