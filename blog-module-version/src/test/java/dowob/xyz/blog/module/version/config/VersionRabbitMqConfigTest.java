package dowob.xyz.blog.module.version.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
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
}
