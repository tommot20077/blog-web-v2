package dowob.xyz.blog.module.tag.consumer;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
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

    @Mock private ArticleTagRepository articleTagRepository;

    private TagArticleDeletedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TagArticleDeletedConsumer(articleTagRepository, new ConsumerExecutor());
    }

    private static ArticleDeletedEvent event(UUID articleUuid) {
        return new ArticleDeletedEvent(UUID.randomUUID(), articleUuid, List.of(), List.of(), Instant.now(), null, null);
    }

    @Test
    @DisplayName("收到事件：依 articleUuid 刪除該文章的標籤關聯並正常返回")
    void onArticleDeleted_deletesArticleTagsAndAcks() throws Exception {
        UUID articleUuid = UUID.randomUUID();

        consumer.onArticleDeleted(event(articleUuid));

        verify(articleTagRepository).deleteByArticleId(articleUuid);
    }

    @Test
    @DisplayName("舊 payload 缺 articleUuid：不刪除、仍正常返回（重送也不會變好）")
    void onArticleDeleted_nullArticleUuid_doesNotDeleteAndAcks() throws Exception {
        consumer.onArticleDeleted(event(null));

        verifyNoInteractions(articleTagRepository);
    }

    @Test
    @DisplayName("刪除拋例外：例外往外拋（交由容器重試，耗盡進 DLQ）")
    void onArticleDeleted_deleteThrows_nacksWithoutRequeue() throws Exception {
        UUID articleUuid = UUID.randomUUID();
        doThrow(new RuntimeException("DB down")).when(articleTagRepository).deleteByArticleId(articleUuid);

        assertThatThrownBy(() -> consumer.onArticleDeleted(event(articleUuid))).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("監聽 tag.article-deleted queue")
    void onArticleDeleted_listensToArticleDeletedQueue() throws Exception {
        Method method = TagArticleDeletedConsumer.class.getMethod(
                "onArticleDeleted", ArticleDeletedEvent.class);

        RabbitListener listener = method.getAnnotation(RabbitListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.queues()).containsExactly(TagRabbitMqConfig.QUEUE_TAG_ARTICLE_DELETED);
        assertThat(TagRabbitMqConfig.QUEUE_TAG_ARTICLE_DELETED).isEqualTo("tag.article-deleted");
    }
}
