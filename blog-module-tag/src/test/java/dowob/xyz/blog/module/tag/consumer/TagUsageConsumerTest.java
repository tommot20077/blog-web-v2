package dowob.xyz.blog.module.tag.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.infrastructure.mq.ThrowingRunnable;
import dowob.xyz.blog.module.tag.event.ArticleTagEvent;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.repository.TagRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TagUsageConsumer unit tests
 *
 * @author Yuan
 * @version 1.0
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
@DisplayName("TagUsageConsumer unit tests")
class TagUsageConsumerTest {

    @Mock
    private TagRepository tagRepository;

    @Mock
    private RedisTemplate<String, String> stringRedisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOps;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private Channel channel;

    /** 真實的 ack/nack 共用包裝（Spy 實例，非 Mock，使 ack/nack 與計數走真實路徑） */
    @Spy
    private ConsumerExecutor consumerExecutor = new ConsumerExecutor();

    @InjectMocks
    private TagUsageConsumer tagUsageConsumer;

    /** 預設：首次處理（markProcessed=true）；需要模擬重送的測試自行 override */
    @BeforeEach
    void setUpIdempotency() {
        when(idempotencyService.markProcessed(any(), eq(TagUsageConsumer.CONSUMER_NAME))).thenReturn(true);
    }

    @Test
    @DisplayName("handleArticleTagged: known tagIds increment usageCount and acks")
    void handleArticlePublished_withTagIds_incrementsUsageCount() throws IOException {
        UUID tagId = UUID.randomUUID();
        Tag tag = new Tag();
        tag.setId(tagId);
        tag.setUsageCount(0);
        when(tagRepository.findById(tagId)).thenReturn(Optional.of(tag));
        when(tagRepository.save(any(Tag.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);

        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), UUID.randomUUID(), List.of(tagId));
        tagUsageConsumer.handleArticleTagged(event, channel, 10L);

        assertThat(tag.getUsageCount()).isEqualTo(1);
        verify(tagRepository).save(tag);
        verify(zSetOps).incrementScore(eq(RedisKeyConstant.TAG_HOT_KEY), eq(tagId.toString()), eq(1.0));
        verify(channel).basicAck(10L, false);
    }

    @Test
    @DisplayName("handleArticleTagged: unknown tagId is silently skipped and acks")
    void handleArticlePublished_withUnknownTagId_skips() throws IOException {
        UUID unknownId = UUID.randomUUID();
        when(tagRepository.findById(unknownId)).thenReturn(Optional.empty());

        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), UUID.randomUUID(), List.of(unknownId));
        tagUsageConsumer.handleArticleTagged(event, channel, 20L);

        verify(tagRepository, never()).save(any());
        verify(channel).basicAck(20L, false);
    }

    @Test
    @DisplayName("handleArticleTagged: 處理失敗時呼叫 basicNack")
    void handleArticleTagged_onFailure_callsBasicNack() throws IOException {
        UUID tagId = UUID.randomUUID();
        when(tagRepository.findById(tagId)).thenThrow(new RuntimeException("DB error"));

        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), UUID.randomUUID(), List.of(tagId));
        tagUsageConsumer.handleArticleTagged(event, channel, 30L);

        verify(channel).basicNack(30L, false, false);
    }

    /**
     * 情境：同一事件重送兩次（相同 eventId）→ 使用計數只增加一次。
     *
     * <p>usageCount 是非冪等 +1（且寫入 DB 為永久）；at-least-once 重送須以 eventId 去重,
     * 否則標籤使用數與 Redis 熱門分數會灌水。</p>
     */
    @Test
    @DisplayName("handleArticleTagged: 同一事件重送 → 使用計數只加一次（冪等）")
    void handleArticleTagged_duplicateEvent_incrementsOnlyOnce() throws IOException {
        UUID tagId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Tag tag = new Tag();
        tag.setId(tagId);
        tag.setUsageCount(0);
        when(tagRepository.findById(tagId)).thenReturn(Optional.of(tag));
        when(tagRepository.save(any(Tag.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);
        // 首次 true、重送 false
        when(idempotencyService.markProcessed(eq(eventId), eq(TagUsageConsumer.CONSUMER_NAME)))
                .thenReturn(true, false);

        ArticleTagEvent event = new ArticleTagEvent(eventId, UUID.randomUUID(), List.of(tagId));
        tagUsageConsumer.handleArticleTagged(event, channel, 1L);
        tagUsageConsumer.handleArticleTagged(event, channel, 2L);

        assertThat(tag.getUsageCount()).isEqualTo(1);
        verify(tagRepository, times(1)).save(tag);
        verify(zSetOps, times(1)).incrementScore(eq(RedisKeyConstant.TAG_HOT_KEY), eq(tagId.toString()), eq(1.0));
        verify(channel).basicAck(1L, false);
        verify(channel).basicAck(2L, false);
    }

    /**
     * 情境：舊訊息（eventId 為 null）→ 照舊處理，不呼叫 idempotencyService。
     */
    @Test
    @DisplayName("handleArticleTagged: eventId 為 null（舊訊息）→ 照舊處理不做去重")
    void handleArticleTagged_nullEventId_processesWithoutIdempotency() throws IOException {
        UUID tagId = UUID.randomUUID();
        Tag tag = new Tag();
        tag.setId(tagId);
        tag.setUsageCount(0);
        when(tagRepository.findById(tagId)).thenReturn(Optional.of(tag));
        when(tagRepository.save(any(Tag.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);

        ArticleTagEvent event = new ArticleTagEvent(null, UUID.randomUUID(), List.of(tagId));
        tagUsageConsumer.handleArticleTagged(event, channel, 7L);

        assertThat(tag.getUsageCount()).isEqualTo(1);
        verify(channel).basicAck(7L, false);
        verify(idempotencyService, never()).markProcessed(any(), any());
    }

    /**
     * 情境：成功處理 → 經 ConsumerExecutor 計入 processed 指標（consumer tag 為 CONSUMER_NAME）
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handleArticleTagged：成功時 ConsumerExecutor 計入 processed 指標")
    void handleArticleTagged_success_recordsProcessedMetric() throws Exception {
        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), UUID.randomUUID(), List.of());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            tagUsageConsumer.handleArticleTagged(event, channel, 5L);

            assertThat(count(registry, ConsumerExecutor.PROCESSED_METRIC, TagUsageConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, TagUsageConsumer.CONSUMER_NAME)).isZero();
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    /**
     * 情境：處理失敗 → failed／dead_lettered 指標加一，且 ConsumerExecutor 的 ERROR 日誌保留業務上下文
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handleArticleTagged：失敗時計入 failed／dead_lettered 指標，ERROR 日誌保留業務上下文")
    void handleArticleTagged_onFailure_recordsMetricsAndLogsContext() throws Exception {
        UUID tagId = UUID.randomUUID();
        when(tagRepository.findById(tagId)).thenThrow(new RuntimeException("DB error"));
        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), UUID.randomUUID(), List.of(tagId));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            List<LogEvent> logs = captureExecutorLogs(() -> tagUsageConsumer.handleArticleTagged(event, channel, 6L));

            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, TagUsageConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.DEAD_LETTERED_METRIC, TagUsageConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(logs).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getMessage().getFormattedMessage())
                        .contains(event.eventId().toString());
            });
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    /**
     * 讀取指定 consumer 的指標計數，指標不存在時視為 0。
     *
     * @param registry     測試用 registry
     * @param metric       指標名稱
     * @param consumerName consumer 識別名稱（指標的 consumer tag）
     * @return 計數值
     */
    private static double count(SimpleMeterRegistry registry, String metric, String consumerName) {
        Counter counter = registry.find(metric).tag("consumer", consumerName).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /**
     * 執行動作並捕捉 {@link ConsumerExecutor} 輸出的日誌事件。
     *
     * @param action 要執行的動作
     * @return 捕捉到的日誌事件
     * @throws Exception 動作拋出的例外
     */
    private static List<LogEvent> captureExecutorLogs(ThrowingRunnable action) throws Exception {
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        Logger logger = (Logger) LogManager.getLogger(ConsumerExecutor.class);
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
        return appender.events;
    }

    /** 捕捉日誌事件的測試用 appender（專案日誌實作為 log4j2） */
    private static final class CapturingAppender extends AbstractAppender {

        /** 捕捉到的日誌事件 */
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("consumer-executor-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
