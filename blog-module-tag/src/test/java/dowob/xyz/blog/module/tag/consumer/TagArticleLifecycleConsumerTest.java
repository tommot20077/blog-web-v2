package dowob.xyz.blog.module.tag.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleArchivedEvent;
import dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleUpdatedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.tag.config.TagRabbitMqConfig;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link TagArticleLifecycleConsumer} 單元測試（DATA-01）：文章發布、更新（含 PUT 直接轉 PUBLISHED）、
 * 下架事件都會讓該文章所掛的標籤重算「已發布文章數」。{@link ConsumerExecutor} 使用真實實例。
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TagArticleLifecycleConsumer 單元測試")
class TagArticleLifecycleConsumerTest {

    @Mock private TagUsageRecalculator recalculator;

    private TagArticleLifecycleConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TagArticleLifecycleConsumer(recalculator, new ConsumerExecutor());
    }

    private static ArticlePublishedEvent published(UUID articleUuid) {
        return new ArticlePublishedEvent(UUID.randomUUID(), articleUuid, UUID.randomUUID(), "t",
                LocalDateTime.now(), "s", "sum", "text", "nick", List.of());
    }

    private static ArticleUpdatedEvent updated(UUID articleUuid) {
        return new ArticleUpdatedEvent(UUID.randomUUID(), articleUuid, UUID.randomUUID(), "t",
                LocalDateTime.now(), "s", "sum", "text", "nick", List.of());
    }

    private static ArticleArchivedEvent archived(UUID articleUuid) {
        return new ArticleArchivedEvent(UUID.randomUUID(), articleUuid, Instant.now());
    }

    @Test
    @DisplayName("文章發布事件：重算該文章所掛標籤")
    void onArticlePublished_recalculatesTagsOfArticle() {
        UUID articleUuid = UUID.randomUUID();

        consumer.onArticlePublished(published(articleUuid));

        verify(recalculator).recalculateForArticle(articleUuid);
    }

    @Test
    @DisplayName("文章更新事件（PUT 把 DRAFT 直接轉 PUBLISHED 且未帶標籤時只會發這個事件）：重算該文章所掛標籤")
    void onArticleUpdated_recalculatesTagsOfArticle() {
        UUID articleUuid = UUID.randomUUID();

        consumer.onArticleUpdated(updated(articleUuid));

        verify(recalculator).recalculateForArticle(articleUuid);
    }

    @Test
    @DisplayName("文章下架事件（事件不帶標籤）：由 article_tags 查出標籤後重算")
    void onArticleArchived_recalculatesTagsOfArticle() {
        UUID articleUuid = UUID.randomUUID();

        consumer.onArticleArchived(archived(articleUuid));

        verify(recalculator).recalculateForArticle(articleUuid);
    }

    @Test
    @DisplayName("舊 payload 缺 articleUuid：不重算、仍正常返回（重送也不會變好）")
    void nullArticleUuid_isSkipped() {
        consumer.onArticlePublished(published(null));
        consumer.onArticleUpdated(updated(null));
        consumer.onArticleArchived(archived(null));

        verifyNoInteractions(recalculator);
    }

    @Test
    @DisplayName("重算拋例外：例外往外拋（交由容器重試，耗盡進 DLQ）")
    void recalculateThrows_propagates() {
        UUID articleUuid = UUID.randomUUID();
        doThrow(new RuntimeException("DB down")).when(recalculator).recalculateForArticle(articleUuid);

        assertThatThrownBy(() -> consumer.onArticleArchived(archived(articleUuid))).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("三個 listener 分別監聽發布／更新／下架 queue")
    void listeners_listenToLifecycleQueues() throws Exception {
        assertThat(queueOf("onArticlePublished", ArticlePublishedEvent.class))
                .isEqualTo(TagRabbitMqConfig.QUEUE_TAG_USAGE_ARTICLE_PUBLISHED);
        assertThat(queueOf("onArticleUpdated", ArticleUpdatedEvent.class))
                .isEqualTo(TagRabbitMqConfig.QUEUE_TAG_USAGE_ARTICLE_UPDATED);
        assertThat(queueOf("onArticleArchived", ArticleArchivedEvent.class))
                .isEqualTo(TagRabbitMqConfig.QUEUE_TAG_USAGE_ARTICLE_ARCHIVED);
    }

    private static String queueOf(String methodName, Class<?> eventType) throws Exception {
        Method method = TagArticleLifecycleConsumer.class.getMethod(methodName, eventType);
        RabbitListener listener = method.getAnnotation(RabbitListener.class);
        assertThat(listener).as(methodName + " 需有 @RabbitListener").isNotNull();
        assertThat(listener.queues()).hasSize(1);
        return listener.queues()[0];
    }
}
