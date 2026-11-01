package dowob.xyz.blog.module.file.config;

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
}
