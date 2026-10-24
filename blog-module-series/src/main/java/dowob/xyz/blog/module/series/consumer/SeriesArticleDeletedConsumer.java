package dowob.xyz.blog.module.series.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.article.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.series.config.SeriesRabbitMqConfig;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Series 模組訂閱 ArticleDeletedEvent — 連動 series.article_count -1。
 *
 * <p>冪等性：用 IdempotencyService 對 (event_id, consumer_name) 做 dedup，
 * 重送會被 skip 不會多次扣 count。</p>
 *
 * <p>ACK 策略：手動 ACK，由 {@link ConsumerExecutor} 統一處理，對齊 ViewCountConsumer pattern：
 * <ul>
 *   <li>no-op（seriesId null）→ 提早 return，仍 ack</li>
 *   <li>已處理過（skip）→ 提早 return，仍 ack</li>
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

    private final IdempotencyService idempotencyService;
    private final SeriesMapper seriesMapper;

    /** MQ consumer 共用 ack/nack 包裝與計數 */
    private final ConsumerExecutor consumerExecutor;

    @RabbitListener(queues = SeriesRabbitMqConfig.QUEUE_SERIES_ARTICLE_DELETED)
    public void onArticleDeleted(ArticleDeletedEvent event,
                                 Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        consumerExecutor.execute(CONSUMER_NAME,
                () -> "articleId=" + event.articleId() + ", seriesId=" + event.seriesId()
                        + ", eventId=" + event.eventId(),
                channel, deliveryTag, () -> {
                    /* seriesId null 表示文章不在 series（或舊 payload backward compat）；no-op 也要 ACK */
                    if (event.seriesId() == null) {
                        return;
                    }
                    /* 冪等 check：第一次 INSERT 成功才繼續處理；重送會 skip（已處理過也要 ACK） */
                    if (!idempotencyService.markProcessed(event.eventId(), CONSUMER_NAME)) {
                        return;
                    }
                    seriesMapper.decrementArticleCount(event.seriesId());
                });
    }
}
