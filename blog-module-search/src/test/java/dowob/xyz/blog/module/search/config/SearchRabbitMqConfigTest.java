package dowob.xyz.blog.module.search.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import dowob.xyz.blog.infrastructure.event.UserEventContract;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SearchRabbitMqConfig 單元測試
 *
 * <p>驗證搜尋模組的 Queue 均包含 DLQ 設定，確保訊息失敗時不被直接丟棄。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("SearchRabbitMqConfig 單元測試")
class SearchRabbitMqConfigTest {

    private final SearchRabbitMqConfig config = new SearchRabbitMqConfig();

    @Test
    @DisplayName("searchIndexQueue 包含 DLQ x-dead-letter-exchange 設定")
    void searchIndexQueue_hasDlqArgs() {
        Queue queue = config.searchIndexQueue();
        assertThat(queue.getArguments()).containsKey("x-dead-letter-exchange");
        assertThat(queue.getArguments().get("x-dead-letter-exchange")).isEqualTo("blog.dlq");
    }

    @Test
    @DisplayName("searchIndexUpdateQueue 包含 DLQ x-dead-letter-exchange 設定")
    void searchIndexUpdateQueue_hasDlqArgs() {
        Queue queue = config.searchIndexUpdateQueue();
        assertThat(queue.getArguments()).containsKey("x-dead-letter-exchange");
        assertThat(queue.getArguments().get("x-dead-letter-exchange")).isEqualTo("blog.dlq");
    }

    @Test
    @DisplayName("searchIndexDeleteQueue 包含 DLQ x-dead-letter-exchange 設定")
    void searchIndexDeleteQueue_hasDlqArgs() {
        Queue queue = config.searchIndexDeleteQueue();
        assertThat(queue.getArguments()).containsKey("x-dead-letter-exchange");
        assertThat(queue.getArguments().get("x-dead-letter-exchange")).isEqualTo("blog.dlq");
    }

    @Test
    @DisplayName("searchIndexArchiveQueue 包含 DLQ x-dead-letter-exchange 設定")
    void searchIndexArchiveQueue_hasDlqArgs() {
        Queue queue = config.searchIndexArchiveQueue();
        assertThat(queue.getArguments()).containsKey("x-dead-letter-exchange");
        assertThat(queue.getArguments().get("x-dead-letter-exchange")).isEqualTo("blog.dlq");
    }

    @Test
    @DisplayName("下架索引 Queue 綁定 article.archived，與 article.deleted 分開（避免 series 計數被連帶遞減）")
    void searchIndexArchiveBinding_bindsToArchivedRoutingKey() {
        assertThat(SearchRabbitMqConfig.ROUTING_KEY_ARCHIVED).isEqualTo("article.archived");
        assertThat(SearchRabbitMqConfig.ROUTING_KEY_ARCHIVED)
                .isNotEqualTo(SearchRabbitMqConfig.ROUTING_KEY_DELETED);
        assertThat(config.searchIndexArchiveBinding().getRoutingKey())
                .isEqualTo(SearchRabbitMqConfig.ROUTING_KEY_ARCHIVED);
        assertThat(config.searchIndexArchiveBinding().getDestination())
                .isEqualTo(SearchRabbitMqConfig.QUEUE_SEARCH_INDEX_ARCHIVE);
    }

    @Test
    @DisplayName("user.events exchange：Topic、durable、不自動刪除；名稱取自 user 事件契約")
    void userEventsExchange_matchesContractAndIsDurableTopic() {
        TopicExchange exchange = config.searchUserEventsExchange();

        assertThat(exchange.getName()).isEqualTo(UserEventContract.EXCHANGE).isEqualTo("user.events");
        assertThat(exchange.getType()).isEqualTo("topic");
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
    }

    @Test
    @DisplayName("queue：search.user-deleted、durable，並帶 DLQ 參數")
    void userDeletedQueue_isDurableWithDlqArgs() {
        Queue queue = config.searchUserDeletedQueue();

        assertThat(SearchRabbitMqConfig.QUEUE_SEARCH_USER_DELETED).isEqualTo("search.user-deleted");
        assertThat(queue.getName()).isEqualTo("search.user-deleted");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq")
                .containsEntry("x-dead-letter-routing-key", "dead-letter");
    }

    @Test
    @DisplayName("binding：綁定 user.events exchange 與 user.deleted routing key（取自契約常數）")
    void searchUserDeletedBinding_bindsUserContract() {
        Binding binding = config.searchUserDeletedBinding();

        assertThat(binding.getExchange()).isEqualTo(UserEventContract.EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(UserEventContract.ROUTING_KEY_DELETED);
        assertThat(binding.getDestination()).isEqualTo("search.user-deleted");
    }
}
