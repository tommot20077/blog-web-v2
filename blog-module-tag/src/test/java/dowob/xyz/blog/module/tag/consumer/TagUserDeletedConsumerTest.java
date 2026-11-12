package dowob.xyz.blog.module.tag.consumer;

import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.tag.repository.UserTagFollowRepository;
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
 * {@link TagUserDeletedConsumer} 單元測試（DATA-02）。
 *
 * <p>{@link ConsumerExecutor} 使用真實實例。{@code DELETE ... WHERE user_id = ?} 是單一 SQL 敘述，
 * 本身原子且天然冪等，因此不使用 IdempotencyService。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TagUserDeletedConsumer")
class TagUserDeletedConsumerTest {

    @Mock private UserTagFollowRepository followRepository;

    private TagUserDeletedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TagUserDeletedConsumer(followRepository, new ConsumerExecutor());
    }

    private static UserDeletedEvent event(UUID userUuid) {
        return new UserDeletedEvent(UUID.randomUUID(), userUuid, Instant.now());
    }

    @Test
    @DisplayName("收到事件：以 userUuid 刪除該使用者的所有標籤追蹤，然後正常返回")
    void onUserDeleted_deletesFollowsByUserUuid() throws Exception {
        UUID userUuid = UUID.randomUUID();

        consumer.onUserDeleted(event(userUuid));

        verify(followRepository).deleteByUserId(userUuid);
    }

    @Test
    @DisplayName("刪除失敗：例外往外拋（交由容器重試，耗盡進 DLQ）")
    void onUserDeleted_repositoryThrows_rethrows() throws Exception {
        UUID userUuid = UUID.randomUUID();
        doThrow(new IllegalStateException("DB down")).when(followRepository).deleteByUserId(userUuid);

        assertThatThrownBy(() -> consumer.onUserDeleted(event(userUuid))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("缺 userUuid 的 payload：不刪除任何資料、仍正常返回（避免無限重送）")
    void onUserDeleted_nullUserUuid_doesNothing() throws Exception {
        consumer.onUserDeleted(event(null));

        verifyNoInteractions(followRepository);
    }
}
