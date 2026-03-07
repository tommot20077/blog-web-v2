package dowob.xyz.blog.module.article.consumer;

import com.rabbitmq.client.Channel;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link ViewCountConsumer} 單元測試
 *
 * <p>驗證 RabbitMQ 消費者正確呼叫計數服務並 ACK 消息。</p>
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
        ArticleViewedEvent event = new ArticleViewedEvent(uuid, Instant.now());
        long deliveryTag = 42L;

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
        ArticleViewedEvent event = new ArticleViewedEvent(uuid, Instant.now());
        long deliveryTag = 99L;

        doThrow(new RuntimeException("Redis error")).when(viewCountService).incrementRedisViewCount(uuid);

        viewCountConsumer.handleArticleViewed(event, channel, deliveryTag);

        verify(channel).basicNack(eq(deliveryTag), eq(false), eq(false));
    }
}
