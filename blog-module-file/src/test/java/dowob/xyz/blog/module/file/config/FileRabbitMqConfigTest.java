package dowob.xyz.blog.module.file.config;

import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import dowob.xyz.blog.infrastructure.event.UserEventContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FileRabbitMqConfig} 中「使用者帳號刪除」訂閱的單元測試：queue 名稱、DLQ 參數與對 user 事件契約的綁定。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("FileRabbitMqConfig")
class FileRabbitMqConfigTest {

    private final FileRabbitMqConfig config = new FileRabbitMqConfig();

    @Test
    @DisplayName("user.events exchange：Topic、durable、不自動刪除；名稱取自 user 事件契約")
    void userEventsExchange_matchesContractAndIsDurableTopic() {
        TopicExchange exchange = config.fileUserEventsExchange();

        assertThat(exchange.getName()).isEqualTo(UserEventContract.EXCHANGE).isEqualTo("user.events");
        assertThat(exchange.getType()).isEqualTo("topic");
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
    }

    @Test
    @DisplayName("queue：file.user-deleted、durable，並帶 DLQ 參數")
    void userDeletedQueue_isDurableWithDlqArgs() {
        Queue queue = config.fileUserDeletedQueue();

        assertThat(FileRabbitMqConfig.QUEUE_USER_DELETED).isEqualTo("file.user-deleted");
        assertThat(queue.getName()).isEqualTo("file.user-deleted");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq")
                .containsEntry("x-dead-letter-routing-key", "dead-letter");
    }

    @Test
    @DisplayName("binding：綁定 user.events exchange 與 user.deleted routing key（取自契約常數）")
    void bindFileUserDeleted_bindsUserContract() {
        Binding binding = config.bindFileUserDeleted(config.fileUserDeletedQueue(), config.fileUserEventsExchange());

        assertThat(binding.getExchange()).isEqualTo(UserEventContract.EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(UserEventContract.ROUTING_KEY_DELETED);
        assertThat(binding.getDestination()).isEqualTo("file.user-deleted");
    }

    @Test
    @DisplayName("article.events exchange（W5A）：Topic、durable、不自動刪除；名稱取自 article 事件契約")
    void articleEventsExchange_matchesContractAndIsDurableTopic() {
        TopicExchange exchange = config.fileArticleEventsExchange();

        assertThat(exchange.getName()).isEqualTo(ArticleEventContract.EXCHANGE).isEqualTo("article.events");
        assertThat(exchange.getType()).isEqualTo("topic");
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
    }

    @Test
    @DisplayName("queue（W5A）：file.article-binding、durable，並帶 DLQ 參數")
    void articleBindingQueue_isDurableWithDlqArgs() {
        Queue queue = config.fileArticleBindingQueue();

        assertThat(FileRabbitMqConfig.QUEUE_ARTICLE_CONTENT_CHANGED).isEqualTo("file.article-binding");
        assertThat(queue.getName()).isEqualTo("file.article-binding");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq")
                .containsEntry("x-dead-letter-routing-key", "dead-letter");
    }

    @Test
    @DisplayName("binding（W5A）：綁定 article.events exchange 與 article.content.changed routing key（取自契約常數）")
    void bindFileArticleBinding_bindsContentChangedContract() {
        Binding binding = config.bindFileArticleBinding(config.fileArticleBindingQueue(), config.fileArticleEventsExchange());

        assertThat(binding.getExchange()).isEqualTo(ArticleEventContract.EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(ArticleEventContract.ROUTING_KEY_CONTENT_CHANGED)
                .isEqualTo("article.content.changed");
        assertThat(binding.getDestination()).isEqualTo("file.article-binding");
    }

    @Test
    @DisplayName("queue（DATA-07）：file.article-deleted、durable，並帶 DLQ 參數")
    void articleDeletedQueue_isDurableWithDlqArgs() {
        Queue queue = config.fileArticleDeletedQueue();

        assertThat(FileRabbitMqConfig.QUEUE_ARTICLE_DELETED).isEqualTo("file.article-deleted");
        assertThat(queue.getName()).isEqualTo("file.article-deleted");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq")
                .containsEntry("x-dead-letter-routing-key", "dead-letter");
    }

    @Test
    @DisplayName("binding（DATA-07）：綁定 article.events exchange 與 article.deleted routing key（取自契約常數）")
    void bindFileArticleDeleted_bindsDeletedContract() {
        Binding binding = config.bindFileArticleDeleted(config.fileArticleDeletedQueue(), config.fileArticleEventsExchange());

        assertThat(binding.getExchange()).isEqualTo(ArticleEventContract.EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(ArticleEventContract.ROUTING_KEY_DELETED);
        assertThat(binding.getDestination()).isEqualTo("file.article-deleted");
    }
}
