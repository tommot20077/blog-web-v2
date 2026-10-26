package dowob.xyz.blog.module.series.service;

import dowob.xyz.blog.infrastructure.event.SeriesDeletedEvent;
import dowob.xyz.blog.infrastructure.event.SeriesEventContract;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Series 事件發布元件。
 *
 * <p>統一封裝 series 模組的 RabbitMQ 訊息發送，採 best-effort 策略：失敗時僅記錄、不往外拋，
 * 不影響已 commit 的主流程（比照 {@code ArticleEventPublisher}）。<b>呼叫端必須在 DB 交易 commit 之後才呼叫</b>
 * （{@code code-standards.md}「Transaction + MQ 時序」）：本類別本身沒有 {@code @Transactional}，
 * 但被交易方法呼叫時一樣在交易內，所以只能由已離開交易的程式碼（例如 {@code TransactionTemplate} 之後）呼叫。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeriesEventPublisher {

    /** MQ 樣板（Jackson JSON 轉換由 {@code RabbitMqConfig} 設定） */
    private final RabbitTemplate rabbitTemplate;

    /**
     * 發送系列刪除事件至 {@code series.events}（routing key {@code series.deleted}）。
     *
     * <p>目前沒有 consumer（article 於 M6 訂閱）；exchange 在沒有任何 queue 綁定時直接丟棄訊息，
     * 發送端不受影響。文章脫離系列在 M6 之前仍由 {@code articles.series_id} 的 FK
     * {@code ON DELETE SET NULL} 負責，所以發送失敗目前沒有功能性後果，僅記錄 warn
     * （帶 eventId 與 seriesUuid，供日後 M6 啟用後人工補送）。</p>
     *
     * @param seriesUuid 已被刪除（且已 commit）的系列公開 UUID
     */
    public void publishDeleted(UUID seriesUuid) {
        UUID eventId = UUID.randomUUID();
        try {
            rabbitTemplate.convertAndSend(
                    SeriesEventContract.EXCHANGE,
                    SeriesEventContract.ROUTING_KEY_DELETED,
                    new SeriesDeletedEvent(eventId, seriesUuid, Instant.now()));
        } catch (Exception e) {
            log.warn("SeriesDeletedEvent MQ 發送失敗（best-effort）：seriesUuid={}, eventId={}, error={}",
                    seriesUuid, eventId, e.getMessage(), e);
        }
    }
}
