package dowob.xyz.blog.module.file.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleContentChangedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.infrastructure.mq.ThrowingRunnable;
import dowob.xyz.blog.module.file.service.ArticleFileBindingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link FileArticleContentChangedConsumer} 單元測試（W5A／Q12）。
 *
 * <p>{@link ConsumerExecutor} 以 mock 取代並讓 {@code executeIdempotent}／{@code execute} 直接執行 body：
 * 冪等去重本身由 {@code ConsumerExecutor} 的測試與本 consumer 的 IT 驗證，這裡只驗證委派與契約。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FileArticleContentChangedConsumer")
class FileArticleContentChangedConsumerTest {

    @Mock private ArticleFileBindingService bindingService;
    @Mock private ConsumerExecutor consumerExecutor;

    private FileArticleContentChangedConsumer consumer;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(inv -> {
            ((ThrowingRunnable) inv.getArgument(3)).run();
            return null;
        }).when(consumerExecutor).executeIdempotent(any(), any(), any(), any());
        doAnswer(inv -> {
            ((ThrowingRunnable) inv.getArgument(2)).run();
            return null;
        }).when(consumerExecutor).execute(any(), any(), any());
        consumer = new FileArticleContentChangedConsumer(bindingService, consumerExecutor);
    }

    private static ArticleContentChangedEvent event(UUID eventId, UUID articleUuid, ArticleContentChangedEvent.Action action) {
        return new ArticleContentChangedEvent(eventId, articleUuid, UUID.randomUUID(), action, Instant.now());
    }

    @Test
    @DisplayName("SAVED／PUBLISHED／RESTORED：皆以 eventId 走 executeIdempotent，並依文章最新內文回填綁定")
    void onContentChanged_everyAction_rebindsViaIdempotentExecution() {
        for (ArticleContentChangedEvent.Action action : ArticleContentChangedEvent.Action.values()) {
            UUID eventId = UUID.randomUUID();
            UUID articleUuid = UUID.randomUUID();

            consumer.onContentChanged(event(eventId, articleUuid, action));

            verify(consumerExecutor).executeIdempotent(
                    eq(FileArticleContentChangedConsumer.CONSUMER_NAME), any(Supplier.class), eq(eventId), any());
            verify(bindingService).rebindFromArticleContent(articleUuid);
        }
    }

    @Test
    @DisplayName("consumer 名稱為 file.article-binding（processed_events 的去重 key 之一）")
    void consumerName_isStable() {
        org.assertj.core.api.Assertions.assertThat(FileArticleContentChangedConsumer.CONSUMER_NAME)
                .isEqualTo("file.article-binding");
    }

    @Test
    @DisplayName("缺 articleUuid 的 payload：不處理、不進冪等交易、正常返回（避免無限重送）")
    void onContentChanged_nullArticleUuid_skips() {
        consumer.onContentChanged(event(UUID.randomUUID(), null, ArticleContentChangedEvent.Action.SAVED));

        verifyNoInteractions(bindingService);
        verify(consumerExecutor, never()).executeIdempotent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("回填失敗：例外往外拋（交由容器重試，耗盡進 DLQ）")
    void onContentChanged_bindingFails_rethrows() {
        UUID articleUuid = UUID.randomUUID();
        doThrow(new IllegalStateException("db down")).when(bindingService).rebindFromArticleContent(articleUuid);

        assertThatThrownBy(() -> consumer.onContentChanged(
                event(UUID.randomUUID(), articleUuid, ArticleContentChangedEvent.Action.SAVED)))
                .isInstanceOf(IllegalStateException.class);
    }
}
