package dowob.xyz.blog.module.series.consumer;

import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.module.article.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.series.config.SeriesRabbitMqConfig;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Series 模組訂閱 ArticleDeletedEvent — 連動 series.article_count -1。
 *
 * <p>冪等性：用 IdempotencyService 對 (event_id, consumer_name) 做 dedup，
 * 重送會被 skip 不會多次扣 count。</p>
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
    public void onArticleDeleted(ArticleDeletedEvent event) {
        try {
            /* seriesId null 表示文章不在 series（或舊 payload backward compat）*/
            if (event.seriesId() == null) {
                return;
            }
            /* 冪等 check：第一次 INSERT 成功才繼續處理；重送會 skip */
            if (!idempotencyService.markProcessed(event.eventId(), CONSUMER_NAME)) {
                return;
            }
            seriesMapper.decrementArticleCount(event.seriesId());
        } catch (Exception e) {
            log.error("處理 ArticleDeletedEvent 失敗 articleId={} seriesId={}",
                event.articleId(), event.seriesId(), e);
            throw e;  // re-throw 讓 RabbitMQ requeue / 進 DLQ
        }
    }
}
