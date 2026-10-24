package dowob.xyz.blog.module.search.listener;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.infrastructure.mq.ThrowingRunnable;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.listener.dto.ArticleArchivedMessage;
import dowob.xyz.blog.module.search.listener.dto.ArticleDeletedMessage;
import dowob.xyz.blog.module.search.listener.dto.ArticlePublishedMessage;
import dowob.xyz.blog.module.search.service.SearchService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * ArticleSearchListener 單元測試
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleSearchListener 單元測試")
class ArticleSearchListenerTest {

    @Mock
    private SearchService searchService;

    @Mock
    private Channel channel;

    /** 真實的 ack/nack 共用包裝（Spy 實例，非 Mock，使 ack/nack 與計數走真實路徑） */
    @Spy
    private ConsumerExecutor consumerExecutor = new ConsumerExecutor();

    @InjectMocks
    private ArticleSearchListener listener;

    /**
     * 建立完整 ArticlePublishedMessage 測試資料
     */
    private ArticlePublishedMessage buildPublishedMessage() {
        ArticlePublishedMessage message = new ArticlePublishedMessage();
        message.setArticleUuid(UUID.randomUUID());
        message.setAuthorId(1L);
        message.setTitle("測試標題");
        message.setSummary("測試摘要");
        message.setContentText("測試內容純文字");
        message.setSlug("test-slug");
        message.setPublishedAt(LocalDateTime.now());
        message.setAuthorUsername("yuan");
        message.setAuthorNickname("Yuan");
        message.setTags(List.of());
        return message;
    }

    /**
     * 文章發布事件測試
     */
    @Nested
    @DisplayName("onArticlePublished")
    class OnArticlePublishedTests {

        @Test
        @DisplayName("正常：收到發布訊息時，應建立 ES 索引並 ACK")
        void onArticlePublished_shouldIndexAndAck() throws IOException {
            ArticlePublishedMessage message = buildPublishedMessage();

            listener.onArticlePublished(message, channel, 1L);

            verify(searchService).indexArticle(any(ArticleDocument.class));
            verify(channel).basicAck(1L, false);
        }

        @Test
        @DisplayName("正常：標籤為 null 時，應正常建立索引（不拋出例外）")
        void onArticlePublished_withNullTags_shouldIndexAndAck() throws IOException {
            ArticlePublishedMessage message = buildPublishedMessage();
            message.setTags(null);

            listener.onArticlePublished(message, channel, 2L);

            verify(searchService).indexArticle(any(ArticleDocument.class));
            verify(channel).basicAck(2L, false);
        }

        @Test
        @DisplayName("異常：ES 索引失敗時，應 NACK 至 DLQ（不重排隊）")
        void onArticlePublished_onEsException_shouldNack() throws IOException {
            ArticlePublishedMessage message = buildPublishedMessage();

            doThrow(new RuntimeException("ES cluster unavailable")).when(searchService).indexArticle(any());

            listener.onArticlePublished(message, channel, 3L);

            verify(channel).basicNack(3L, false, false);
        }

        @Test
        @DisplayName("異常：basicAck 拋出 IOException 時，應觸發 basicNack")
        void onArticlePublished_onAckIoException_shouldNack() throws IOException {
            ArticlePublishedMessage message = buildPublishedMessage();

            doThrow(new IOException("channel broken")).when(channel).basicAck(4L, false);

            listener.onArticlePublished(message, channel, 4L);

            verify(channel).basicNack(4L, false, false);
        }
    }

    /**
     * 文章更新事件測試
     */
    @Nested
    @DisplayName("onArticleUpdated")
    class OnArticleUpdatedTests {

        @Test
        @DisplayName("正常：收到更新訊息時，應重新索引文章")
        void onArticleUpdated_shouldReindex() throws IOException {
            ArticlePublishedMessage message = new ArticlePublishedMessage();
            message.setArticleUuid(UUID.randomUUID());
            message.setAuthorId(1L);
            message.setTitle("更新後標題");
            message.setSummary("更新後摘要");
            message.setContentText("更新後內容");
            message.setSlug("updated-slug");
            message.setPublishedAt(LocalDateTime.now());
            message.setAuthorUsername("yuan");
            message.setAuthorNickname("Yuan");
            message.setTags(List.of());

            listener.onArticleUpdated(message, channel, 1L);

            verify(searchService).indexArticle(any(ArticleDocument.class));
            verify(channel).basicAck(1L, false);
        }

        @Test
        @DisplayName("異常：重新索引失敗時，應 NACK 至 DLQ")
        void onArticleUpdated_onError_shouldNack() throws IOException {
            ArticlePublishedMessage message = new ArticlePublishedMessage();
            message.setArticleUuid(UUID.randomUUID());
            message.setAuthorId(1L);
            message.setTitle("會失敗的標題");
            message.setTags(List.of());

            doThrow(new RuntimeException("ES down")).when(searchService).indexArticle(any());

            listener.onArticleUpdated(message, channel, 2L);

            verify(channel).basicNack(2L, false, false);
        }
    }

    /**
     * 文章下架事件測試
     *
     * <p>下架（PUBLISHED → ARCHIVED）後文章不再公開，索引必須移除，
     * 否則搜尋結果仍會曝光已下架文章的標題與內文摘要。</p>
     */
    @Nested
    @DisplayName("onArticleArchived")
    class OnArticleArchivedTests {

        @Test
        @DisplayName("正常：收到下架訊息時，應移除該文章的 ES 索引並 ACK")
        void onArticleArchived_shouldDeleteIndexAndAck() throws IOException {
            UUID uuid = UUID.randomUUID();
            ArticleArchivedMessage message = new ArticleArchivedMessage();
            message.setEventId(UUID.randomUUID());
            message.setArticleUuid(uuid);
            message.setOccurredAt(Instant.now());

            listener.onArticleArchived(message, channel, 1L);

            verify(searchService).deleteIndex(uuid.toString());
            verify(channel).basicAck(1L, false);
        }

        @Test
        @DisplayName("異常：移除索引失敗時，應 NACK 至 DLQ（不 requeue）")
        void onArticleArchived_onError_shouldNack() throws IOException {
            UUID uuid = UUID.randomUUID();
            ArticleArchivedMessage message = new ArticleArchivedMessage();
            message.setEventId(UUID.randomUUID());
            message.setArticleUuid(uuid);
            message.setOccurredAt(Instant.now());

            doThrow(new RuntimeException("ES down")).when(searchService).deleteIndex(any());

            listener.onArticleArchived(message, channel, 7L);

            verify(channel).basicNack(7L, false, false);
        }

        /**
         * requeue=false 代表訊息直接進 DLQ，不會自動重試。
         * 下架是合規動作，這條路徑失敗＝文章仍留在搜尋索引裡，必須留下可告警、
         * 且足以人工補送的紀錄：ERROR 等級 + articleUuid（要刪哪一篇）+ eventId（對得回原事件）。
         */
        @Test
        @DisplayName("可觀測性：移除索引失敗時，ERROR 日誌須帶 articleUuid 與 eventId")
        void onArticleArchived_onError_logsErrorWithUuidAndEventId() throws IOException {
            UUID uuid = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            ArticleArchivedMessage message = new ArticleArchivedMessage();
            message.setEventId(eventId);
            message.setArticleUuid(uuid);
            message.setOccurredAt(Instant.now());

            doThrow(new RuntimeException("ES down")).when(searchService).deleteIndex(any());

            CapturingAppender appender = attachAppender();
            try {
                listener.onArticleArchived(message, channel, 7L);
            } finally {
                detachAppender(appender);
            }

            assertThat(appender.events).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getMessage().getFormattedMessage())
                        .contains(uuid.toString())
                        .contains(eventId.toString());
            });
        }
    }

    /**
     * 掛上捕捉用 appender 以取得 {@link ConsumerExecutor} 的日誌事件（失敗日誌已收斂至 ConsumerExecutor）。
     *
     * @return 已啟動並掛載的 appender
     */
    private CapturingAppender attachAppender() {
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        ((Logger) LogManager.getLogger(ConsumerExecutor.class)).addAppender(appender);
        return appender;
    }

    /**
     * 卸載測試用 appender，避免污染其他測試。
     *
     * @param appender 先前掛上的 appender
     */
    private void detachAppender(CapturingAppender appender) {
        ((Logger) LogManager.getLogger(ConsumerExecutor.class)).removeAppender(appender);
        appender.stop();
    }

    /**
     * 捕捉日誌事件的測試用 appender（log4j2 為本專案的日誌實作，見 root pom 排除
     * spring-boot-starter-logging 並改用 log4j-slf4j2-impl）。
     */
    private static final class CapturingAppender extends AbstractAppender {

        /** 捕捉到的日誌事件 */
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("archive-test-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    /**
     * 文章刪除事件測試
     */
    @Nested
    @DisplayName("onArticleDeleted")
    class OnArticleDeletedTests {

        @Test
        @DisplayName("正常：收到刪除訊息時，應移除索引")
        void onArticleDeleted_shouldDeleteIndex() throws IOException {
            UUID uuid = UUID.randomUUID();
            ArticleDeletedMessage message = new ArticleDeletedMessage();
            message.setArticleUuid(uuid);
            message.setDeletedAt(Instant.now());

            listener.onArticleDeleted(message, channel, 1L);

            verify(searchService).deleteIndex(uuid.toString());
            verify(channel).basicAck(1L, false);
        }

        @Test
        @DisplayName("異常：移除索引失敗時，應 NACK 至 DLQ")
        void onArticleDeleted_onError_shouldNack() throws IOException {
            UUID uuid = UUID.randomUUID();
            ArticleDeletedMessage message = new ArticleDeletedMessage();
            message.setArticleUuid(uuid);
            message.setDeletedAt(Instant.now());

            doThrow(new RuntimeException("ES down")).when(searchService).deleteIndex(any());

            listener.onArticleDeleted(message, channel, 3L);

            verify(channel).basicNack(3L, false, false);
        }
    }

    /**
     * 情境：成功處理 → 經 ConsumerExecutor 計入 processed 指標（consumer tag 為 CONSUMER_NAME）
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("onArticlePublished：成功時 ConsumerExecutor 計入 processed 指標")
    void onArticlePublished_success_recordsProcessedMetric() throws Exception {
        ArticlePublishedMessage message = buildPublishedMessage();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            listener.onArticlePublished(message, channel, 5L);

            assertThat(count(registry, ConsumerExecutor.PROCESSED_METRIC, ArticleSearchListener.CONSUMER_NAME_INDEX)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ArticleSearchListener.CONSUMER_NAME_INDEX)).isZero();
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
    @DisplayName("onArticlePublished：失敗時計入 failed／dead_lettered 指標，ERROR 日誌保留業務上下文")
    void onArticlePublished_onFailure_recordsMetricsAndLogsContext() throws Exception {
        ArticlePublishedMessage message = buildPublishedMessage();
        doThrow(new RuntimeException("ES down")).when(searchService).indexArticle(any());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            List<LogEvent> logs = captureExecutorLogs(() -> listener.onArticlePublished(message, channel, 6L));

            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ArticleSearchListener.CONSUMER_NAME_INDEX)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.DEAD_LETTERED_METRIC, ArticleSearchListener.CONSUMER_NAME_INDEX)).isEqualTo(1.0);
            assertThat(logs).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getMessage().getFormattedMessage())
                        .contains(message.getArticleUuid().toString());
            });
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    /**
     * 情境：成功處理 → 經 ConsumerExecutor 計入 processed 指標（consumer tag 為 CONSUMER_NAME）
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("onArticleUpdated：成功時 ConsumerExecutor 計入 processed 指標")
    void onArticleUpdated_success_recordsProcessedMetric() throws Exception {
        ArticlePublishedMessage message = buildPublishedMessage();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            listener.onArticleUpdated(message, channel, 5L);

            assertThat(count(registry, ConsumerExecutor.PROCESSED_METRIC, ArticleSearchListener.CONSUMER_NAME_INDEX_UPDATE)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ArticleSearchListener.CONSUMER_NAME_INDEX_UPDATE)).isZero();
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
    @DisplayName("onArticleUpdated：失敗時計入 failed／dead_lettered 指標，ERROR 日誌保留業務上下文")
    void onArticleUpdated_onFailure_recordsMetricsAndLogsContext() throws Exception {
        ArticlePublishedMessage message = buildPublishedMessage();
        doThrow(new RuntimeException("ES down")).when(searchService).indexArticle(any());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            List<LogEvent> logs = captureExecutorLogs(() -> listener.onArticleUpdated(message, channel, 6L));

            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ArticleSearchListener.CONSUMER_NAME_INDEX_UPDATE)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.DEAD_LETTERED_METRIC, ArticleSearchListener.CONSUMER_NAME_INDEX_UPDATE)).isEqualTo(1.0);
            assertThat(logs).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getMessage().getFormattedMessage())
                        .contains(message.getArticleUuid().toString());
            });
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    /**
     * 情境：成功處理 → 經 ConsumerExecutor 計入 processed 指標（consumer tag 為 CONSUMER_NAME）
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("onArticleDeleted：成功時 ConsumerExecutor 計入 processed 指標")
    void onArticleDeleted_success_recordsProcessedMetric() throws Exception {
        ArticleDeletedMessage message = new ArticleDeletedMessage();
        message.setArticleUuid(UUID.randomUUID());
        message.setDeletedAt(Instant.now());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            listener.onArticleDeleted(message, channel, 5L);

            assertThat(count(registry, ConsumerExecutor.PROCESSED_METRIC, ArticleSearchListener.CONSUMER_NAME_INDEX_DELETE)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ArticleSearchListener.CONSUMER_NAME_INDEX_DELETE)).isZero();
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
    @DisplayName("onArticleDeleted：失敗時計入 failed／dead_lettered 指標，ERROR 日誌保留業務上下文")
    void onArticleDeleted_onFailure_recordsMetricsAndLogsContext() throws Exception {
        ArticleDeletedMessage message = new ArticleDeletedMessage();
        message.setArticleUuid(UUID.randomUUID());
        message.setDeletedAt(Instant.now());
        doThrow(new RuntimeException("ES down")).when(searchService).deleteIndex(any());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            List<LogEvent> logs = captureExecutorLogs(() -> listener.onArticleDeleted(message, channel, 6L));

            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ArticleSearchListener.CONSUMER_NAME_INDEX_DELETE)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.DEAD_LETTERED_METRIC, ArticleSearchListener.CONSUMER_NAME_INDEX_DELETE)).isEqualTo(1.0);
            assertThat(logs).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getMessage().getFormattedMessage())
                        .contains(message.getArticleUuid().toString());
            });
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }

    /**
     * 情境：成功處理 → 經 ConsumerExecutor 計入 processed 指標（consumer tag 為 CONSUMER_NAME）
     *
     * @throws Exception 測試過程中的例外
     */
    @Test
    @DisplayName("onArticleArchived：成功時 ConsumerExecutor 計入 processed 指標")
    void onArticleArchived_success_recordsProcessedMetric() throws Exception {
        ArticleArchivedMessage message = new ArticleArchivedMessage();
        message.setEventId(UUID.randomUUID());
        message.setArticleUuid(UUID.randomUUID());
        message.setOccurredAt(Instant.now());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            listener.onArticleArchived(message, channel, 5L);

            assertThat(count(registry, ConsumerExecutor.PROCESSED_METRIC, ArticleSearchListener.CONSUMER_NAME_INDEX_ARCHIVE)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ArticleSearchListener.CONSUMER_NAME_INDEX_ARCHIVE)).isZero();
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
    @DisplayName("onArticleArchived：失敗時計入 failed／dead_lettered 指標，ERROR 日誌保留業務上下文")
    void onArticleArchived_onFailure_recordsMetricsAndLogsContext() throws Exception {
        ArticleArchivedMessage message = new ArticleArchivedMessage();
        message.setEventId(UUID.randomUUID());
        message.setArticleUuid(UUID.randomUUID());
        message.setOccurredAt(Instant.now());
        doThrow(new RuntimeException("ES down")).when(searchService).deleteIndex(any());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            List<LogEvent> logs = captureExecutorLogs(() -> listener.onArticleArchived(message, channel, 6L));

            assertThat(count(registry, ConsumerExecutor.FAILED_METRIC, ArticleSearchListener.CONSUMER_NAME_INDEX_ARCHIVE)).isEqualTo(1.0);
            assertThat(count(registry, ConsumerExecutor.DEAD_LETTERED_METRIC, ArticleSearchListener.CONSUMER_NAME_INDEX_ARCHIVE)).isEqualTo(1.0);
            assertThat(logs).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getMessage().getFormattedMessage())
                        .contains(message.getArticleUuid().toString())
                        .contains(message.getEventId().toString());
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
    private List<LogEvent> captureExecutorLogs(ThrowingRunnable action) throws Exception {
        CapturingAppender appender = attachAppender();
        try {
            action.run();
        } finally {
            detachAppender(appender);
        }
        return appender.events;
    }
}
