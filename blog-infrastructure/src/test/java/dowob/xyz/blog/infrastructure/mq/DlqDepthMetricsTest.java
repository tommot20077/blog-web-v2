package dowob.xyz.blog.infrastructure.mq;

import dowob.xyz.blog.infrastructure.config.DeadLetterTopology;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DlqDepthMetrics} 的單元測試：{@code mq.dlq.depth} gauge 讀的是排程更新的快取值，
 * 且 broker 不可用時不外拋。真 RabbitMQ 下的深度正確性由 {@code DlqDepthMetricsIntegrationTest} 驗證。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("DlqDepthMetrics：DLQ 深度 gauge")
class DlqDepthMetricsTest {

    private SimpleMeterRegistry registry;

    private AmqpAdmin admin;

    /** 本測試建立的實例；結束時移除其 gauge，避免全域 composite registry 在測試間殘留 */
    private final java.util.List<DlqDepthMetrics> created = new java.util.ArrayList<>();

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AmqpAdmin> provider = mock(ObjectProvider.class);

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        admin = mock(AmqpAdmin.class);
        when(provider.getIfAvailable()).thenReturn(admin);
    }

    @AfterEach
    void tearDown() {
        created.forEach(DlqDepthMetrics::close);
        Metrics.removeRegistry(registry);
        registry.close();
    }

    private DlqDepthMetrics create() {
        DlqDepthMetrics metrics = new DlqDepthMetrics(provider);
        created.add(metrics);
        return metrics;
    }

    private static QueueInformation info(int messageCount) {
        return new QueueInformation(DeadLetterTopology.DLQ_QUEUE, messageCount, 1);
    }

    private double gaugeValue() {
        Gauge gauge = registry.find(DlqDepthMetrics.DEPTH_METRIC).tag("queue", DeadLetterTopology.DLQ_QUEUE).gauge();
        assertThat(gauge).as("應已註冊 mq.dlq.depth{queue=%s}", DeadLetterTopology.DLQ_QUEUE).isNotNull();
        return gauge.value();
    }

    @Test
    @DisplayName("refresh 後 gauge 等於 broker 回報的 DLQ messageCount")
    void refresh_setsGaugeToBrokerMessageCount() {
        when(admin.getQueueInfo(DeadLetterTopology.DLQ_QUEUE)).thenReturn(info(7));
        DlqDepthMetrics metrics = create();

        metrics.refresh();

        assertThat(gaugeValue()).isEqualTo(7.0);
    }

    @Test
    @DisplayName("尚未 refresh 時 gauge 為 NaN（未知），不是 0——避免把「沒量到」誤報成「沒積壓」")
    void beforeFirstRefresh_gaugeIsNaN() {
        DlqDepthMetrics metrics = create();

        assertThat(metrics).isNotNull();
        assertThat(gaugeValue()).isNaN();
    }

    @Test
    @DisplayName("讀 gauge 不會打 broker：scrape 多次，broker 只被 refresh 查詢一次")
    void readingGauge_doesNotHitBroker() {
        when(admin.getQueueInfo(DeadLetterTopology.DLQ_QUEUE)).thenReturn(info(3));
        DlqDepthMetrics metrics = create();
        metrics.refresh();

        gaugeValue();
        gaugeValue();
        gaugeValue();

        verify(admin, times(1)).getQueueInfo(DeadLetterTopology.DLQ_QUEUE);
    }

    @Test
    @DisplayName("broker 不可用（拋 AmqpConnectException）→ refresh 不外拋，gauge 轉為 NaN")
    void refresh_whenBrokerDown_doesNotThrowAndGaugeBecomesNaN() {
        when(admin.getQueueInfo(DeadLetterTopology.DLQ_QUEUE))
                .thenReturn(info(5))
                .thenThrow(new AmqpConnectException(new IOException("broker down")));
        DlqDepthMetrics metrics = create();
        metrics.refresh();
        assertThat(gaugeValue()).isEqualTo(5.0);

        assertThatCode(metrics::refresh).doesNotThrowAnyException();

        assertThat(gaugeValue()).isNaN();
    }

    @Test
    @DisplayName("broker 恢復後下一次 refresh 回到正確深度")
    void refresh_afterBrokerRecovers_reportsDepthAgain() {
        when(admin.getQueueInfo(DeadLetterTopology.DLQ_QUEUE))
                .thenThrow(new AmqpConnectException(new IOException("broker down")))
                .thenReturn(info(2));
        DlqDepthMetrics metrics = create();

        metrics.refresh();
        metrics.refresh();

        assertThat(gaugeValue()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("DLQ queue 尚不存在（getQueueInfo 回 null）→ gauge 為 NaN，不外拋")
    void refresh_whenQueueMissing_gaugeIsNaN() {
        when(admin.getQueueInfo(DeadLetterTopology.DLQ_QUEUE)).thenReturn(null);
        DlqDepthMetrics metrics = create();

        assertThatCode(metrics::refresh).doesNotThrowAnyException();

        assertThat(gaugeValue()).isNaN();
    }

    @Test
    @DisplayName("沒有 AmqpAdmin bean（無 RabbitMQ 的模組測試環境）→ refresh 為 no-op，不外拋")
    void refresh_withoutAmqpAdmin_isNoOp() {
        when(provider.getIfAvailable()).thenReturn(null);
        DlqDepthMetrics metrics = create();

        assertThatCode(metrics::refresh).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("broker 黑洞（接受 TCP 連線但永不回應 AMQP 握手）→ refresh 在逾時內返回、不外拋、gauge 為 NaN")
    void refresh_whenBrokerIsBlackHole_returnsWithinTimeout() throws Exception {
        try (java.net.ServerSocket blackHole = new java.net.ServerSocket(0)) {
            org.springframework.amqp.rabbit.connection.CachingConnectionFactory cf =
                    new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("localhost", blackHole.getLocalPort());
            cf.setConnectionTimeout(1_000);
            cf.getRabbitConnectionFactory().setHandshakeTimeout(1_000);
            cf.getRabbitConnectionFactory().setChannelRpcTimeout(1_000);
            when(provider.getIfAvailable()).thenReturn(new org.springframework.amqp.rabbit.core.RabbitAdmin(cf));
            DlqDepthMetrics metrics = create();

            long start = System.nanoTime();
            assertThatCode(metrics::refresh).doesNotThrowAnyException();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs).as("refresh 不得長時間卡住").isLessThan(15_000);
            assertThat(gaugeValue()).isNaN();
            cf.destroy();
        }
    }
}
