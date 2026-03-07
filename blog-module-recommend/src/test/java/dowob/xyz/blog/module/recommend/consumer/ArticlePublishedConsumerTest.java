package dowob.xyz.blog.module.recommend.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArticlePublishedConsumer 單元測試
 *
 * <p>
 * 驗證處理文章發布事件時：<br>
 * 1. 正常情境：清除快取並呼叫 basicAck；<br>
 * 2. ACK 失敗情境：IOException 時呼叫 basicNack 防止訊息卡住。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticlePublishedConsumer 單元測試")
class ArticlePublishedConsumerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private Channel channel;

    private ArticlePublishedConsumer consumer;

    private static final UUID ARTICLE_UUID = UUID.randomUUID();
    private static final long DELIVERY_TAG = 42L;

    /**
     * 建立受測物件
     */
    @BeforeEach
    void setUp() {
        consumer = new ArticlePublishedConsumer(stringRedisTemplate);
    }

    private ArticlePublishedEvent event() {
        return new ArticlePublishedEvent(
                ARTICLE_UUID, 1L, "Test Article", LocalDateTime.now(),
                "test-slug", "Test summary", "content text",
                "testuser", "Test User", List.of());
    }

    @Test
    @DisplayName("正常情境：清除快取並呼叫 basicAck")
    void handleArticlePublished_正常_清除快取並ACK() throws IOException {
        consumer.handleArticlePublished(event(), channel, DELIVERY_TAG);

        verify(stringRedisTemplate).delete("recommend:related:" + ARTICLE_UUID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("ACK 失敗時呼叫 basicNack 避免訊息卡住")
    void handleArticlePublished_ACK失敗_呼叫basicNack() throws IOException {
        doThrow(new IOException("ACK failed")).when(channel).basicAck(DELIVERY_TAG, false);

        consumer.handleArticlePublished(event(), channel, DELIVERY_TAG);

        verify(channel).basicNack(eq(DELIVERY_TAG), eq(false), eq(false));
    }

    @Test
    @DisplayName("Redis 拋出 RuntimeException 時仍應呼叫 basicNack")
    void handleArticlePublished_Redis拋出RuntimeException_呼叫basicNack() throws IOException {
        org.springframework.data.redis.RedisConnectionFailureException redisEx =
                new org.springframework.data.redis.RedisConnectionFailureException("連線失敗");
        doThrow(redisEx).when(stringRedisTemplate).delete(anyString());

        consumer.handleArticlePublished(event(), channel, DELIVERY_TAG);

        verify(channel).basicNack(eq(DELIVERY_TAG), eq(false), eq(false));
    }
}
