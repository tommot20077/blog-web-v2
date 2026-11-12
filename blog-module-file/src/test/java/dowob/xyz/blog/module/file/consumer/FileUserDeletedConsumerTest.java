package dowob.xyz.blog.module.file.consumer;

import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.file.service.FileUserCleanupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link FileUserDeletedConsumer} 單元測試（DATA-02）。{@link ConsumerExecutor} 使用真實實例。
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileUserDeletedConsumer")
class FileUserDeletedConsumerTest {

    @Mock private FileUserCleanupService cleanupService;

    private FileUserDeletedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new FileUserDeletedConsumer(cleanupService, new ConsumerExecutor());
    }

    private static UserDeletedEvent event(UUID userUuid) {
        return new UserDeletedEvent(UUID.randomUUID(), userUuid, Instant.now());
    }

    @Test
    @DisplayName("收到事件：委派清理服務刪除該使用者未被保留文章引用的檔案")
    void onUserDeleted_delegatesToCleanupService() throws Exception {
        UUID userUuid = UUID.randomUUID();

        consumer.onUserDeleted(event(userUuid));

        verify(cleanupService).deleteFilesNotReferencedByRetainedArticles(userUuid);
    }

    @Test
    @DisplayName("清理失敗：例外往外拋（交由容器重試，耗盡進 DLQ）")
    void onUserDeleted_serviceThrows_rethrows() throws Exception {
        UUID userUuid = UUID.randomUUID();
        doThrow(new IllegalStateException("MinIO down")).when(cleanupService)
                .deleteFilesNotReferencedByRetainedArticles(userUuid);

        assertThatThrownBy(() -> consumer.onUserDeleted(event(userUuid))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("缺 userUuid 的 payload：不處理、仍正常返回（避免無限重送）")
    void onUserDeleted_nullUserUuid_doesNothing() throws Exception {
        consumer.onUserDeleted(event(null));

        verifyNoInteractions(cleanupService);
    }
}
