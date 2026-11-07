package dowob.xyz.blog.e2e.observability;

import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.infrastructure.mq.MeteredRabbitTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C7 端到端：Prometheus 匯出端點只在 management port 上，主 port 取不到；K3s probe 在主 port 的
 * {@code /livez}、{@code /readyz} 仍可匿名存取。
 *
 * <p>真實 Tomcat＋完整 Spring Security 鏈（含 management 子 context）。management port 在 e2e profile 為隨機 port
 * （避免多個快取 context 搶同一個固定 port）；正式環境預設 9091，不經過 ingress（見 security.md）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@AutoConfigureObservability // @SpringBootTest 預設關閉 metrics 匯出；要驗 Prometheus 端點必須明確開啟
@DisplayName("Actuator management port／Prometheus 端點 E2E")
class ActuatorManagementPortE2E extends AbstractE2ETest {

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ConsumerExecutor consumerExecutor;

    private ResponseEntity<String> get(int port, String path) {
        return rest.getForEntity("http://localhost:" + port + path, String.class);
    }

    @Test
    @DisplayName("management port 與主 port 不同（actuator 已搬到獨立 port）")
    void managementPort_isSeparateFromServerPort() {
        assertThat(managementPort).isNotEqualTo(serverPort);
    }

    @Test
    @DisplayName("management port 的 /actuator/prometheus 可匿名取得，且含既有的 mq_consumer_processed 與新的 mq_dlq_depth")
    void managementPort_prometheusEndpoint_exposesMqMetrics() throws Exception {
        consumerExecutor.execute("e2e.observability", () -> { });

        ResponseEntity<String> response = get(managementPort, "/actuator/prometheus");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("DLQ 深度 gauge 於啟動時註冊（值可為 NaN 或 0）")
                .contains("mq_dlq_depth")
                .contains("mq_consumer_processed_total");
    }

    @Test
    @DisplayName("publisher 發送失敗後，mq_publisher_failed 出現在 Prometheus 輸出（全域 registry 已接到 Prometheus）")
    void managementPort_prometheusEndpoint_exposesPublisherFailedCounter() {
        ConnectionFactory brokenCf = mock(ConnectionFactory.class);
        when(brokenCf.createConnection()).thenThrow(new org.springframework.amqp.AmqpConnectException(
                new java.io.IOException("broker down")));
        MeteredRabbitTemplate template = new MeteredRabbitTemplate(brokenCf);
        assertThatThrownBy(() -> template.send("e2e.events", "e2e.failed",
                new org.springframework.amqp.core.Message(new byte[0])))
                .isInstanceOf(AmqpException.class);

        String body = get(managementPort, "/actuator/prometheus").getBody();

        assertThat(body).contains("mq_publisher_failed_total{exchange=\"e2e.events\",routing_key=\"e2e.failed\"");
    }

    @Test
    @DisplayName("management port 的 /actuator/health/liveness 可匿名取得（探針／健康檢查可改用此 port）")
    void managementPort_healthLiveness_isUp() {
        /* 不測聚合的 /actuator/health：它含 mail 等外部依賴，e2e 環境沒有 SMTP 會回 503（與本變更無關） */
        ResponseEntity<String> response = get(managementPort, "/actuator/health/liveness");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
    }

    @Test
    @DisplayName("主 port 取不到 /actuator/prometheus（匿名 → 401 或 404，絕不是 200，body 不含指標）")
    void mainPort_prometheusEndpoint_isNotReachable() {
        ResponseEntity<String> response = get(serverPort, "/actuator/prometheus");

        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        assertThat(String.valueOf(response.getBody())).doesNotContain("mq_dlq_depth");
    }

    @Test
    @DisplayName("主 port 的 /livez 與 /readyz 可匿名存取（K3s probe 改用主 port 的 additional path）")
    void mainPort_probes_areAnonymouslyReachable() {
        assertThat(get(serverPort, "/livez").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(serverPort, "/readyz").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
