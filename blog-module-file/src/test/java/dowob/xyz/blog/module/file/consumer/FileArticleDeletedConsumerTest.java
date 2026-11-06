package dowob.xyz.blog.module.file.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.infrastructure.mq.ThrowingRunnable;
import dowob.xyz.blog.module.file.service.FileArticleCleanupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link FileArticleDeletedConsumer} 單元測試（DATA-07）。
 *
 * <p>{@link ConsumerExecutor} 以 mock 取代並讓 {@code executeIdempotent}／{@code execute} 直接執行 body：
 * 去重本身由 ConsumerExecutor 的測試與本 consumer 的 IT 驗證，這裡只驗證委派與契約。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FileArticleDeletedConsumer")
class FileArticleDeletedConsumerTest {

    @Mock private FileArticleCleanupService cleanupService;
    @Mock private ConsumerExecutor consumerExecutor;

    private FileArticleDeletedConsumer consumer;

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
        consumer = new FileArticleDeletedConsumer(cleanupService, consumerExecutor);
    }

    private static ArticleDeletedEvent event(UUID eventId, UUID articleUuid) {
        return new ArticleDeletedEvent(eventId, articleUuid, List.of(), List.of(), Instant.now(), UUID.randomUUID(), null);
    }

    @Test
    @DisplayName("收到事件：以 eventId 走 executeIdempotent，並委派清理服務刪除綁定該文章的檔案")
    void onArticleDeleted_delegatesViaIdempotentExecution() {
        UUID eventId = UUID.randomUUID();
        UUID articleUuid = UUID.randomUUID();

        consumer.onArticleDeleted(event(eventId, articleUuid));

        verify(consumerExecutor).executeIdempotent(
                eq(FileArticleDeletedConsumer.CONSUMER_NAME), any(Supplier.class), eq(eventId), any());
        verify(cleanupService).deleteFilesBoundToDeletedArticle(articleUuid);
    }

    @Test
    @DisplayName("清理失敗（例如 MinIO 掛了）：例外往外拋，交由容器重試、耗盡進 DLQ")
    void onArticleDeleted_cleanupThrows_rethrows() {
        UUID articleUuid = UUID.randomUUID();
        doThrow(new IllegalStateException("MinIO down")).when(cleanupService).deleteFilesBoundToDeletedArticle(articleUuid);

        assertThatThrownBy(() -> consumer.onArticleDeleted(event(UUID.randomUUID(), articleUuid)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("缺 articleUuid 的 payload：記 warn 後返回（重送也不會變好），不清理、不走冪等標記")
    void onArticleDeleted_nullArticleUuid_skips() {
        consumer.onArticleDeleted(event(UUID.randomUUID(), null));

        verifyNoInteractions(cleanupService);
        verify(consumerExecutor, org.mockito.Mockito.never()).executeIdempotent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("監聽 file.article-deleted，並發度固定為 1（連線池預算：見 application.yaml hikari 註解）")
    void listener_bindsToQueueWithConcurrencyOne() throws Exception {
        Method method = FileArticleDeletedConsumer.class.getMethod("onArticleDeleted", ArticleDeletedEvent.class);
        RabbitListener listener = method.getAnnotation(RabbitListener.class);

        assertThat(listener.queues()).containsExactly("file.article-deleted");
        assertThat(listener.concurrency()).isEqualTo("1");
    }
}
