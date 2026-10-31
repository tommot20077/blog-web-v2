package dowob.xyz.blog.e2e.mq;

import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * 正式 Spring context（<b>真實的 application.yaml</b>、真 RabbitMQ＋Postgres）中的 MQ 重試管線守衛（ARCH-02／PERF-15／PERF-30）。
 *
 * <p>{@code RetryPipelineIntegrationTest}（blog-infrastructure）以鏡像的參數驗證完整行為矩陣；本測試補上
 * 「正式 yaml 的 concurrency／prefetch／ack 模式」真的讓毒訊息不阻塞同 queue、重試耗盡真的進
 * {@code queue.dead-letter}、轉換失敗真的不重試。若有人改壞 yaml（例如 prefetch 調大、ack 改回 manual），
 * 這裡會失敗。</p>
 *
 * <p>不使用固定睡眠：Awaitility 輪詢＋上限；「不阻塞」以事件先後斷言（正常訊息完成當下毒訊息仍未用完重試）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Import(MqRetryWiringE2E.WiringConfig.class)
@DisplayName("MQ 重試管線（正式 context／yaml）")
class MqRetryWiringE2E extends AbstractE2ETest {

    /** 測試專用 work queue（DLX 指向正式的 blog.dlq） */
    static final String WORK_QUEUE = "e2e.retry-wiring.work";

    /** 測試 consumer 名稱 */
    static final String CONSUMER = "e2e.retry-wiring";

    /** 正式 DLQ */
    static final String DEAD_LETTER_QUEUE = "queue.dead-letter";

    /** 重試最大次數（含首次），對應 RabbitMqConfig 的 MAX_ATTEMPTS */
    static final int MAX_ATTEMPTS = 3;

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(60);

    /** 測試事件：eventId 為冪等 key；failTimes 為前幾次嘗試要失敗 */
    record Job(UUID eventId, int failTimes) { }

    /** 測試 listener 與 queue 宣告 */
    @TestConfiguration
    static class WiringConfig {

        @Bean
        Queue e2eRetryWorkQueue() {
            return QueueBuilder.durable(WORK_QUEUE)
                    .deadLetterExchange("blog.dlq")
                    .deadLetterRoutingKey("dead-letter")
                    .build();
        }

        @Bean
        RetryJobListener retryJobListener(ConsumerExecutor executor) {
            return new RetryJobListener(executor);
        }
    }

    /** 以正式的 ConsumerExecutor 處理測試事件 */
    static class RetryJobListener {

        final Map<UUID, AtomicInteger> attempts = new ConcurrentHashMap<>();
        final AtomicInteger invocations = new AtomicInteger();
        final Map<UUID, Integer> poisonAttemptsAtCompletion = new ConcurrentHashMap<>();
        volatile UUID observedPoison;
        private final ConsumerExecutor executor;

        RetryJobListener(ConsumerExecutor executor) {
            this.executor = executor;
        }

        @RabbitListener(queues = WORK_QUEUE)
        public void handle(Job job) {
            invocations.incrementAndGet();
            executor.executeIdempotent(CONSUMER, () -> "eventId=" + job.eventId(), job.eventId(), () -> {
                int attempt = attempts.computeIfAbsent(job.eventId(), k -> new AtomicInteger()).incrementAndGet();
                if (attempt <= job.failTimes()) {
                    throw new IllegalStateException("模擬失敗，第 " + attempt + " 次");
                }
                UUID poison = observedPoison;
                if (poison != null) {
                    poisonAttemptsAtCompletion.put(job.eventId(), attempts.getOrDefault(poison, new AtomicInteger()).get());
                }
            });
        }
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RetryJobListener listener;

    @Autowired
    private JdbcTemplate jdbc;

    private int markers(UUID eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ? AND consumer_name = ?",
                Integer.class, eventId, CONSUMER);
    }

    private Message awaitDeadLetter(UUID id) {
        List<Message> collected = new ArrayList<>();
        await().atMost(WAIT_LIMIT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Message m;
            while ((m = rabbitTemplate.receive(DEAD_LETTER_QUEUE)) != null) {
                collected.add(m);
            }
            assertThat(collected).anyMatch(x -> id.toString().equals(x.getMessageProperties().getMessageId())
                    || new String(x.getBody(), StandardCharsets.UTF_8).contains(id.toString()));
        });
        return collected.stream().filter(x -> id.toString().equals(x.getMessageProperties().getMessageId())
                || new String(x.getBody(), StandardCharsets.UTF_8).contains(id.toString())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("毒訊息在退避期間不阻塞同 queue 的多則正常訊息（正式 yaml 的 concurrency／prefetch）；毒訊息最終進 DLQ 且嘗試恰 3 次")
    void poisonDoesNotBlock_andEndsInDeadLetterQueue() {
        Job poison = new Job(UUID.randomUUID(), Integer.MAX_VALUE);
        listener.observedPoison = poison.eventId();
        rabbitTemplate.convertAndSend("", WORK_QUEUE, poison);
        await().atMost(WAIT_LIMIT).untilAsserted(() ->
                assertThat(listener.attempts.getOrDefault(poison.eventId(), new AtomicInteger()).get()).isGreaterThanOrEqualTo(2));
        List<Job> normals = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            normals.add(new Job(UUID.randomUUID(), 0));
        }

        normals.forEach(job -> rabbitTemplate.convertAndSend("", WORK_QUEUE, job));

        await().atMost(WAIT_LIMIT).untilAsserted(() ->
                assertThat(normals).allSatisfy(job -> assertThat(markers(job.eventId())).isEqualTo(1)));
        assertThat(normals).allSatisfy(job -> assertThat(listener.poisonAttemptsAtCompletion.get(job.eventId()))
                .as("正常訊息完成當下，毒訊息還在退避、未用完重試").isLessThan(MAX_ATTEMPTS));
        Message deadLetter = awaitDeadLetter(poison.eventId());
        assertThat(deadLetter.getMessageProperties().getMessageId()).isEqualTo(poison.eventId().toString());
        assertThat(listener.attempts.get(poison.eventId())).hasValue(MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("訊息轉換失敗：listener 不被呼叫、不重試，直接進 DLQ")
    void conversionFailure_goesStraightToDeadLetterQueue() {
        int before = listener.invocations.get();
        UUID id = UUID.randomUUID();
        Message garbage = MessageBuilder.withBody("{ not json".getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").setMessageId(id.toString()).build();

        rabbitTemplate.send("", WORK_QUEUE, garbage);

        awaitDeadLetter(id);
        assertThat(listener.invocations.get()).isEqualTo(before);
    }
}
