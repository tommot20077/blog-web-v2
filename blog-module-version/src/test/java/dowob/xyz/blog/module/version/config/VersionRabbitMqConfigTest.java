package dowob.xyz.blog.module.version.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

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
}
