package dowob.xyz.blog.module.version.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import dowob.xyz.blog.infrastructure.event.UserEventContract;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VersionRabbitMqConfig 單元測試
 *
 * <p>驗證版本模組的 Queue 對齊全站 DLQ 慣例（blog.dlq + dead-letter routing key），
 * 確保訊息失敗時能進入共用死信基礎設施而不是被靜默丟失。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("VersionRabbitMqConfig 單元測試")
class VersionRabbitMqConfigTest {

    private final VersionRabbitMqConfig config = new VersionRabbitMqConfig();

    @Test
    @DisplayName("versionSnapshotQueue 的 DLQ 應指向共用 blog.dlq exchange")
    void versionSnapshotQueue_dlqExchangeIsBlogDlq() {
        Queue queue = config.versionSnapshotQueue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq");
    }

    @Test
    @DisplayName("versionSnapshotQueue 的 DLQ routing key 應為 dead-letter")
    void versionSnapshotQueue_dlqRoutingKeyIsDeadLetter() {
        Queue queue = config.versionSnapshotQueue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-routing-key", "dead-letter");
    }

    @Test
    @DisplayName("versionArticleDeletedQueue：名稱為 version.article-deleted、durable，並帶 DLQ 參數")
    void versionArticleDeletedQueue_isDurableWithDlqArgs() {
        Queue queue = config.versionArticleDeletedQueue();

        assertThat(VersionRabbitMqConfig.QUEUE_VERSION_ARTICLE_DELETED).isEqualTo("version.article-deleted");
        assertThat(queue.getName()).isEqualTo("version.article-deleted");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq")
                .containsEntry("x-dead-letter-routing-key", "dead-letter");
    }

    @Test
    @DisplayName("bindVersionArticleDeleted：綁定 article.events exchange 與 article.deleted routing key")
    void bindVersionArticleDeleted_bindsContractExchangeAndRoutingKey() {
        Queue queue = config.versionArticleDeletedQueue();
        TopicExchange exchange = new TopicExchange(ArticleEventContract.EXCHANGE);

        Binding binding = config.bindVersionArticleDeleted(queue, exchange);

        assertThat(binding.getExchange()).isEqualTo(ArticleEventContract.EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(ArticleEventContract.ROUTING_KEY_DELETED);
        assertThat(binding.getDestination()).isEqualTo("version.article-deleted");
    }

    @Test
    @DisplayName("user.events exchange：Topic、durable、不自動刪除；名稱取自 user 事件契約")
    void userEventsExchange_matchesContractAndIsDurableTopic() {
        TopicExchange exchange = config.versionUserEventsExchange();

        assertThat(exchange.getName()).isEqualTo(UserEventContract.EXCHANGE).isEqualTo("user.events");
        assertThat(exchange.getType()).isEqualTo("topic");
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
    }

    @Test
    @DisplayName("versionUserDeletedQueue：名稱為 version.user-deleted、durable，並帶 DLQ 參數")
    void versionUserDeletedQueue_isDurableWithDlqArgs() {
        Queue queue = config.versionUserDeletedQueue();

        assertThat(VersionRabbitMqConfig.QUEUE_VERSION_USER_DELETED).isEqualTo("version.user-deleted");
        assertThat(queue.getName()).isEqualTo("version.user-deleted");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq")
                .containsEntry("x-dead-letter-routing-key", "dead-letter");
    }

    @Test
    @DisplayName("bindVersionUserDeleted：綁定 user.events exchange 與 user.deleted routing key（取自契約常數）")
    void bindVersionUserDeleted_bindsUserContract() {
        Binding binding = config.bindVersionUserDeleted(
                config.versionUserDeletedQueue(), config.versionUserEventsExchange());

        assertThat(binding.getExchange()).isEqualTo(UserEventContract.EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(UserEventContract.ROUTING_KEY_DELETED);
        assertThat(binding.getDestination()).isEqualTo("version.user-deleted");
    }
}
