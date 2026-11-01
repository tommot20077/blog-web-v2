package dowob.xyz.blog.module.reading.consumer;

import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.reading.mapper.ReadingUserCleanupMapper;
import dowob.xyz.blog.module.reading.service.ReadingUserCacheCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ReadingUserDeletedConsumer} 單元測試（DATA-02）。
 *
 * <p>{@link ConsumerExecutor} 使用真實實例；{@link TransactionTemplate} 使用真實實例搭配 Mock 的交易管理員，
 * 以驗證「三張表同一交易、失敗回滾」。按讚<b>不</b>刪除（決策見 consumer 類別文件），由
 * {@code verifyNoMoreInteractions} 釘住。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReadingUserDeletedConsumer")
class ReadingUserDeletedConsumerTest {

    @Mock private ReadingUserCleanupMapper cleanupMapper;
    @Mock private ReadingUserCacheCleaner cacheCleaner;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;

    private ReadingUserDeletedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ReadingUserDeletedConsumer(cleanupMapper, cacheCleaner,
                new TransactionTemplate(transactionManager), new ConsumerExecutor());
    }

    private static UserDeletedEvent event(UUID userUuid) {
        return new UserDeletedEvent(UUID.randomUUID(), userUuid, Instant.now());
    }

    @Test
    @DisplayName("收到事件：先清 Redis 閱讀進度快取，再於同一交易內刪除收藏、劃線、閱讀進度後 commit")
    void onUserDeleted_clearsCacheThenDeletesThreeTablesInOneTransaction() throws Exception {
        UUID userUuid = UUID.randomUUID();
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

        consumer.onUserDeleted(event(userUuid));

        InOrder order = inOrder(cacheCleaner, transactionManager, cleanupMapper);
        order.verify(cacheCleaner).clearProgressCache(userUuid);
        order.verify(transactionManager).getTransaction(any());
        order.verify(cleanupMapper).deleteBookmarksByUserUuid(userUuid);
        order.verify(cleanupMapper).deleteHighlightsByUserUuid(userUuid);
        order.verify(cleanupMapper).deleteProgressByUserUuid(userUuid);
        order.verify(transactionManager).commit(transactionStatus);
    }

    @Test
    @DisplayName("按讚保留：不刪除按讚紀錄（已無個資，且刪除會讓文章 like_count 與實際不符）")
    void onUserDeleted_neverTouchesLikes() throws Exception {
        UUID userUuid = UUID.randomUUID();
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

        consumer.onUserDeleted(event(userUuid));

        verify(cleanupMapper).deleteBookmarksByUserUuid(userUuid);
        verify(cleanupMapper).deleteHighlightsByUserUuid(userUuid);
        verify(cleanupMapper).deleteProgressByUserUuid(userUuid);
        verifyNoMoreInteractions(cleanupMapper);
    }

    @Test
    @DisplayName("最後一張表刪除失敗：交易回滾（不 commit）、例外往外拋（交由容器重試）")
    void onUserDeleted_progressDeleteThrows_rollsBackAndRethrows() throws Exception {
        UUID userUuid = UUID.randomUUID();
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        doThrow(new RuntimeException("DB down")).when(cleanupMapper).deleteProgressByUserUuid(userUuid);

        assertThatThrownBy(() -> consumer.onUserDeleted(event(userUuid))).isInstanceOf(RuntimeException.class);

        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
    }

    @Test
    @DisplayName("Redis 清除失敗：例外往外拋、不開交易（交由容器重試，DB 尚未動）")
    void onUserDeleted_cacheCleanerThrows_rethrowsWithoutTouchingDb() throws Exception {
        UUID userUuid = UUID.randomUUID();
        doThrow(new RuntimeException("redis down")).when(cacheCleaner).clearProgressCache(userUuid);

        assertThatThrownBy(() -> consumer.onUserDeleted(event(userUuid))).isInstanceOf(RuntimeException.class);

        verifyNoInteractions(cleanupMapper, transactionManager);
    }

    @Test
    @DisplayName("缺 userUuid 的 payload：不清快取、不開交易、仍正常返回（避免無限重送）")
    void onUserDeleted_nullUserUuid_doesNothing() throws Exception {
        consumer.onUserDeleted(event(null));

        verifyNoInteractions(cleanupMapper, cacheCleaner, transactionManager);
    }
}
