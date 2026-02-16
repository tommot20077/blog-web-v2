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
}
