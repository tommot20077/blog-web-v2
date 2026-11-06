package dowob.xyz.blog.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DeadLetterTopology} 單元測試（ARCH-19）：DLQ 拓撲常數與 queue arguments helper 是全站單一來源，
 * 值必須與重構前各模組複製的字面值完全相同（RabbitMQ 對既有 queue 參數不一致會回 406）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("DeadLetterTopology")
class DeadLetterTopologyTest {

    @Test
    @DisplayName("常數：DLX blog.dlq、DLQ queue.dead-letter、routing key dead-letter（與重構前字面值相同）")
    void constants_matchLegacyLiterals() {
        assertThat(DeadLetterTopology.DLQ_EXCHANGE).isEqualTo("blog.dlq");
        assertThat(DeadLetterTopology.DLQ_QUEUE).isEqualTo("queue.dead-letter");
        assertThat(DeadLetterTopology.DLQ_ROUTING_KEY).isEqualTo("dead-letter");
    }

    @Test
    @DisplayName("dlqArgs：恰好兩個 x-dead-letter-* 參數，沒有其他（例如 TTL）")
    void dlqArgs_hasExactlyTheTwoDeadLetterEntries() {
        assertThat(DeadLetterTopology.dlqArgs()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "x-dead-letter-exchange", "blog.dlq",
                "x-dead-letter-routing-key", "dead-letter"));
    }

    @Test
    @DisplayName("dlqArgs：每次回傳新的可變 Map，呼叫端可在其上追加參數（例如 version 的 TTL）而不污染其他 queue")
    void dlqArgs_returnsFreshMutableMapEachCall() {
        Map<String, Object> first = DeadLetterTopology.dlqArgs();
        first.put("x-message-ttl", 1);

        assertThat(DeadLetterTopology.dlqArgs()).doesNotContainKey("x-message-ttl");
    }

    @Test
    @DisplayName("withDeadLetter：QueueBuilder 版本產生與 dlqArgs 相同的參數")
    void withDeadLetter_appliesSameArguments() {
        Queue queue = DeadLetterTopology.withDeadLetter(QueueBuilder.durable("q")).build();

        assertThat(queue.getArguments()).isEqualTo(DeadLetterTopology.dlqArgs());
        assertThat(queue.isDurable()).isTrue();
    }
}
