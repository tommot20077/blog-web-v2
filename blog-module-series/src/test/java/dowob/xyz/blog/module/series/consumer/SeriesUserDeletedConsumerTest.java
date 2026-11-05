package dowob.xyz.blog.module.series.consumer;

import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.series.service.SeriesEventPublisher;
import dowob.xyz.blog.module.series.service.SeriesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SeriesUserDeletedConsumer} 單元測試：冪等、刪帳連動只刪「已無保留文章」的系列、commit 後才發 SeriesDeletedEvent。
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SeriesUserDeletedConsumer")
class SeriesUserDeletedConsumerTest {

    @Mock private IdempotencyService idempotencyService;
    @Mock private SeriesService seriesService;
    @Mock private SeriesEventPublisher eventPublisher;

    /** 交易管理員替身：用來觀察 commit／rollback */
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    private SeriesUserDeletedConsumer consumer;

    private static final UUID USER_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @BeforeEach
    void buildSubjectUnderTest() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ConsumerExecutor executor = new ConsumerExecutor(new TransactionTemplate(txManager), idempotencyService);
        consumer = new SeriesUserDeletedConsumer(seriesService, eventPublisher, executor);
    }

    private UserDeletedEvent event(UUID userUuid) {
        return new UserDeletedEvent(UUID.randomUUID(), userUuid, Instant.now());
    }

    @Test
    @DisplayName("第一次處理 → 刪除無保留文章的系列，commit 後逐一發 SeriesDeletedEvent")
    void firstTime_deletesSeriesThenPublishesAfterCommit() {
        UserDeletedEvent ev = event(USER_UUID);
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        when(idempotencyService.tryMark(eq(ev.eventId()), eq(SeriesUserDeletedConsumer.CONSUMER_NAME))).thenReturn(true);
        when(seriesService.deleteSeriesWithoutRetainedArticles(USER_UUID)).thenReturn(List.of(s1, s2));

        consumer.onUserDeleted(ev);

        InOrder order = inOrder(seriesService, txManager, eventPublisher);
        order.verify(seriesService).deleteSeriesWithoutRetainedArticles(USER_UUID);
        order.verify(txManager).commit(any());
        order.verify(eventPublisher).publishDeleted(s1);
        order.verify(eventPublisher).publishDeleted(s2);
    }

    @Test
    @DisplayName("沒有系列需要刪除 → 不發任何事件")
    void nothingDeleted_publishesNothing() {
        UserDeletedEvent ev = event(USER_UUID);
        when(idempotencyService.tryMark(any(), any())).thenReturn(true);
        when(seriesService.deleteSeriesWithoutRetainedArticles(USER_UUID)).thenReturn(List.of());

        consumer.onUserDeleted(ev);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("同一事件重送 → 冪等去重：不刪、不發事件，仍正常返回")
    void replayed_skipsEverything() {
        UserDeletedEvent ev = event(USER_UUID);
        when(idempotencyService.tryMark(eq(ev.eventId()), eq(SeriesUserDeletedConsumer.CONSUMER_NAME))).thenReturn(false);

        consumer.onUserDeleted(ev);

        verifyNoInteractions(seriesService);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("刪除失敗 → 例外往外拋（容器重試）、標記交易 rollback、不發事件")
    void deleteFails_rollsBackAndPublishesNothing() {
        UserDeletedEvent ev = event(USER_UUID);
        when(idempotencyService.tryMark(any(), any())).thenReturn(true);
        doThrow(new RuntimeException("DB down")).when(seriesService).deleteSeriesWithoutRetainedArticles(USER_UUID);

        assertThatThrownBy(() -> consumer.onUserDeleted(ev)).isInstanceOf(RuntimeException.class);

        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("缺 userUuid 的 payload → 記 warn 後略過，不刪、不寫冪等標記")
    void missingUserUuid_isSkipped() {
        consumer.onUserDeleted(event(null));

        verifyNoInteractions(seriesService);
        verifyNoInteractions(eventPublisher);
        verify(idempotencyService, never()).tryMark(any(), any());
    }

    @Test
    @DisplayName("舊訊息 eventId 為 null → 不去重、照舊處理")
    void nullEventId_processesWithoutIdempotency() {
        UserDeletedEvent ev = new UserDeletedEvent(null, USER_UUID, Instant.now());
        UUID s1 = UUID.randomUUID();
        when(seriesService.deleteSeriesWithoutRetainedArticles(USER_UUID)).thenReturn(List.of(s1));

        consumer.onUserDeleted(ev);

        verify(idempotencyService, never()).tryMark(any(), any());
        verify(eventPublisher).publishDeleted(s1);
    }
}
