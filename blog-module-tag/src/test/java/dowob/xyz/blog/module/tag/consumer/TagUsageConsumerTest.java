package dowob.xyz.blog.module.tag.consumer;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.event.ArticleTagEvent;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.infrastructure.mq.ThrowingRunnable;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.repository.ArticleTagRepository;
import dowob.xyz.blog.module.tag.repository.TagRepository;
import dowob.xyz.blog.module.tag.service.TagUsageRecalculator;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TagUsageConsumer unit tests（DATA-01／TEST-02 改寫）。
 *
 * <p>原本的測試把「每收到一次事件就 +1、Redis incrementScore +1」寫成規格，會擋下 DATA-01／RACE-07 的修法。
 * 新語意：收到事件 → 對受影響標籤<b>重算</b>（已發布文章數）→ 寫回 {@code usage_count}、
 * Redis 熱門分數以 ZADD 設為該絕對值。被測物件使用真實的 {@link TagUsageRecalculator}，
 * 只替換 repository／facade／Redis 邊界，因此驗證的是完整的「事件 → 計數」行為。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
@DisplayName("TagUsageConsumer unit tests")
class TagUsageConsumerTest {

    @Mock
    private TagRepository tagRepository;

    @Mock
    private ArticleTagRepository articleTagRepository;

    @Mock
    private ArticleFacade articleFacade;

    @Mock
    private RedisTemplate<String, String> stringRedisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOps;

    @Mock
    private IdempotencyService idempotencyService;

    /** 交易管理員替身：用來觀察 commit／rollback（getTransaction 回傳簡易 status） */
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    /** 真實的 ack/nack/冪等共用包裝（非 Mock），搭配 Mock 的 IdempotencyService 與交易管理員 */
    private ConsumerExecutor consumerExecutor;

    private TagUsageConsumer tagUsageConsumer;

    /**
     * 建立被測物件：ConsumerExecutor 與 TagUsageRecalculator 使用真實實例，
     * 冪等服務為 Mock、交易由 Mock 交易管理員模擬。
     */
    @BeforeEach
    void buildSubjectUnderTest() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);
        consumerExecutor = new ConsumerExecutor(new TransactionTemplate(txManager), idempotencyService);
        TagUsageRecalculator recalculator =
                new TagUsageRecalculator(tagRepository, articleTagRepository, articleFacade, stringRedisTemplate);
        tagUsageConsumer = new TagUsageConsumer(recalculator, consumerExecutor);
    }

    /** 預設：首次處理（tryMark=true）；需要模擬重送的測試自行 override */
    @BeforeEach
    void setUpIdempotency() {
        when(idempotencyService.tryMark(any(), eq(TagUsageConsumer.CONSUMER_NAME))).thenReturn(true);
    }

    private static Tag tag(UUID id, int usageCount) {
        Tag tag = new Tag();
        tag.setId(id);
        tag.setSlug("slug-" + id);
        tag.setUsageCount(usageCount);
        return tag;
    }

    /** 安排：標籤已存在、掛了 {@code articleIds} 這些文章，其中 {@code published} 為已發布 */
    private void givenTagWithArticles(UUID tagId, int currentCount, List<UUID> articleIds, Set<UUID> published) {
        when(tagRepository.findAllByIdInForUpdate(anyCollection())).thenAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(0);
            return ids.contains(tagId) ? List.of(tag(tagId, currentCount)) : List.of();
        });
        when(articleTagRepository.findArticleIdsByTagId(tagId)).thenReturn(articleIds);
        when(articleFacade.filterPublishedUuids(anyCollection())).thenAnswer(inv -> {
            Set<UUID> result = new HashSet<>(inv.<Collection<UUID>>getArgument(0));
            result.retainAll(published);
            return result;
        });
    }

    @Test
    @DisplayName("handleArticleTagged: 標籤掛 3 篇、2 篇已發布 → usage_count 重算為 2，Redis ZADD 為 2.0（不是 +1）")
    void handleArticleTagged_recalculatesPublishedCountInsteadOfIncrementing() throws IOException {
        UUID tagId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID draft = UUID.randomUUID();
        givenTagWithArticles(tagId, 0, List.of(p1, p2, draft), Set.of(p1, p2));

        tagUsageConsumer.handleArticleTagged(new ArticleTagEvent(UUID.randomUUID(), p1, List.of(tagId)));

        verify(tagRepository).updateUsageCount(tagId, 2);
        verify(zSetOps).add(RedisKeyConstant.TAG_HOT_KEY, tagId.toString(), 2.0);
        verify(zSetOps, never()).incrementScore(any(), any(), anyDouble());
    }

    @Test
    @DisplayName("handleArticleTagged: 舊計數被灌水（只增不減留下的）→ 事件到來時被修正回真實的已發布數")
    void handleArticleTagged_inflatedLegacyCount_isCorrected() throws IOException {
        UUID tagId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        givenTagWithArticles(tagId, 40, List.of(p1), Set.of(p1));

        tagUsageConsumer.handleArticleTagged(new ArticleTagEvent(UUID.randomUUID(), p1, List.of(tagId)));

        verify(tagRepository).updateUsageCount(tagId, 1);
        verify(zSetOps).add(RedisKeyConstant.TAG_HOT_KEY, tagId.toString(), 1.0);
    }

    @Test
    @DisplayName("handleArticleTagged: unknown tagId is silently skipped and acks")
    void handleArticlePublished_withUnknownTagId_skips() throws IOException {
        UUID unknownId = UUID.randomUUID();
        when(tagRepository.findAllByIdInForUpdate(anyCollection())).thenReturn(List.of());

        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), UUID.randomUUID(), List.of(unknownId));
        tagUsageConsumer.handleArticleTagged(event);

        verify(tagRepository, never()).updateUsageCount(any(), anyInt());
        verify(zSetOps, never()).add(any(), any(), anyDouble());
    }

    @Test
    @DisplayName("handleArticleTagged: 處理失敗時讓例外往外拋")
    void handleArticleTagged_onFailure_callsBasicNack() throws IOException {
        UUID tagId = UUID.randomUUID();
        when(tagRepository.findAllByIdInForUpdate(anyCollection())).thenThrow(new RuntimeException("DB error"));

        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), UUID.randomUUID(), List.of(tagId));
        assertThatThrownBy(() -> tagUsageConsumer.handleArticleTagged(event)).isInstanceOf(RuntimeException.class);
    }

    /**
     * 情境：同一事件重送兩次（相同 eventId）→ 冪等標記擋下第二次，重算只執行一次。
     */
    @Test
    @DisplayName("handleArticleTagged: 同一事件重送 → 冪等標記擋下，只重算一次")
    void handleArticleTagged_duplicateEvent_recalculatesOnlyOnce() throws IOException {
        UUID tagId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        givenTagWithArticles(tagId, 0, List.of(p1), Set.of(p1));
        // 首次 true、重送 false
        when(idempotencyService.tryMark(eq(eventId), eq(TagUsageConsumer.CONSUMER_NAME)))
                .thenReturn(true, false);

        ArticleTagEvent event = new ArticleTagEvent(eventId, p1, List.of(tagId));
        tagUsageConsumer.handleArticleTagged(event);
        tagUsageConsumer.handleArticleTagged(event);

        verify(tagRepository, times(1)).updateUsageCount(tagId, 1);
        verify(zSetOps, times(1)).add(RedisKeyConstant.TAG_HOT_KEY, tagId.toString(), 1.0);
    }

    /**
     * 情境：即使冪等標記沒擋下（舊訊息無 eventId、或標記遺失後 DLQ 重投），同一狀態重算多次結果也不變。
     * 這正是「重算」相對於「+1」的本質差異：重送不會灌水。
     */
    @Test
    @DisplayName("handleArticleTagged: 沒有冪等標記保護時重送 → 結果不變（Redis 分數仍是同一個絕對值，不累加）")
    void handleArticleTagged_replayWithoutIdempotencyMarker_sameResult() throws IOException {
        UUID tagId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        givenTagWithArticles(tagId, 1, List.of(p1), Set.of(p1));

        tagUsageConsumer.handleArticleTagged(new ArticleTagEvent(null, p1, List.of(tagId)));
        tagUsageConsumer.handleArticleTagged(new ArticleTagEvent(null, p1, List.of(tagId)));

        verify(tagRepository, never()).updateUsageCount(any(), anyInt());
        verify(zSetOps, times(2)).add(RedisKeyConstant.TAG_HOT_KEY, tagId.toString(), 1.0);
    }

    /**
     * 情境：舊訊息（eventId 為 null）→ 照舊處理，不呼叫 idempotencyService。
     */
    @Test
    @DisplayName("handleArticleTagged: eventId 為 null（舊訊息）→ 照舊處理不做去重")
    void handleArticleTagged_nullEventId_processesWithoutIdempotency() throws IOException {
        UUID tagId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        givenTagWithArticles(tagId, 0, List.of(p1), Set.of(p1));

        ArticleTagEvent event = new ArticleTagEvent(null, p1, List.of(tagId));
        tagUsageConsumer.handleArticleTagged(event);

        verify(tagRepository).updateUsageCount(tagId, 1);
        verify(idempotencyService, never()).tryMark(any(), any());
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
            tagUsageConsumer.handleArticleTagged(event);

            assertThat(count(registry, ConsumerExecutor.PROCESSED_METRIC, TagUsageConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, TagUsageConsumer.CONSUMER_NAME)).isZero();
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    /**
     * 情境：updateUsageCount 拋例外 → 不得被吞掉：往外拋、不 ack、failed 加一
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handleArticleTagged：寫入計數拋例外時 往外拋例外一次，計入 failed")
    void handleArticleTagged_onSaveFailure_nacksOnceAndDoesNotAck() throws Exception {
        UUID tagId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        givenTagWithArticles(tagId, 0, List.of(p1), Set.of(p1));
        when(tagRepository.updateUsageCount(eq(tagId), anyInt())).thenThrow(new RuntimeException("DB write error"));
        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), p1, List.of(tagId));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            assertThatThrownBy(() -> tagUsageConsumer.handleArticleTagged(event)).isInstanceOf(RuntimeException.class);
            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, TagUsageConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.DEAD_LETTERED_METRIC, TagUsageConsumer.CONSUMER_NAME)).as("dead_lettered 由容器層的 DeadLetterCountingErrorHandler 計，ConsumerExecutor 不計").isZero();
            assertThat(count(registry, ConsumerExecutor.PROCESSED_METRIC, TagUsageConsumer.CONSUMER_NAME)).isZero();
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    /**
     * 情境：Redis ZADD 拋例外 → 不得被吞掉：往外拋、不 ack、failed 加一
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handleArticleTagged：Redis ZADD 拋例外時 往外拋例外一次，計入 failed")
    void handleArticleTagged_onRedisFailure_nacksOnceAndDoesNotAck() throws Exception {
        UUID tagId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        givenTagWithArticles(tagId, 0, List.of(p1), Set.of(p1));
        when(zSetOps.add(eq(RedisKeyConstant.TAG_HOT_KEY), eq(tagId.toString()), anyDouble()))
                .thenThrow(new RuntimeException("Redis down"));
        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), p1, List.of(tagId));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            assertThatThrownBy(() -> tagUsageConsumer.handleArticleTagged(event)).isInstanceOf(RuntimeException.class);
            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, TagUsageConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.DEAD_LETTERED_METRIC, TagUsageConsumer.CONSUMER_NAME)).as("dead_lettered 由容器層的 DeadLetterCountingErrorHandler 計，ConsumerExecutor 不計").isZero();
            assertThat(count(registry, ConsumerExecutor.PROCESSED_METRIC, TagUsageConsumer.CONSUMER_NAME)).isZero();
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    /**
     * 情境：處理失敗 → failed 指標加一，且 ConsumerExecutor 的 ERROR 日誌保留業務上下文
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("handleArticleTagged：失敗時計入 failed 指標，ERROR 日誌保留業務上下文")
    void handleArticleTagged_onFailure_recordsMetricsAndLogsContext() throws Exception {
        UUID tagId = UUID.randomUUID();
        when(tagRepository.findAllByIdInForUpdate(anyCollection())).thenThrow(new RuntimeException("DB error"));
        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), UUID.randomUUID(), List.of(tagId));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            List<LogEvent> logs = captureExecutorLogs(() -> assertThatThrownBy(() -> tagUsageConsumer.handleArticleTagged(event)).isInstanceOf(RuntimeException.class));

            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, TagUsageConsumer.CONSUMER_NAME)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.DEAD_LETTERED_METRIC, TagUsageConsumer.CONSUMER_NAME)).as("dead_lettered 由容器層的 DeadLetterCountingErrorHandler 計，ConsumerExecutor 不計").isZero();
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
     * 情境：業務（寫 DB）失敗 → 標記所在的交易 rollback、不 commit，訊息 nack。
     *
     * @throws IOException basicNack 可能拋出的 IO 例外
     */
    @Test
    @DisplayName("handleArticleTagged: 業務失敗 → 冪等標記所在交易 rollback（不 commit）並往外拋例外")
    void handleArticleTagged_onBodyFailure_rollsBackMarkTransaction() throws IOException {
        UUID tagId = UUID.randomUUID();
        when(tagRepository.findAllByIdInForUpdate(anyCollection())).thenThrow(new RuntimeException("DB error"));
        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), UUID.randomUUID(), List.of(tagId));

        assertThatThrownBy(() -> tagUsageConsumer.handleArticleTagged(event)).isInstanceOf(RuntimeException.class);

        verify(idempotencyService).tryMark(event.eventId(), TagUsageConsumer.CONSUMER_NAME);
        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
    }

    /**
     * 情境：事件有兩個標籤，第 2 個標籤的 DB 寫入失敗 → 第 1 個標籤的 Redis 熱門分數也不得被設定
     * （DB 會 rollback，Redis 無法 rollback）。
     *
     * @throws IOException basicNack 可能拋出的 IO 例外
     */
    @Test
    @DisplayName("handleArticleTagged: 第 2 個標籤 DB 寫入失敗 → Redis ZSet 完全不被呼叫")
    void handleArticleTagged_secondTagDbWriteFails_neverTouchesRedis() throws IOException {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        when(tagRepository.findAllByIdInForUpdate(anyCollection()))
                .thenReturn(List.of(tag(firstId, 0), tag(secondId, 0)));
        when(articleTagRepository.findArticleIdsByTagId(any())).thenReturn(List.of(p1));
        when(articleFacade.filterPublishedUuids(anyCollection())).thenReturn(Set.of(p1));
        when(tagRepository.updateUsageCount(eq(secondId), anyInt())).thenThrow(new RuntimeException("DB write error"));
        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), p1, List.of(firstId, secondId));

        assertThatThrownBy(() -> tagUsageConsumer.handleArticleTagged(event)).isInstanceOf(RuntimeException.class);

        verify(stringRedisTemplate, never()).opsForZSet();
        verify(zSetOps, never()).add(any(), any(), anyDouble());
        verify(txManager).rollback(any());
    }

    /**
     * 情境：兩個標籤都成功 → 兩個標籤的 Redis 分數都被設定，且發生在所有 DB 寫入之後。
     *
     * @throws IOException basicAck 可能拋出的 IO 例外
     */
    @Test
    @DisplayName("handleArticleTagged: 所有 DB 寫入成功後才更新 Redis，兩個標籤都以 ZADD 設分")
    void handleArticleTagged_allDbWritesSucceed_setsRedisForEveryTag() throws IOException {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        when(tagRepository.findAllByIdInForUpdate(anyCollection()))
                .thenReturn(List.of(tag(firstId, 0), tag(secondId, 0)));
        when(articleTagRepository.findArticleIdsByTagId(any())).thenReturn(List.of(p1));
        when(articleFacade.filterPublishedUuids(anyCollection())).thenReturn(Set.of(p1));
        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), p1, List.of(firstId, secondId));

        tagUsageConsumer.handleArticleTagged(event);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(tagRepository, zSetOps);
        order.verify(tagRepository).updateUsageCount(secondId, 1);
        order.verify(zSetOps).add(RedisKeyConstant.TAG_HOT_KEY, firstId.toString(), 1.0);
        order.verify(zSetOps).add(RedisKeyConstant.TAG_HOT_KEY, secondId.toString(), 1.0);
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
