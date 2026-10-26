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
     * <p>article 模組訂閱此事件、把成員文章的 {@code series_uuid} 清空（M6-b 起沒有資料庫 FK 連動）。
     * 發送失敗時刪除已 commit，不往外拋；但文章的系列歸屬會暫時殘留，故記錄 <b>error</b>
     * （帶 seriesUuid 與 eventId，供追查）。<b>失敗由 article 端的每日孤兒校正排程兜底</b>
     * （{@code ArticleSeriesOrphanReconciliationJob} 找出指向已不存在系列的文章並清空歸屬）。</p>
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
            log.error("SeriesDeletedEvent MQ 發送失敗（best-effort），文章的系列歸屬將由 article 端每日校正清空："
                            + "seriesUuid={}, eventId={}, error={}",
                    seriesUuid, eventId, e.getMessage(), e);
        }
    }
}
