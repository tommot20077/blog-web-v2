package dowob.xyz.blog.module.series.service;

import dowob.xyz.blog.infrastructure.event.SeriesDeletedEvent;
import dowob.xyz.blog.infrastructure.event.SeriesEventContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link SeriesEventPublisher} 單元測試：發送目標、事件內容，以及 best-effort（失敗不往外拋）。
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SeriesEventPublisher")
class SeriesEventPublisherTest {

    @Mock private RabbitTemplate rabbitTemplate;
    @InjectMocks private SeriesEventPublisher publisher;

    @Test
    @DisplayName("publishDeleted：發送到 series.events／series.deleted，事件帶 eventId、seriesUuid、occurredAt")
    void publishDeleted_sendsEventToContractExchangeAndRoutingKey() {
        UUID seriesUuid = UUID.randomUUID();
        Instant before = Instant.now();

        publisher.publishDeleted(seriesUuid);

        ArgumentCaptor<SeriesDeletedEvent> captor = ArgumentCaptor.forClass(SeriesDeletedEvent.class);
        verify(rabbitTemplate).convertAndSend(
                eq(SeriesEventContract.EXCHANGE), eq(SeriesEventContract.ROUTING_KEY_DELETED), captor.capture());
        SeriesDeletedEvent event = captor.getValue();
        assertThat(event.eventId()).isNotNull();
        assertThat(event.seriesUuid()).isEqualTo(seriesUuid);
        assertThat(event.occurredAt()).isBetween(before, Instant.now());
    }

    @Test
    @DisplayName("publishDeleted：每次呼叫產生不同的 eventId（consumer 冪等的 dedup key）")
    void publishDeleted_generatesFreshEventIdPerCall() {
        UUID seriesUuid = UUID.randomUUID();

        publisher.publishDeleted(seriesUuid);
        publisher.publishDeleted(seriesUuid);

        ArgumentCaptor<SeriesDeletedEvent> captor = ArgumentCaptor.forClass(SeriesDeletedEvent.class);
        verify(rabbitTemplate, org.mockito.Mockito.times(2)).convertAndSend(
                eq(SeriesEventContract.EXCHANGE), eq(SeriesEventContract.ROUTING_KEY_DELETED), captor.capture());
        assertThat(captor.getAllValues()).extracting(SeriesDeletedEvent::eventId).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("publishDeleted：MQ 發送失敗 → best-effort，不往外拋（刪除已 commit，不得因此回報失敗）")
    void publishDeleted_whenBrokerFails_doesNotThrow() {
        doThrow(new AmqpConnectException(new RuntimeException("broker down")))
                .when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class));

        assertThatCode(() -> publisher.publishDeleted(UUID.randomUUID())).doesNotThrowAnyException();
    }
}
