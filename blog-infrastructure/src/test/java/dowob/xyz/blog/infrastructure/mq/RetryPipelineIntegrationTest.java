package dowob.xyz.blog.infrastructure.mq;

import dowob.xyz.blog.infrastructure.config.RabbitMqConfig;
import dowob.xyz.blog.infrastructure.idempotency.IdempotencyAutoConfiguration;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Z3-b 重試管線的整合測試：<b>真 RabbitMQ ＋ 真 Postgres</b>（Testcontainers，版本與 {@code AbstractE2ETest} 一致），
 * 以正式的 {@link RabbitMqConfig}（容器工廠、重試攔截器、error handler、轉換器）、
 * 正式的 {@link ConsumerExecutor} 與 {@link dowob.xyz.blog.infrastructure.idempotency.IdempotencyService}
 * 組成完整的「發送 → 消費 → 重試 → 冪等 → DLQ」路徑。
 *
 * <p>容器參數鏡像 {@code application.yaml}（concurrency=2、max-concurrency=2、prefetch=1、ack=auto）；
 * yaml 本身的值由 blog-start 的 {@code RabbitListenerYamlBindingTest} 守衛。</p>
 *
 * <p><b>不使用固定睡眠猜時間</b>：一律以 Awaitility 輪詢並設上限；「不阻塞」以<b>事件先後</b>斷言
 * （正常訊息處理完成時，毒訊息仍在退避、尚未用完重試），不是以牆鐘時間斷言。
 * 命名為 {@code *IntegrationTest} 是因為 blog-infrastructure 的 surefire 沒有 {@code *IT} includes。需要 Docker。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = RetryPipelineIntegrationTest.TestApp.class)
@DisplayName("MQ 重試管線整合測試（真 RabbitMQ＋Postgres）")
class RetryPipelineIntegrationTest {

    /** 測試用 work queue（DLX 指向正式的 blog.dlq） */
    static final String WORK_QUEUE = "it.retry-pipeline.work";

    /** 測試用 consumer 名稱（指標 tag 與 processed_events.consumer_name） */
    static final String CONSUMER = "it.retry-pipeline";

    /** 正式 DLQ 名稱（RabbitMqConfig 宣告） */
    static final String DEAD_LETTER_QUEUE = "queue.dead-letter";

    /** 重試最大次數（含首次），對應 RabbitMqConfig 的 MAX_ATTEMPTS */
    static final int MAX_ATTEMPTS = 3;

    /** 輪詢等待上限 */
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(60);

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management-alpine");

    static {
        POSTGRES.start();
        RABBIT.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        JdbcTemplate bootstrap = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        bootstrap.execute("CREATE TABLE IF NOT EXISTS it_retry_business (id BIGSERIAL PRIMARY KEY, event_id UUID NOT NULL, note VARCHAR(50))");
        bootstrap.execute("CREATE TABLE IF NOT EXISTS it_retry_parent (id UUID PRIMARY KEY)");
        /* 延遲到 commit 才檢查的外鍵：讓「業務寫入後、commit 時才失敗」成為真實情境 */
        bootstrap.execute("CREATE TABLE IF NOT EXISTS it_retry_child (event_id UUID PRIMARY KEY, "
                + "parent_id UUID NOT NULL REFERENCES it_retry_parent(id) DEFERRABLE INITIALLY DEFERRED)");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.rabbitmq.listener.simple.acknowledge-mode", () -> "auto");
        registry.add("spring.rabbitmq.listener.simple.concurrency", () -> "2");
        registry.add("spring.rabbitmq.listener.simple.max-concurrency", () -> "2");
        registry.add("spring.rabbitmq.listener.simple.prefetch", () -> "1");
    }

    /** 測試用事件：eventId 即冪等 key；failTimes 為「前幾次嘗試要失敗」；mode 控制特殊失敗方式 */
    record TestEvent(UUID eventId, int failTimes, String mode) {
        static TestEvent ok() {
            return new TestEvent(UUID.randomUUID(), 0, "PLAIN");
        }

        static TestEvent failing(int failTimes) {
            return new TestEvent(UUID.randomUUID(), failTimes, "PLAIN");
        }
    }

    /** 測試用 Spring 組態：只載入 MQ／交易／冪等相關的正式元件 */
    @Configuration
    @ImportAutoConfiguration({RabbitAutoConfiguration.class, DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            TransactionAutoConfiguration.class})
    @Import({RabbitMqConfig.class, IdempotencyAutoConfiguration.class, ConsumerExecutor.class, TestListener.class})
    static class TestApp {

        @Bean
        Queue workQueue() {
            return QueueBuilder.durable(WORK_QUEUE)
                    .deadLetterExchange("blog.dlq")
                    .deadLetterRoutingKey("dead-letter")
                    .build();
        }
    }

    /** 測試用 listener：行為由事件內容決定，並記錄每次嘗試 */
    @Component
    static class TestListener {

        /** 每個 eventId 的嘗試次數（body 被呼叫的次數） */
        final Map<UUID, AtomicInteger> attempts = new ConcurrentHashMap<>();

        /** listener 方法被呼叫的總次數（轉換失敗時不會增加） */
        final AtomicInteger invocations = new AtomicInteger();

        /** 每個 eventId 收到的 AMQP messageId */
        final Map<UUID, String> messageIds = new ConcurrentHashMap<>();

        /** 每個 eventId 在 body 成功當下，記錄「毒訊息」的嘗試次數（供不阻塞斷言） */
        final Map<UUID, Integer> poisonAttemptsAtCompletion = new ConcurrentHashMap<>();

        /** 被觀察的毒訊息 eventId */
        volatile UUID observedPoison;

        @Autowired
        ConsumerExecutor executor;

        @Autowired
        JdbcTemplate jdbc;

        @RabbitListener(queues = WORK_QUEUE)
        public void handle(TestEvent event,
                           @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
            invocations.incrementAndGet();
            if (messageId != null) {
                messageIds.put(event.eventId(), messageId);
            }
            executor.executeIdempotent(CONSUMER, () -> "eventId=" + event.eventId(), event.eventId(), () -> {
                int attempt = attempts.computeIfAbsent(event.eventId(), k -> new AtomicInteger()).incrementAndGet();
                jdbc.update("INSERT INTO it_retry_business (event_id, note) VALUES (?, ?)",
                        event.eventId(), "attempt-" + attempt);
                if ("COMMIT_FAIL".equals(event.mode())) {
                    UUID parent = UUID.randomUUID();
                    if (attempt > 1) {
                        jdbc.update("INSERT INTO it_retry_parent (id) VALUES (?)", parent);
                    }
                    /* 第 1 次：parent 不存在 ⇒ 延遲外鍵在 commit 時才違反；第 2 次：先建 parent，commit 成功 */
                    jdbc.update("INSERT INTO it_retry_child (event_id, parent_id) VALUES (?, ?)", event.eventId(), parent);
                    return;
                }
                if ("CLASS_CAST".equals(event.mode())) {
                    throw new ClassCastException("模擬致命（不可重試）例外，eventId=" + event.eventId());
                }
                if (attempt <= event.failTimes()) {
                    throw new IllegalStateException("模擬業務失敗，第 " + attempt + " 次，eventId=" + event.eventId());
                }
                UUID poison = observedPoison;
                if (poison != null) {
                    poisonAttemptsAtCompletion.put(event.eventId(),
                            attempts.getOrDefault(poison, new AtomicInteger()).get());
                }
            });
        }
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestListener listener;

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        rabbitAdmin.purgeQueue(DEAD_LETTER_QUEUE, false);
        listener.observedPoison = null;
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(registry);
        registry.close();
    }

    private double consumerCount(String metric) {
        var counter = registry.find(metric).tag("consumer", CONSUMER).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double queueCount(String metric) {
        var counter = registry.find(metric).tag("queue", WORK_QUEUE).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private int businessRows(UUID eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM it_retry_business WHERE event_id = ?", Integer.class, eventId);
    }

    private int markerRows(UUID eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ? AND consumer_name = ?",
                Integer.class, eventId, CONSUMER);
    }

    private int attemptsOf(UUID eventId) {
        AtomicInteger n = listener.attempts.get(eventId);
        return n == null ? 0 : n.get();
    }

    /**
     * 等到 DLQ 收到內容含該 eventId 的訊息。
     *
     * @param eventId 事件 ID
     * @return DLQ 中的該訊息
     */
    private Message awaitDeadLetter(UUID eventId) {
        List<Message> collected = new ArrayList<>();
        await().atMost(WAIT_LIMIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Message m;
            while ((m = rabbitTemplate.receive(DEAD_LETTER_QUEUE)) != null) {
                collected.add(m);
            }
            assertThat(collected).as("DLQ 應收到 eventId=%s 的訊息", eventId)
                    .anyMatch(x -> isOf(x, eventId));
        });
        return collected.stream().filter(x -> isOf(x, eventId)).findFirst().orElseThrow();
    }

    /**
     * 訊息是否屬於該 ID（messageId 相同，或內容含該 ID——無 messageId 的殘留訊息只能比對內容）。
     */
    private static boolean isOf(Message message, UUID id) {
        return id.toString().equals(message.getMessageProperties().getMessageId())
                || new String(message.getBody(), StandardCharsets.UTF_8).contains(id.toString());
    }

    @Test
    @DisplayName("第 1 次失敗、第 2 次成功：業務確實執行（不是被冪等跳過），業務列與 processed_events 各一列，failed=1、processed=1")
    void failOnceThenSucceed_businessRunsAndMarkerCommittedOnce() {
        TestEvent event = TestEvent.failing(1);

        rabbitTemplate.convertAndSend("", WORK_QUEUE, event);

        await().atMost(WAIT_LIMIT).untilAsserted(() -> assertThat(markerRows(event.eventId())).isEqualTo(1));
        assertThat(attemptsOf(event.eventId())).as("body 執行 2 次：第 1 次失敗、第 2 次真的重做").isEqualTo(2);
        assertThat(businessRows(event.eventId())).as("第 1 次的業務寫入已 rollback，最終只剩第 2 次").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT note FROM it_retry_business WHERE event_id = ?", String.class, event.eventId()))
                .isEqualTo("attempt-2");
        assertThat(consumerCount(ConsumerExecutor.FAILED_METRIC)).isEqualTo(1.0);
        assertThat(consumerCount(ConsumerExecutor.PROCESSED_METRIC)).isEqualTo(1.0);
        assertThat(queueCount(ConsumerExecutor.DEAD_LETTERED_METRIC)).isZero();
    }

    @Test
    @DisplayName("業務寫入後、commit 時才失敗（延遲外鍵）：標記與業務一起 rollback，重試時業務再執行，最終只生效一次")
    void failureAtCommitTime_rollsBackMarkerAndBusiness_thenRetrySucceeds() {
        TestEvent event = new TestEvent(UUID.randomUUID(), 0, "COMMIT_FAIL");

        rabbitTemplate.convertAndSend("", WORK_QUEUE, event);

        await().atMost(WAIT_LIMIT).untilAsserted(() -> assertThat(markerRows(event.eventId())).isEqualTo(1));
        assertThat(attemptsOf(event.eventId())).as("第 1 次在 commit 時失敗、第 2 次成功").isEqualTo(2);
        assertThat(businessRows(event.eventId())).as("第 1 次的業務列隨 commit 失敗一起 rollback").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM it_retry_child WHERE event_id = ?", Integer.class, event.eventId()))
                .isEqualTo(1);
        assertThat(consumerCount(ConsumerExecutor.FAILED_METRIC)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("同一 eventId（同 messageId）重送兩次：只處理一次（業務一列、body 一次），兩則都被確認")
    void sameEventIdSentTwice_processedOnce() {
        TestEvent event = TestEvent.ok();

        rabbitTemplate.convertAndSend("", WORK_QUEUE, event);
        rabbitTemplate.convertAndSend("", WORK_QUEUE, event);

        await().atMost(WAIT_LIMIT).untilAsserted(
                () -> assertThat(consumerCount(ConsumerExecutor.PROCESSED_METRIC)).as("兩則都正常完成").isEqualTo(2.0));
        assertThat(attemptsOf(event.eventId())).as("第二則被冪等跳過，body 只跑一次").isEqualTo(1);
        assertThat(businessRows(event.eventId())).isEqualTo(1);
        assertThat(markerRows(event.eventId())).isEqualTo(1);
    }

    @Test
    @DisplayName("超過重試次數：嘗試 3 次後進 queue.dead-letter；failed=3、dead_lettered=1；業務與標記都沒留下")
    void exhaustedRetries_goToDeadLetterQueue_withCounters() {
        TestEvent event = TestEvent.failing(Integer.MAX_VALUE);

        rabbitTemplate.convertAndSend("", WORK_QUEUE, event);

        Message deadLetter = awaitDeadLetter(event.eventId());
        assertThat(deadLetter.getMessageProperties().getMessageId()).isEqualTo(event.eventId().toString());
        assertThat(attemptsOf(event.eventId())).isEqualTo(MAX_ATTEMPTS);
        assertThat(consumerCount(ConsumerExecutor.FAILED_METRIC)).as("每次嘗試失敗各計一次").isEqualTo(3.0);
        assertThat(queueCount(ConsumerExecutor.DEAD_LETTERED_METRIC)).isEqualTo(1.0);
        assertThat(consumerCount(ConsumerExecutor.PROCESSED_METRIC)).isZero();
        assertThat(businessRows(event.eventId())).as("每次嘗試都 rollback").isZero();
        assertThat(markerRows(event.eventId())).as("失敗不留標記，DLQ 人工重投時才會真的重做").isZero();
    }

    @Test
    @DisplayName("毒訊息重試（退避睡眠）期間，同 queue 的多則正常訊息都不被阻塞：每則完成當下毒訊息仍未用完重試")
    void poisonMessageDoesNotBlockOtherMessagesOnSameQueue() {
        TestEvent poison = TestEvent.failing(Integer.MAX_VALUE);
        listener.observedPoison = poison.eventId();
        rabbitTemplate.convertAndSend("", WORK_QUEUE, poison);
        await().atMost(WAIT_LIMIT).untilAsserted(() ->
                assertThat(attemptsOf(poison.eventId())).as("等毒訊息進入第 2 次嘗試之後（接著會睡約 5 秒才第 3 次）").isGreaterThanOrEqualTo(2));
        /* 多則而非一則：prefetch 過大時，被睡眠中的執行緒囤在緩衝裡的訊息會被一起卡住，
         * 訊息夠多才能在輪詢分派下必然有幾則落到睡眠中的那條 consumer 上，使這個守衛對 prefetch 設定有鑑別力 */
        List<TestEvent> normals = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            normals.add(TestEvent.ok());
        }

        normals.forEach(normal -> rabbitTemplate.convertAndSend("", WORK_QUEUE, normal));

        await().atMost(WAIT_LIMIT).untilAsserted(() -> assertThat(normals)
                .allSatisfy(normal -> assertThat(markerRows(normal.eventId())).isEqualTo(1)));
        assertThat(normals).allSatisfy(normal -> assertThat(listener.poisonAttemptsAtCompletion.get(normal.eventId()))
                .as("正常訊息完成當下，毒訊息最多只嘗試過 2 次——若被頭部阻塞（睡眠中的執行緒或其 prefetch 緩衝），這個值會是 3")
                .isLessThan(MAX_ATTEMPTS));
        awaitDeadLetter(poison.eventId());
    }

    @Test
    @DisplayName("訊息轉換失敗：listener 不被呼叫、不重試，直接進 DLQ；dead_lettered=1、failed=0")
    void conversionFailure_goesStraightToDeadLetterQueue_withoutRetry() {
        int invocationsBefore = listener.invocations.get();
        UUID messageId = UUID.randomUUID();
        Message garbage = MessageBuilder.withBody("{ this is not json".getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setMessageId(messageId.toString())
                .build();

        rabbitTemplate.send("", WORK_QUEUE, garbage);

        Message deadLetter = awaitDeadLetter(messageId);
        assertThat(deadLetter.getMessageProperties().getMessageId()).isEqualTo(messageId.toString());
        assertThat(listener.invocations.get()).as("轉換在 listener 之前就失敗").isEqualTo(invocationsBefore);
        assertThat(consumerCount(ConsumerExecutor.FAILED_METRIC)).isZero();
        assertThat(queueCount(ConsumerExecutor.DEAD_LETTERED_METRIC)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("沒有 messageId 的殘留訊息：失敗時只嘗試 1 次（無法計次，不重試）就進 DLQ；成功則正常處理")
    void messageWithoutMessageId_failsOnce_thenDeadLettered() {
        TestEvent failing = TestEvent.failing(Integer.MAX_VALUE);
        TestEvent succeeding = TestEvent.ok();

        rabbitTemplate.send("", WORK_QUEUE, withoutMessageId(failing));
        rabbitTemplate.send("", WORK_QUEUE, withoutMessageId(succeeding));

        awaitDeadLetter(failing.eventId());
        await().atMost(WAIT_LIMIT).untilAsserted(() -> assertThat(markerRows(succeeding.eventId())).isEqualTo(1));
        assertThat(attemptsOf(failing.eventId())).as("沒有 messageId ⇒ 不重試").isEqualTo(1);
        assertThat(consumerCount(ConsumerExecutor.FAILED_METRIC)).isEqualTo(1.0);
        assertThat(queueCount(ConsumerExecutor.DEAD_LETTERED_METRIC)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("發送端以 RabbitTemplate 發出的事件：AMQP messageId 等於 eventId（stateful retry 的追蹤依據）")
    void publishedEvent_carriesEventIdAsMessageId() {
        TestEvent event = TestEvent.ok();

        rabbitTemplate.convertAndSend("", WORK_QUEUE, event);

        await().atMost(WAIT_LIMIT).untilAsserted(() -> assertThat(listener.messageIds).containsKey(event.eventId()));
        assertThat(listener.messageIds.get(event.eventId())).isEqualTo(event.eventId().toString());
    }

    @Test
    @DisplayName("DLQ 重投（broker 加了 x-death）的訊息再次轉換失敗：仍回到 DLQ、不被靜默丟棄；dead_lettered 累計 2")
    void redrivenMessageWithXDeath_conversionFailure_returnsToDeadLetterQueue() {
        UUID messageId = UUID.randomUUID();
        Message garbage = MessageBuilder.withBody("{ this is not json".getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setMessageId(messageId.toString())
                .build();
        rabbitTemplate.send("", WORK_QUEUE, garbage);
        Message firstDeadLetter = awaitDeadLetter(messageId);
        assertThat(firstDeadLetter.getMessageProperties().getXDeathHeader())
                .as("broker 死信時加上的 x-death（維運 shovel／move 回原 queue 時會帶著）").isNotEmpty();

        rabbitTemplate.send("", WORK_QUEUE, firstDeadLetter);

        awaitDeadLetter(messageId);
        assertThat(queueCount(ConsumerExecutor.DEAD_LETTERED_METRIC)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("DLQ 重投（broker 加了 x-death）的訊息業務拋 ClassCastException（致命）：仍回到 DLQ；每次投遞各嘗試 1 次")
    void redrivenMessageWithXDeath_fatalBusinessException_returnsToDeadLetterQueue() {
        TestEvent event = new TestEvent(UUID.randomUUID(), 0, "CLASS_CAST");
        rabbitTemplate.convertAndSend("", WORK_QUEUE, event);
        Message firstDeadLetter = awaitDeadLetter(event.eventId());
        assertThat(firstDeadLetter.getMessageProperties().getXDeathHeader()).isNotEmpty();

        rabbitTemplate.send("", WORK_QUEUE, firstDeadLetter);

        awaitDeadLetter(event.eventId());
        assertThat(attemptsOf(event.eventId())).as("致命例外不重試：兩次投遞各 1 次").isEqualTo(2);
        assertThat(queueCount(ConsumerExecutor.DEAD_LETTERED_METRIC)).isEqualTo(2.0);
    }

    /**
     * 組出沒有 messageId 的 JSON 訊息（模擬部署前殘留的舊訊息）。
     *
     * @param event 事件
     * @return 不含 messageId 的原始訊息
     */
    private static Message withoutMessageId(TestEvent event) {
        String json = "{\"eventId\":\"" + event.eventId() + "\",\"failTimes\":" + event.failTimes()
                + ",\"mode\":\"" + event.mode() + "\"}";
        return MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .build();
    }
}
