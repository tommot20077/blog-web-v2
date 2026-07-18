package dowob.xyz.blog.module.article.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.module.article.event.ArticleViewedEvent;
import dowob.xyz.blog.module.article.service.ViewCountService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ViewCountConsumer} 單元測試
 *
 * <p>驗證 RabbitMQ 消費者正確呼叫計數服務並 ACK 消息，且對重送具冪等性。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ViewCountConsumer 單元測試")
class ViewCountConsumerTest {

    /** 瀏覽計數服務（Mock） */
    @Mock
    private ViewCountService viewCountService;

    /** 冪等處理服務（Mock） */
    @Mock
    private IdempotencyService idempotencyService;

    /** RabbitMQ Channel（Mock） */
    @Mock
    private Channel channel;

    /** 待測消費者 */
    @InjectMocks
    private ViewCountConsumer viewCountConsumer;

    /**
     * 情境：收到 ArticleViewedEvent → 增加計數並 ACK
     */
    @Test
    @DisplayName("handleArticleViewed：成功增加計數並發送 ACK")
    void handleArticleViewed_success_incrementsAndAcks() throws IOException {
        UUID uuid = UUID.randomUUID();
        ArticleViewedEvent event = new ArticleViewedEvent(UUID.randomUUID(), uuid, Instant.now());
        long deliveryTag = 42L;
        when(idempotencyService.markProcessed(event.eventId(), ViewCountConsumer.CONSUMER_NAME)).thenReturn(true);

        viewCountConsumer.handleArticleViewed(event, channel, deliveryTag);

        verify(viewCountService).incrementRedisViewCount(uuid);
        verify(channel).basicAck(deliveryTag, false);
    }

    /**
     * 情境：處理失敗（Redis 拋出例外）→ 呼叫 basicNack
     */
    @Test
    @DisplayName("handleArticleViewed：處理失敗時呼叫 basicNack")
    void handleArticleViewed_onFailure_callsBasicNack() throws IOException {
        UUID uuid = UUID.randomUUID();
        ArticleViewedEvent event = new ArticleViewedEvent(UUID.randomUUID(), uuid, Instant.now());
        long deliveryTag = 99L;
        when(idempotencyService.markProcessed(event.eventId(), ViewCountConsumer.CONSUMER_NAME)).thenReturn(true);
        doThrow(new RuntimeException("Redis error")).when(viewCountService).incrementRedisViewCount(uuid);

        viewCountConsumer.handleArticleViewed(event, channel, deliveryTag);

        verify(channel).basicNack(eq(deliveryTag), eq(false), eq(false));
    }

    /**
     * 情境：同一事件重送兩次（相同 eventId）→ 計數只增加一次，兩次皆 ACK。
     *
     * <p>at-least-once 語意下 broker 可能重送；計數為非冪等 +1 操作，
     * 若不去重則會多算。以 IdempotencyService 對 eventId 去重。</p>
     */
    @Test
    @DisplayName("handleArticleViewed：同一事件重送 → 計數只加一次（冪等）")
    void handleArticleViewed_duplicateEvent_incrementsOnlyOnce() throws IOException {
        UUID uuid = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        ArticleViewedEvent event = new ArticleViewedEvent(eventId, uuid, Instant.now());
        // 第一次 markProcessed 回 true（首次處理），第二次回 false（已處理過要 skip）
        when(idempotencyService.markProcessed(eventId, ViewCountConsumer.CONSUMER_NAME))
                .thenReturn(true, false);

        viewCountConsumer.handleArticleViewed(event, channel, 1L);
        viewCountConsumer.handleArticleViewed(event, channel, 2L);

        verify(viewCountService, times(1)).incrementRedisViewCount(uuid);
        verify(channel).basicAck(1L, false);
        verify(channel).basicAck(2L, false);
    }

    /**
     * 情境：舊訊息（eventId 為 null）→ 照舊處理，不呼叫 idempotencyService。
     *
     * <p>上線瞬間佇列中的舊 payload 無 eventId，反序列化為 null；
     * 此路徑須直接處理,不可因 null dedup key 進 DLQ。</p>
     */
    @Test
    @DisplayName("handleArticleViewed：eventId 為 null（舊訊息）→ 照舊處理不做去重")
    void handleArticleViewed_nullEventId_processesWithoutIdempotency() throws IOException {
        UUID uuid = UUID.randomUUID();
        ArticleViewedEvent event = new ArticleViewedEvent(null, uuid, Instant.now());

        viewCountConsumer.handleArticleViewed(event, channel, 7L);

        verify(viewCountService).incrementRedisViewCount(uuid);
        verify(channel).basicAck(7L, false);
        verify(idempotencyService, never()).markProcessed(any(), any());
    }
}
