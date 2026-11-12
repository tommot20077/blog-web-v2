package dowob.xyz.blog.infrastructure.mq;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MeteredRabbitTemplate} 的單元測試：{@code mq.publisher.failed} 是全站 publisher 發送失敗的唯一計數點。
 *
 * <p>所有 publisher（article／series／user／file 與 AuthService）共用同一個 {@code RabbitTemplate} bean，
 * 失敗在此計數後仍<b>照常外拋</b>，由各 publisher 既有的 best-effort catch 處理。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("MeteredRabbitTemplate：publisher 發送失敗計數")
class MeteredRabbitTemplateTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(registry);
        registry.close();
    }

    private MeteredRabbitTemplate templateWith(ConnectionFactory cf) {
        MeteredRabbitTemplate template = new MeteredRabbitTemplate(cf);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        return template;
    }

    private ConnectionFactory brokenConnectionFactory() {
        ConnectionFactory cf = mock(ConnectionFactory.class);
        when(cf.createConnection()).thenThrow(new AmqpConnectException(new IOException("broker down")));
        return cf;
    }

    private double failed(String exchange, String routingKey) {
        var counter = registry.find(MeteredRabbitTemplate.FAILED_METRIC)
                .tag("exchange", exchange).tag("routing_key", routingKey).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("broker 連不上導致 convertAndSend 失敗 → 該 exchange／routing key 的 mq.publisher.failed 加 1，例外照常外拋")
    void convertAndSend_whenBrokerDown_incrementsFailedCounterAndRethrows() {
        MeteredRabbitTemplate template = templateWith(brokenConnectionFactory());

        assertThatThrownBy(() -> template.convertAndSend("article.events", "article.published", Map.of("k", "v")))
                .isInstanceOf(AmqpConnectException.class);

        assertThat(failed("article.events", "article.published")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("連續兩次失敗 → 計數為 2；不同 routing key 各自獨立計數")
    void convertAndSend_failuresAreCountedPerExchangeAndRoutingKey() {
        MeteredRabbitTemplate template = templateWith(brokenConnectionFactory());

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> template.convertAndSend("article.events", "article.deleted", Map.of()))
                    .isInstanceOf(AmqpConnectException.class);
        }
        assertThatThrownBy(() -> template.convertAndSend("user.events", "user.deleted", Map.of()))
                .isInstanceOf(AmqpConnectException.class);

        assertThat(failed("article.events", "article.deleted")).isEqualTo(2.0);
        assertThat(failed("user.events", "user.deleted")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("預設 exchange（空字串）發送失敗 → exchange tag 為 (default)，不得因空 tag 值而計數失敗")
    void convertAndSend_defaultExchange_usesPlaceholderTag() {
        MeteredRabbitTemplate template = templateWith(brokenConnectionFactory());

        assertThatThrownBy(() -> template.convertAndSend("", "some.queue", Map.of()))
                .isInstanceOf(AmqpConnectException.class);

        assertThat(failed("(default)", "some.queue")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("發送成功 → 不計 mq.publisher.failed")
    void convertAndSend_success_doesNotIncrementFailedCounter() throws Exception {
        ConnectionFactory cf = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        when(cf.createConnection()).thenReturn(connection);
        when(connection.createChannel(anyBoolean())).thenReturn(channel);
        MeteredRabbitTemplate template = templateWith(cf);

        template.convertAndSend("article.events", "article.published", Map.of("k", "v"));

        // 全域 composite 會把先前測試註冊過的 meter 帶進新 registry，故斷言「總和為 0」而非「不存在」
        double total = registry.find(MeteredRabbitTemplate.FAILED_METRIC).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
        assertThat(total).isZero();
    }
}
