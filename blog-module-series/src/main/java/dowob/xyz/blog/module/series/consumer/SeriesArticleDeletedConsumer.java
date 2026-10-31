package dowob.xyz.blog.module.series.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.series.config.SeriesRabbitMqConfig;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Series 模組訂閱 ArticleDeletedEvent — 連動 series.article_count -1。
 *
 * <p>冪等性：由 {@link ConsumerExecutor#executeIdempotent} 對 (event_id, consumer_name) 做 dedup，
 * 標記與扣計數在<b>同一個資料庫交易</b>內——重送會被 skip 不會多次扣 count，
 * 扣計數失敗則標記一起 rollback，不會因此漏扣。</p>
 *
 * <p>識別：只讀事件的 {@code seriesUuid}，以 series 公開 UUID 扣計數；{@code articleUuid} 僅用於 log。
 * 事件舊有的 Long 欄位（articleId、seriesId）已於 M4-b 移除，series 是它們最後一個讀取者。</p>
 *
 * <p>ACK 策略：手動 ACK，由 {@link ConsumerExecutor} 統一處理，對齊 ViewCountConsumer pattern：
 * <ul>
 *   <li>no-op（seriesUuid null）→ 提早 return，仍 ack</li>
 *   <li>已處理過（skip）→ 由 ConsumerExecutor 跳過業務，仍 ack</li>
 *   <li>成功處理 → ack</li>
 *   <li>例外 → nack（requeue=false）進 DLQ，不 requeue</li>
 * </ul>
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeriesArticleDeletedConsumer {

    /** 冪等消費與 ConsumerExecutor 指標共用的 consumer 識別名 */
    public static final String CONSUMER_NAME = "series.article-deleted";

    private final SeriesMapper seriesMapper;

    /** MQ consumer 共用 ack/nack 包裝與計數 */
    private final ConsumerExecutor consumerExecutor;

    @RabbitListener(queues = SeriesRabbitMqConfig.QUEUE_SERIES_ARTICLE_DELETED)
    public void onArticleDeleted(ArticleDeletedEvent event,
                                 Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        Supplier<String> logContext = () -> "articleUuid=" + event.articleUuid()
                + ", seriesUuid=" + event.seriesUuid() + ", eventId=" + event.eventId();
        /* seriesUuid null 表示文章不在 series（或舊 payload backward compat）：no-op 也要 ACK，
         * 且不進冪等流程——沒有業務可去重，不該為每一篇不在 series 的文章刪除事件多寫一列 processed_events */
        if (event.seriesUuid() == null) {
            consumerExecutor.execute(CONSUMER_NAME, logContext, channel, deliveryTag, () -> { });
            return;
        }
        /* 標記與扣計數同一個資料庫交易：扣計數失敗 → 標記一起 rollback，DLQ 重投不會被誤判為已處理 */
        consumerExecutor.executeIdempotent(CONSUMER_NAME, logContext, channel, deliveryTag, event.eventId(),
                () -> seriesMapper.decrementArticleCountByUuid(event.seriesUuid()));
    }
}
