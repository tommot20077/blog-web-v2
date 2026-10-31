package dowob.xyz.blog.module.tag.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.tag.config.TagRabbitMqConfig;
import dowob.xyz.blog.module.tag.repository.ArticleTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link TagArticleDeletedConsumer} 單元測試。{@link ConsumerExecutor} 使用真實實例，ack／nack 走真實路徑。
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TagArticleDeletedConsumer 單元測試")
class TagArticleDeletedConsumerTest {

    private static final long DELIVERY_TAG = 42L;

    @Mock private ArticleTagRepository articleTagRepository;
    @Mock private Channel channel;

    private TagArticleDeletedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TagArticleDeletedConsumer(articleTagRepository, new ConsumerExecutor());
    }

    private static ArticleDeletedEvent event(UUID articleUuid) {
        return new ArticleDeletedEvent(UUID.randomUUID(), articleUuid, List.of(), List.of(), Instant.now(), null, null);
    }

    @Test
    @DisplayName("收到事件：依 articleUuid 刪除該文章的標籤關聯並 ack")
    void onArticleDeleted_deletesArticleTagsAndAcks() throws Exception {
        UUID articleUuid = UUID.randomUUID();

        consumer.onArticleDeleted(event(articleUuid), channel, DELIVERY_TAG);

        verify(articleTagRepository).deleteByArticleId(articleUuid);
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("舊 payload 缺 articleUuid：不刪除、仍 ack（重送也不會變好）")
    void onArticleDeleted_nullArticleUuid_doesNotDeleteAndAcks() throws Exception {
        consumer.onArticleDeleted(event(null), channel, DELIVERY_TAG);

        verifyNoInteractions(articleTagRepository);
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("刪除拋例外：nack（requeue=false）送往 DLQ，不 ack")
    void onArticleDeleted_deleteThrows_nacksWithoutRequeue() throws Exception {
        UUID articleUuid = UUID.randomUUID();
        doThrow(new RuntimeException("DB down")).when(articleTagRepository).deleteByArticleId(articleUuid);

        consumer.onArticleDeleted(event(articleUuid), channel, DELIVERY_TAG);

        verify(channel, times(1)).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("監聽 tag.article-deleted queue")
    void onArticleDeleted_listensToArticleDeletedQueue() throws Exception {
        Method method = TagArticleDeletedConsumer.class.getMethod(
                "onArticleDeleted", ArticleDeletedEvent.class, Channel.class, long.class);

        RabbitListener listener = method.getAnnotation(RabbitListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.queues()).containsExactly(TagRabbitMqConfig.QUEUE_TAG_ARTICLE_DELETED);
        assertThat(TagRabbitMqConfig.QUEUE_TAG_ARTICLE_DELETED).isEqualTo("tag.article-deleted");
    }
}
