package dowob.xyz.blog.module.series.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.module.article.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.series.config.SeriesRabbitMqConfig;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Series 模組訂閱 ArticleDeletedEvent — 連動 series.article_count -1。
 *
 * <p>冪等性：用 IdempotencyService 對 (event_id, consumer_name) 做 dedup，
 * 重送會被 skip 不會多次扣 count。</p>
 *
 * <p>ACK 策略：手動 ACK，對齊 ViewCountConsumer pattern：
 * <ul>
 *   <li>no-op（seriesId null）→ basicAck</li>
 *   <li>已處理過（skip）→ basicAck</li>
 *   <li>成功處理 → basicAck</li>
 *   <li>例外 → basicNack(false, false) 進 DLQ，不 requeue</li>
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

    public static final String CONSUMER_NAME = "series.article-deleted";

    private final IdempotencyService idempotencyService;
    private final SeriesMapper seriesMapper;

    @RabbitListener(queues = SeriesRabbitMqConfig.QUEUE_SERIES_ARTICLE_DELETED)
    public void onArticleDeleted(ArticleDeletedEvent event,
                                 Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            /* seriesId null 表示文章不在 series（或舊 payload backward compat）*/
            if (event.seriesId() == null) {
                channel.basicAck(deliveryTag, false);  // no-op 也要 ACK
                return;
            }
            /* 冪等 check：第一次 INSERT 成功才繼續處理；重送會 skip */
            if (!idempotencyService.markProcessed(event.eventId(), CONSUMER_NAME)) {
                channel.basicAck(deliveryTag, false);  // 已處理過也要 ACK
                return;
            }
            seriesMapper.decrementArticleCount(event.seriesId());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("處理 ArticleDeletedEvent 失敗 articleId={} seriesId={}",
                event.articleId(), event.seriesId(), e);
            try {
                channel.basicNack(deliveryTag, false, false);  // 不 requeue，直接進 DLQ
            } catch (IOException nackEx) {
                log.error("NACK 亦失敗", nackEx);
            }
        }
    }
}
