package dowob.xyz.blog.infrastructure.mq;

import dowob.xyz.blog.infrastructure.config.DeadLetterTopology;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.testcontainers.containers.RabbitMQContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

/**
 * {@link DlqDepthMetrics} 對<b>真 RabbitMQ</b>（Testcontainers）的整合測試：gauge 讀到正確的 DLQ 深度；
 * broker 被關掉之後 refresh 不拋例外、gauge 轉為 NaN。
 *
 * <p>測試依宣告順序執行（深度 → 清空 → 斷線），最後一個測試會停掉自己專用的 broker 容器。
 * 命名為 {@code *IntegrationTest} 是因為 blog-infrastructure 的 surefire 沒有 {@code *IT} includes。需要 Docker。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("DlqDepthMetrics 整合測試（真 RabbitMQ）")
class DlqDepthMetricsIntegrationTest {

    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.2.2-management-alpine");

    private static CachingConnectionFactory connectionFactory;

    private static RabbitAdmin admin;

    private static RabbitTemplate template;

    private SimpleMeterRegistry registry;

    private DlqDepthMetrics metrics;

    @BeforeAll
    static void startBroker() {
        RABBIT.start();
        connectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connectionFactory.setUsername(RABBIT.getAdminUsername());
        connectionFactory.setPassword(RABBIT.getAdminPassword());
        /* 斷線測試要快速失敗，不要等預設的長逾時 */
        connectionFactory.setConnectionTimeout(2_000);
        admin = new RabbitAdmin(connectionFactory);
        admin.declareQueue(new Queue(DeadLetterTopology.DLQ_QUEUE, true));
        template = new RabbitTemplate(connectionFactory);
    }

    @AfterAll
    static void stopBroker() {
        connectionFactory.destroy();
        if (RABBIT.isRunning()) {
            RABBIT.stop();
        }
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.amqp.core.AmqpAdmin> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(admin);
        metrics = new DlqDepthMetrics(provider);
    }

    @AfterEach
    void tearDown() {
        metrics.close();
        Metrics.removeRegistry(registry);
        registry.close();
    }

    private double gaugeValue() {
        Gauge gauge = registry.find(DlqDepthMetrics.DEPTH_METRIC).tag("queue", DeadLetterTopology.DLQ_QUEUE).gauge();
        assertThat(gauge).isNotNull();
        return gauge.value();
    }

    @Test
    @Order(1)
    @DisplayName("DLQ 內有 3 則訊息 → refresh 後 gauge 為 3")
    void refresh_reportsActualDlqDepth() {
        for (int i = 0; i < 3; i++) {
            template.convertAndSend("", DeadLetterTopology.DLQ_QUEUE, "dead-" + i);
        }
        /* messageCount 在 broker 端是非同步入列後才反映，用輪詢而非固定睡眠 */
        await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> {
            metrics.refresh();
            assertThat(gaugeValue()).isEqualTo(3.0);
        });
    }

    @Test
    @Order(2)
    @DisplayName("清空 DLQ 後 refresh → gauge 回到 0（0 是真的沒積壓，與 NaN 區分）")
    void refresh_afterPurge_reportsZero() {
        admin.purgeQueue(DeadLetterTopology.DLQ_QUEUE, false);

        await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> {
            metrics.refresh();
            assertThat(gaugeValue()).isEqualTo(0.0);
        });
    }

    @Test
    @Order(3)
    @DisplayName("broker 斷線 → refresh 不拋例外，gauge 轉為 NaN")
    void refresh_whenBrokerStopped_doesNotThrowAndGaugeIsNaN() {
        metrics.refresh();
        assertThat(gaugeValue()).isEqualTo(0.0);

        RABBIT.stop();

        assertThatCode(metrics::refresh).doesNotThrowAnyException();
        assertThat(gaugeValue()).isNaN();
    }
}
