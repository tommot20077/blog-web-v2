package dowob.xyz.blog.infrastructure.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Z3-b 的 ack 模式證明：以<b>真 RabbitMQ</b>比較 {@code MANUAL} 與 {@code AUTO} 在
 * 「listener 拋一般例外 + stateful retry interceptor」下的實際行為，決定正式環境採用哪一種。
 *
 * <p>本測試只使用 Spring AMQP 原生元件（不含任何 blog 專案類別），目的是固定下列事實，
 * 避免日後有人把 ack 模式改回 {@code MANUAL} 而重試靜默失效：</p>
 * <ul>
 *   <li>{@code MANUAL}：listener 拋一般例外，容器<b>不會</b> nack，訊息停在 unacked、不重投、不進 DLQ，
 *       retry interceptor 沒有第二次機會（直到 channel 關閉才被 broker 重新入列）</li>
 *   <li>{@code AUTO}：失敗 → 容器 nack(requeue) → 重投 → interceptor 計次 → 第 N 次成功即 ack；
 *       耗盡後 recoverer 拒絕且不重新入列 → DLX → DLQ</li>
 * </ul>
 *
 * <p>unacked 數量取自 RabbitMQ Management HTTP API（Testcontainers 的 management 映像）；
 * 該統計約每 5 秒更新一次，故一律以 Awaitility 輪詢並設上限，不使用固定睡眠。
 * 命名為 {@code *IntegrationTest} 是因為 blog-infrastructure 的 surefire 沒有 {@code *IT} includes。
 * 需要 Docker。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Testcontainers
@DisplayName("ack 模式證明（真 RabbitMQ）：MANUAL 無法重試、AUTO 可重試並進 DLQ")
class AckModeRetryProofIntegrationTest {

    /** 重試最大次數（含首次），與正式設定一致 */
    private static final int MAX_ATTEMPTS = 3;

    /** 輪詢等待上限（含 Management API 約 5 秒的統計延遲） */
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(40);

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3-management-alpine");

    private static CachingConnectionFactory connectionFactory;
    private static RabbitAdmin admin;
    private static RabbitTemplate template;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** 每個測試各自的 work queue 名稱（避免互相污染） */
    private String workQueue;

    /** 每個測試各自的 DLQ 名稱 */
    private String deadQueue;

    /** 本測試啟動的容器 */
    private SimpleMessageListenerContainer container;

    @BeforeAll
    static void connect() {
        connectionFactory = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
        connectionFactory.setUsername(rabbit.getAdminUsername());
        connectionFactory.setPassword(rabbit.getAdminPassword());
        admin = new RabbitAdmin(connectionFactory);
        template = new RabbitTemplate(connectionFactory);
    }

    @AfterAll
    static void close() {
        connectionFactory.destroy();
    }

    @AfterEach
    void stopContainer() {
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }

    /**
     * 宣告 work queue（DLX 指向本測試專屬的 DLQ）。
     */
    private void declareTopology() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        workQueue = "proof.work." + suffix;
        deadQueue = "proof.dead." + suffix;
        DirectExchange dlx = new DirectExchange("proof.dlx." + suffix, false, true);
        Queue dead = new Queue(deadQueue, false, false, true);
        Queue work = QueueBuilder.nonDurable(workQueue)
                .deadLetterExchange(dlx.getName())
                .deadLetterRoutingKey("dead")
                .build();
        admin.declareExchange(dlx);
        admin.declareQueue(dead);
        admin.declareQueue(work);
        Binding binding = BindingBuilder.bind(dead).to(dlx).with("dead");
        admin.declareBinding(binding);
    }

    /**
     * 啟動與正式設定同型的容器：stateful retry（3 次）＋ RejectAndDontRequeueRecoverer，唯 ack 模式由參數決定。
     *
     * @param mode     ack 模式
     * @param attempts 記錄 listener 被呼叫的次數
     * @param failUpTo 前幾次呼叫要拋例外（{@code Integer.MAX_VALUE} 表示永遠失敗）
     */
    private void startContainer(AcknowledgeMode mode, AtomicInteger attempts, int failUpTo) {
        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(MAX_ATTEMPTS));
        FixedBackOffPolicy backOff = new FixedBackOffPolicy();
        backOff.setBackOffPeriod(100L);
        retryTemplate.setBackOffPolicy(backOff);

        container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(workQueue);
        container.setAcknowledgeMode(mode);
        container.setPrefetchCount(1);
        container.setAdviceChain(RetryInterceptorBuilder.stateful()
                .retryOperations(retryTemplate)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        container.setMessageListener(message -> {
            int n = attempts.incrementAndGet();
            if (n <= failUpTo) {
                throw new IllegalStateException("模擬業務失敗，第 " + n + " 次");
            }
        });
        container.afterPropertiesSet();
        container.start();
    }

    /**
     * 發送一則帶 messageId 的訊息（stateful retry 以 messageId 追蹤嘗試次數）。
     */
    private void publish() {
        Message message = MessageBuilder.withBody("{}".getBytes(StandardCharsets.UTF_8))
                .setMessageId(UUID.randomUUID().toString())
                .build();
        template.send("", workQueue, message);
    }

    /**
     * 讀取 queue 的 {@code messages_ready}／{@code messages_unacknowledged}（Management API）。
     *
     * @param queue queue 名稱
     * @return 長度為 2 的陣列：[ready, unacked]；統計尚未產生時回傳 [-1, -1]
     */
    private int[] counts(String queue) {
        try {
            String auth = Base64.getEncoder().encodeToString(
                    (rabbit.getAdminUsername() + ":" + rabbit.getAdminPassword()).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(rabbit.getHttpUrl() + "/api/queues/%2F/" + queue))
                    .header("Authorization", "Basic " + auth)
                    .GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new int[]{-1, -1};
            }
            JsonNode node = JSON.readTree(response.body());
            if (!node.has("messages_ready") || !node.has("messages_unacknowledged")) {
                return new int[]{-1, -1};
            }
            return new int[]{node.get("messages_ready").asInt(), node.get("messages_unacknowledged").asInt()};
        } catch (Exception e) {
            throw new IllegalStateException("讀取 Management API 失敗", e);
        }
    }

    @Test
    @DisplayName("MANUAL：listener 拋一般例外 → 容器不 nack，訊息停在 unacked，不重投、不進 DLQ（retry interceptor 失效）")
    void manualAck_listenerThrows_messageStaysUnackedAndIsNeverRetried() {
        declareTopology();
        AtomicInteger attempts = new AtomicInteger();
        startContainer(AcknowledgeMode.MANUAL, attempts, Integer.MAX_VALUE);

        publish();

        await().atMost(WAIT_LIMIT).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(counts(workQueue)).as("[ready, unacked]").containsExactly(0, 1));
        assertThat(attempts).as("listener 只被呼叫 1 次：沒有 nack ⇒ 沒有重投 ⇒ 沒有第 2 次機會").hasValue(1);
        assertThat(template.receive(deadQueue)).as("沒有 nack ⇒ 不會進 DLQ").isNull();

        container.stop();
        await().atMost(WAIT_LIMIT).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(counts(workQueue))
                        .as("channel 關閉後 broker 才把 unacked 重新入列").containsExactly(1, 0));
    }

    @Test
    @DisplayName("AUTO：第 1、2 次失敗、第 3 次成功 → 共 3 次呼叫，queue 清空（ready=0, unacked=0），DLQ 沒有訊息")
    void autoAck_failTwiceThenSucceed_isRetriedAndAcked() {
        declareTopology();
        AtomicInteger attempts = new AtomicInteger();
        startContainer(AcknowledgeMode.AUTO, attempts, 2);

        publish();

        await().atMost(WAIT_LIMIT).untilAsserted(() -> assertThat(attempts).hasValue(3));
        await().atMost(WAIT_LIMIT).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(counts(workQueue)).as("[ready, unacked]").containsExactly(0, 0));
        assertThat(template.receive(deadQueue)).as("最終成功 ⇒ 不進 DLQ").isNull();
    }

    @Test
    @DisplayName("AUTO：持續失敗 → 嘗試 3 次後 recoverer 拒絕且不重新入列 → DLX → DLQ 收到該訊息")
    void autoAck_alwaysFails_exhaustsRetriesAndGoesToDeadLetterQueue() {
        declareTopology();
        AtomicInteger attempts = new AtomicInteger();
        startContainer(AcknowledgeMode.AUTO, attempts, Integer.MAX_VALUE);

        publish();

        await().atMost(WAIT_LIMIT).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(template.receive(deadQueue)).as("DLQ 應收到耗盡重試的訊息").isNotNull());
        assertThat(attempts).as("恰好嘗試 MAX_ATTEMPTS 次").hasValue(MAX_ATTEMPTS);
        await().atMost(WAIT_LIMIT).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(counts(workQueue)).as("work queue 不殘留").containsExactly(0, 0));
    }
}
