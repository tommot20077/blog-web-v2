package dowob.xyz.blog.module.article.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.event.SeriesDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.module.article.mapper.ArticleSeriesCleanupMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Article 模組訂閱 {@link SeriesDeletedEvent}：系列被刪除後，清空成員文章的系列歸屬。
 *
 * <p>V32 起 {@code articles.series_uuid} 沒有指向 {@code series} 的 FK，原本的
 * {@code ON DELETE SET NULL} 改由本 consumer（即時）與 {@code ArticleSeriesOrphanReconciliationJob}
 * （每日校正，事件遺失時的第二道保險）負責。刪除語意不變：<b>文章留存，只清空 {@code series_uuid} 與
 * {@code series_position}</b>；且與改造前相同，不遞增 {@code version}、不更新 {@code updated_at}。</p>
 *
 * <p>只讀事件的 {@code seriesUuid}。{@code UPDATE ... WHERE series_uuid = ?} 是單一 SQL 敘述（本身即原子）
 * 且天然冪等，重送只會更新 0 筆，因此不使用 IdempotencyService、也不需要交易模板。</p>
 *
 * <p>ACK 策略：手動 ACK，由 {@link ConsumerExecutor} 統一處理：</p>
 * <ul>
 *   <li>成功（含更新 0 筆）→ ack</li>
 *   <li>缺 {@code seriesUuid} → 無法定位要清空的文章，記 warn 後 ack（重送也不會變好）</li>
 *   <li>例外 → nack（requeue=false）進 DLQ；每日孤兒校正排程是事件遺失時的第二道保險</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleSeriesDeletedConsumer {

    /** ConsumerExecutor 指標與日誌共用的 consumer 識別名 */
    public static final String CONSUMER_NAME = "article.series-deleted";

    /** 系列刪除連動用的 mapper */
    private final ArticleSeriesCleanupMapper cleanupMapper;

    /** MQ consumer 共用 ack/nack 包裝與計數 */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理系列刪除事件。
     *
     * @param event       系列刪除事件
     * @param channel     AMQP channel
     * @param deliveryTag 訊息的 delivery tag
     */
    @RabbitListener(queues = ArticleRabbitMqConfig.QUEUE_SERIES_DELETED)
    public void onSeriesDeleted(SeriesDeletedEvent event,
                                Channel channel,
                                @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        consumerExecutor.execute(CONSUMER_NAME,
                () -> "seriesUuid=" + event.seriesUuid() + ", eventId=" + event.eventId(),
                channel, deliveryTag, () -> {
                    if (event.seriesUuid() == null) {
                        log.warn("系列刪除事件缺少 seriesUuid，略過文章系列歸屬清空 eventId={}", event.eventId());
                        return;
                    }
                    int cleared = cleanupMapper.clearSeriesByUuid(event.seriesUuid());
                    log.info("系列刪除連動：已清空成員文章的系列歸屬 seriesUuid={} eventId={} articles={}",
                            event.seriesUuid(), event.eventId(), cleared);
                });
    }
}
