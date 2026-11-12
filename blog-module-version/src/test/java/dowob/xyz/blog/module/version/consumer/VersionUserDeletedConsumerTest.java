package dowob.xyz.blog.module.version.consumer;

import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.version.mapper.UserPreferenceMapper;
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
 * {@link VersionUserDeletedConsumer} 單元測試（DATA-02）。
 *
 * <p>{@link ConsumerExecutor} 使用真實實例。{@code DELETE ... WHERE user_uuid = ?} 是單一 SQL 敘述，
 * 本身原子且天然冪等，因此不使用 IdempotencyService、也不需要交易模板。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VersionUserDeletedConsumer")
class VersionUserDeletedConsumerTest {

    @Mock private UserPreferenceMapper preferenceMapper;

    private VersionUserDeletedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new VersionUserDeletedConsumer(preferenceMapper, new ConsumerExecutor());
    }

    private static UserDeletedEvent event(UUID userUuid) {
        return new UserDeletedEvent(UUID.randomUUID(), userUuid, Instant.now());
    }

    @Test
    @DisplayName("收到事件：以 userUuid 刪除該使用者的偏好設定，然後正常返回")
    void onUserDeleted_deletesPreferencesByUserUuid() throws Exception {
        UUID userUuid = UUID.randomUUID();

        consumer.onUserDeleted(event(userUuid));

        verify(preferenceMapper).deleteByUserUuid(userUuid);
    }

    @Test
    @DisplayName("刪除失敗：例外往外拋（交由容器重試，耗盡進 DLQ）")
    void onUserDeleted_mapperThrows_rethrows() throws Exception {
        UUID userUuid = UUID.randomUUID();
        doThrow(new IllegalStateException("DB down")).when(preferenceMapper).deleteByUserUuid(userUuid);

        assertThatThrownBy(() -> consumer.onUserDeleted(event(userUuid))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("缺 userUuid 的 payload：不刪除任何資料、仍正常返回（避免無限重送）")
    void onUserDeleted_nullUserUuid_doesNothing() throws Exception {
        consumer.onUserDeleted(event(null));

        verifyNoInteractions(preferenceMapper);
    }
}
