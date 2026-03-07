package dowob.xyz.blog.module.search.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

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
}
