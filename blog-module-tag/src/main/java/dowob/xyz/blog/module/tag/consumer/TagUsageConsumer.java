package dowob.xyz.blog.module.tag.consumer;

import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.tag.config.TagRabbitMqConfig;
import dowob.xyz.blog.infrastructure.event.ArticleTagEvent;
import dowob.xyz.blog.module.tag.service.TagUsageRecalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 標籤使用計數消費者
 *
 * <p>
 * 監聽文章標籤事件（{@link ArticleTagEvent}），對事件中每個標籤<b>重算</b>使用計數
 * （已發布文章數，見 {@link TagUsageRecalculator}），並以 ZADD 設定 Redis 熱門標籤分數。
 * 事件只用來指出「哪些標籤受影響」，不再代表「+1」，因此重送、重複編輯都不會灌水（DATA-01）。
 * 若標籤 ID 不存在，則靜默跳過，不拋出例外。
 * </p>
 *
 * @author Yuan
 * @version 2.0
 */
@Slf4j
@Component
public class TagUsageConsumer {

    /** 冪等消費 consumer 識別名（用於 (event_id, consumer_name) dedup） */
    public static final String CONSUMER_NAME = "tag.usage-count";

    /**
     * 標籤使用計數重算元件
     */
    private final TagUsageRecalculator recalculator;

    /**
     * MQ consumer 共用執行包裝（失敗日誌、計數、冪等）
     */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 建立消費者。
     *
     * @param recalculator     標籤使用計數重算元件
     * @param consumerExecutor MQ consumer 共用執行包裝
     */
    public TagUsageConsumer(TagUsageRecalculator recalculator, ConsumerExecutor consumerExecutor) {
        this.recalculator = recalculator;
        this.consumerExecutor = consumerExecutor;
    }

    /**
     * 處理文章標籤事件，重算事件中各標籤的使用計數
     *
     * <p>
     * 冪等標記與重算（DB 寫入）在同一個交易內（{@link ConsumerExecutor#executeIdempotent}）；
     * Redis 熱門分數在 DB 寫入之後才以 ZADD 設定。重算本身對同一狀態天然冪等，標記僅用來省下重複的查詢。
     * 採用 AUTO ACK 模式：失敗日誌與計數統一交由 {@link ConsumerExecutor} 處理
     * （處理成功則正常返回、容器 ack；失敗則往外拋，由容器重試，耗盡後送 DLQ）。
     * </p>
     *
     * @param event       文章標籤事件
     */
    @RabbitListener(queues = TagRabbitMqConfig.QUEUE_TAG_ARTICLE_TAGGED)
    public void handleArticleTagged(ArticleTagEvent event) {
        consumerExecutor.executeIdempotent(CONSUMER_NAME, () -> "eventId=" + event.eventId(),
                event.eventId(), () -> recalculator.recalculate(event.tagIds()));
    }
}
