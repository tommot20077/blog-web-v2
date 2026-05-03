package dowob.xyz.blog.module.version.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent.Action;
import dowob.xyz.blog.module.version.service.AutoSnapshotPolicy;
import dowob.xyz.blog.module.version.service.VersioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArticleVersionConsumer 單元測試。
 *
 * <p>驗證共用 manual ACK container factory 下的訊息確認流程：
 * 處理成功呼叫 basicAck；處理失敗或 ACK 失敗呼叫 basicNack（不重新入隊，送 DLQ）。
 * 與 ArticlePublishedConsumerTest 對齊以維持全站慣例。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleVersionConsumer 單元測試")
class ArticleVersionConsumerTest {

    @Mock
    private VersioningService versioningService;

    @Mock
    private AutoSnapshotPolicy autoSnapshotPolicy;

    @Mock
    private Channel channel;

    private ArticleVersionConsumer consumer;

    private static final long ARTICLE_ID = 100L;
    private static final long DELIVERY_TAG = 42L;

    @BeforeEach
    void setUp() {
        consumer = new ArticleVersionConsumer(versioningService, autoSnapshotPolicy);
    }

    private ArticleContentChangedEvent event(Action action) {
        return new ArticleContentChangedEvent(
                ARTICLE_ID, UUID.randomUUID(), 1L, action, Instant.now());
    }

    @Test
    @DisplayName("SAVED + shouldSnapshot=true: 寫入快照並 basicAck")
    void onContentChanged_savedAndShouldSnapshot_recordsAndAcks() throws IOException {
        when(autoSnapshotPolicy.shouldSnapshot(ARTICLE_ID)).thenReturn(true);

        consumer.onContentChanged(event(Action.SAVED), channel, DELIVERY_TAG);

        verify(versioningService).recordAutoSnapshot(ARTICLE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("SAVED + shouldSnapshot=false: 不寫入但仍 basicAck")
    void onContentChanged_savedButPolicyFalse_skipsButAcks() throws IOException {
        when(autoSnapshotPolicy.shouldSnapshot(ARTICLE_ID)).thenReturn(false);

        consumer.onContentChanged(event(Action.SAVED), channel, DELIVERY_TAG);

        verify(versioningService, never()).recordAutoSnapshot(ARTICLE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("PUBLISHED: 凍結發布快照並 basicAck")
    void onContentChanged_published_freezesAndAcks() throws IOException {
        consumer.onContentChanged(event(Action.PUBLISHED), channel, DELIVERY_TAG);

        verify(versioningService).freezePublished(ARTICLE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("RESTORED: no-op 但仍 basicAck")
    void onContentChanged_restored_isNoOpButAcks() throws IOException {
        consumer.onContentChanged(event(Action.RESTORED), channel, DELIVERY_TAG);

        verify(versioningService, never()).recordAutoSnapshot(ARTICLE_ID);
        verify(versioningService, never()).freezePublished(ARTICLE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("Service 拋例外: 呼叫 basicNack（不重新入隊）")
    void onContentChanged_serviceThrows_callsBasicNack() throws IOException {
        when(autoSnapshotPolicy.shouldSnapshot(ARTICLE_ID)).thenReturn(true);
        doThrow(new RuntimeException("DB error")).when(versioningService).recordAutoSnapshot(ARTICLE_ID);

        consumer.onContentChanged(event(Action.SAVED), channel, DELIVERY_TAG);

        verify(channel).basicNack(eq(DELIVERY_TAG), eq(false), eq(false));
    }

    @Test
    @DisplayName("ACK 失敗時呼叫 basicNack 避免訊息卡住")
    void onContentChanged_ackThrows_callsBasicNack() throws IOException {
        when(autoSnapshotPolicy.shouldSnapshot(ARTICLE_ID)).thenReturn(true);
        doThrow(new IOException("ACK failed")).when(channel).basicAck(DELIVERY_TAG, false);

        consumer.onContentChanged(event(Action.SAVED), channel, DELIVERY_TAG);

        verify(channel).basicNack(eq(DELIVERY_TAG), eq(false), eq(false));
    }
}
