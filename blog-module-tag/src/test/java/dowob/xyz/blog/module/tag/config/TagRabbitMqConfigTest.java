package dowob.xyz.blog.module.tag.config;

import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import dowob.xyz.blog.infrastructure.event.UserEventContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TagRabbitMqConfig} 單元測試：文章刪除 queue 的名稱、DLQ 參數與對 article 事件契約的綁定。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("TagRabbitMqConfig 單元測試")
class TagRabbitMqConfigTest {

    private final TagRabbitMqConfig config = new TagRabbitMqConfig();

    /** 共用的 article.events exchange（ARCH-06：只在 infrastructure 宣告一次，本模組的 binding 以參數注入） */
    private static final TopicExchange ARTICLE_EVENTS_EXCHANGE = new TopicExchange(ArticleEventContract.EXCHANGE);

    @Test
    @DisplayName("queue：名稱為 tag.article-deleted、durable，並帶 DLQ 參數")
    void articleDeletedQueue_isDurableWithDlqArgs() {
        Queue queue = config.tagArticleDeletedQueue();

        assertThat(queue.getName()).isEqualTo("tag.article-deleted");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq")
                .containsEntry("x-dead-letter-routing-key", "dead-letter");
    }

    @Test
    @DisplayName("binding：綁定 article.events exchange 與 article.deleted routing key（取自事件契約常數）")
    void bindTagArticleDeleted_bindsContractExchangeAndRoutingKey() {
        Binding binding = config.bindTagArticleDeleted(ARTICLE_EVENTS_EXCHANGE);

        assertThat(binding.getExchange()).isEqualTo(ArticleEventContract.EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(ArticleEventContract.ROUTING_KEY_DELETED);
        assertThat(binding.getDestination()).isEqualTo("tag.article-deleted");
    }

    @Test
    @DisplayName("既有的 article.tagged queue 與 binding 不受影響")
    void taggedQueueAndBinding_areUnchanged() {
        assertThat(config.tagArticleTaggedQueue().getName()).isEqualTo("tag.article.tagged");
        assertThat(config.tagArticleTaggedBinding(ARTICLE_EVENTS_EXCHANGE).getRoutingKey()).isEqualTo(ArticleEventContract.ROUTING_KEY_TAGGED);
    }

    @Test
    @DisplayName("DATA-01：發布／更新／下架三個 usage queue 皆 durable、帶 DLQ 參數，且各綁對應 routing key")
    void usageLifecycleQueues_areDurableWithDlqArgsAndBoundToContractKeys() {
        assertQueue(config.tagUsageArticlePublishedQueue(), "tag.usage.article-published");
        assertQueue(config.tagUsageArticleUpdatedQueue(), "tag.usage.article-updated");
        assertQueue(config.tagUsageArticleArchivedQueue(), "tag.usage.article-archived");

        assertBinding(config.bindTagUsageArticlePublished(ARTICLE_EVENTS_EXCHANGE), "tag.usage.article-published",
                ArticleEventContract.ROUTING_KEY_PUBLISHED);
        assertBinding(config.bindTagUsageArticleUpdated(ARTICLE_EVENTS_EXCHANGE), "tag.usage.article-updated",
                ArticleEventContract.ROUTING_KEY_UPDATED);
        assertBinding(config.bindTagUsageArticleArchived(ARTICLE_EVENTS_EXCHANGE), "tag.usage.article-archived",
                ArticleEventContract.ROUTING_KEY_ARCHIVED);
    }

    private static void assertQueue(Queue queue, String name) {
        assertThat(queue.getName()).isEqualTo(name);
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq")
                .containsEntry("x-dead-letter-routing-key", "dead-letter");
    }

    private static void assertBinding(Binding binding, String destination, String routingKey) {
        assertThat(binding.getExchange()).isEqualTo(ArticleEventContract.EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(routingKey);
        assertThat(binding.getDestination()).isEqualTo(destination);
    }

    @Test
    @DisplayName("user.events exchange：Topic、durable、不自動刪除；名稱取自 user 事件契約")
    void userEventsExchange_matchesContractAndIsDurableTopic() {
        TopicExchange exchange = config.tagUserEventsExchange();

        assertThat(exchange.getName()).isEqualTo(UserEventContract.EXCHANGE).isEqualTo("user.events");
        assertThat(exchange.getType()).isEqualTo("topic");
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
    }

    @Test
    @DisplayName("queue：tag.user-deleted、durable，並帶 DLQ 參數")
    void userDeletedQueue_isDurableWithDlqArgs() {
        Queue queue = config.tagUserDeletedQueue();

        assertThat(TagRabbitMqConfig.QUEUE_TAG_USER_DELETED).isEqualTo("tag.user-deleted");
        assertThat(queue.getName()).isEqualTo("tag.user-deleted");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq")
                .containsEntry("x-dead-letter-routing-key", "dead-letter");
    }

    @Test
    @DisplayName("binding：綁定 user.events exchange 與 user.deleted routing key（取自契約常數）")
    void bindTagUserDeleted_bindsUserContract() {
        Binding binding = config.bindTagUserDeleted();

        assertThat(binding.getExchange()).isEqualTo(UserEventContract.EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(UserEventContract.ROUTING_KEY_DELETED);
        assertThat(binding.getDestination()).isEqualTo("tag.user-deleted");
    }
}
