package dowob.xyz.blog.module.reading.config;

import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import dowob.xyz.blog.infrastructure.event.UserEventContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReadingRabbitMqConfig} 單元測試：queue 名稱、DLQ 參數與對 article 事件契約的綁定。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ReadingRabbitMqConfig")
class ReadingRabbitMqConfigTest {

    private final ReadingRabbitMqConfig config = new ReadingRabbitMqConfig();

    @Test
    @DisplayName("queue：名稱為 reading.article-deleted、durable，並帶 DLQ 參數")
    void articleDeletedQueue_isDurableWithDlqArgs() {
        Queue queue = config.readingArticleDeletedQueue();

        assertThat(ReadingRabbitMqConfig.QUEUE_READING_ARTICLE_DELETED).isEqualTo("reading.article-deleted");
        assertThat(queue.getName()).isEqualTo("reading.article-deleted");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq")
                .containsEntry("x-dead-letter-routing-key", "dead-letter");
    }

    @Test
    @DisplayName("binding：綁定 article.events exchange 與 article.deleted routing key")
    void bindReadingArticleDeleted_bindsContractExchangeAndRoutingKey() {
        Queue queue = config.readingArticleDeletedQueue();
        TopicExchange exchange = new TopicExchange(ArticleEventContract.EXCHANGE);

        Binding binding = config.bindReadingArticleDeleted(queue, exchange);

        assertThat(binding.getExchange()).isEqualTo(ArticleEventContract.EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(ArticleEventContract.ROUTING_KEY_DELETED);
        assertThat(binding.getDestination()).isEqualTo("reading.article-deleted");
    }

    @Test
    @DisplayName("user.events exchange：Topic、durable、不自動刪除；名稱取自 user 事件契約")
    void userEventsExchange_matchesContractAndIsDurableTopic() {
        TopicExchange exchange = config.readingUserEventsExchange();

        assertThat(exchange.getName()).isEqualTo(UserEventContract.EXCHANGE).isEqualTo("user.events");
        assertThat(exchange.getType()).isEqualTo("topic");
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
    }

    @Test
    @DisplayName("queue：名稱為 reading.user-deleted、durable，並帶 DLQ 參數")
    void userDeletedQueue_isDurableWithDlqArgs() {
        Queue queue = config.readingUserDeletedQueue();

        assertThat(ReadingRabbitMqConfig.QUEUE_READING_USER_DELETED).isEqualTo("reading.user-deleted");
        assertThat(queue.getName()).isEqualTo("reading.user-deleted");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq")
                .containsEntry("x-dead-letter-routing-key", "dead-letter");
    }

    @Test
    @DisplayName("binding：綁定 user.events exchange 與 user.deleted routing key")
    void bindReadingUserDeleted_bindsUserContract() {
        Binding binding = config.bindReadingUserDeleted(
                config.readingUserDeletedQueue(), config.readingUserEventsExchange());

        assertThat(binding.getExchange()).isEqualTo(UserEventContract.EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(UserEventContract.ROUTING_KEY_DELETED);
        assertThat(binding.getDestination()).isEqualTo("reading.user-deleted");
    }
}
