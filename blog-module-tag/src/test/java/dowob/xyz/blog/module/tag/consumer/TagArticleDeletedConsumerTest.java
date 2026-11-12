package dowob.xyz.blog.module.tag.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.tag.config.TagRabbitMqConfig;
import dowob.xyz.blog.module.tag.repository.ArticleTagRepository;
import dowob.xyz.blog.module.tag.service.TagUsageRecalculator;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
    @Mock private TagUsageRecalculator recalculator;

    private TagArticleDeletedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TagArticleDeletedConsumer(articleTagRepository, recalculator, new ConsumerExecutor());
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
    @DisplayName("舊 payload 缺 articleUuid：不刪除、不重算、仍正常返回（重送也不會變好）")
    void onArticleDeleted_nullArticleUuid_doesNotDeleteAndAcks() throws Exception {
        consumer.onArticleDeleted(event(null));

        verifyNoInteractions(articleTagRepository, recalculator);
    }

    @Test
    @DisplayName("DATA-01：刪除關聯列之後，對該文章原本掛的標籤（article_tags 現況 ∪ 事件 tagIds）重算")
    void onArticleDeleted_recalculatesFormerTagsAfterDeletingLinks() throws Exception {
        UUID articleUuid = UUID.randomUUID();
        UUID fromTable = UUID.randomUUID();
        UUID fromEventOnly = UUID.randomUUID();
        when(articleTagRepository.findTagIdsByArticleId(articleUuid)).thenReturn(List.of(fromTable));
        ArticleDeletedEvent event = new ArticleDeletedEvent(UUID.randomUUID(), articleUuid, List.of(),
                List.of(fromTable, fromEventOnly), Instant.now(), null, null);

        consumer.onArticleDeleted(event);

        org.mockito.InOrder order = inOrder(articleTagRepository, recalculator);
        order.verify(articleTagRepository).findTagIdsByArticleId(articleUuid);
        order.verify(articleTagRepository).deleteByArticleId(articleUuid);
        org.mockito.ArgumentCaptor<java.util.Collection<UUID>> captor = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        order.verify(recalculator).recalculate(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(fromTable, fromEventOnly);
    }

    @Test
    @DisplayName("舊 payload 缺 tagIds（null）：仍以刪除前 article_tags 查到的標籤重算")
    void onArticleDeleted_nullEventTagIds_usesTagsFromTable() throws Exception {
        UUID articleUuid = UUID.randomUUID();
        UUID tagId = UUID.randomUUID();
        when(articleTagRepository.findTagIdsByArticleId(articleUuid)).thenReturn(List.of(tagId));
        ArticleDeletedEvent event = new ArticleDeletedEvent(UUID.randomUUID(), articleUuid, null, null,
                Instant.now(), null, null);

        consumer.onArticleDeleted(event);

        org.mockito.ArgumentCaptor<java.util.Collection<UUID>> captor = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(recalculator).recalculate(captor.capture());
        assertThat(captor.getValue()).containsExactly(tagId);
    }

    @Test
    @DisplayName("重算拋例外：例外往外拋（重送時 DELETE 為 0 筆，但事件 tagIds 仍能重算）")
    void onArticleDeleted_recalculateThrows_propagates() throws Exception {
        UUID articleUuid = UUID.randomUUID();
        UUID tagId = UUID.randomUUID();
        doThrow(new RuntimeException("DB down")).when(recalculator).recalculate(org.mockito.ArgumentMatchers.anyCollection());
        ArticleDeletedEvent event = new ArticleDeletedEvent(UUID.randomUUID(), articleUuid, List.of(),
                List.of(tagId), Instant.now(), null, null);

        assertThatThrownBy(() -> consumer.onArticleDeleted(event)).isInstanceOf(RuntimeException.class);
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
