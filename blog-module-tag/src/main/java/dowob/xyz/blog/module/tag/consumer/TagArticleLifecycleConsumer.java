package dowob.xyz.blog.module.tag.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleArchivedEvent;
import dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleUpdatedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.tag.config.TagRabbitMqConfig;
import dowob.xyz.blog.module.tag.service.TagUsageRecalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 文章生命週期事件消費者：文章「發布／更新／下架」時，重算該文章所掛標籤的使用計數（已發布文章數，DATA-01）。
 *
 * <p>{@code tags.usage_count} 計的是已發布文章，所以任何讓文章進出 PUBLISHED 的轉換都要重算受影響標籤：</p>
 * <ul>
 *   <li>{@link ArticlePublishedEvent}（發布）：DRAFT／PENDING_REVIEW → PUBLISHED；</li>
 *   <li>{@link ArticleUpdatedEvent}（更新）：PUT 端點把 DRAFT 直接轉成 PUBLISHED 時只會發這個事件
 *       （不發 {@code article.published}，且沒帶 tagNames 時也不發 {@code article.tagged}）；</li>
 *   <li>{@link ArticleArchivedEvent}（下架）：PUBLISHED → ARCHIVED，事件不帶標籤，由
 *       {@code article_tags} 查出。</li>
 * </ul>
 * 刪除文章由 {@link TagArticleDeletedConsumer} 處理，標籤變更由 {@link TagUsageConsumer} 處理。
 * 事件只用來指出「哪篇文章的狀態變了」，標籤集合一律回查自己的 {@code article_tags}、已發布與否一律回問
 * article facade（不信任事件 payload），因此事件重送、亂序都收斂到同一個結果；不使用冪等標記。
 *
 * <p>ACK 策略：AUTO（正常返回即 ack；拋例外由容器重試，耗盡進 DLQ）；每日校正排程
 * （{@code TagUsageReconciliationJob}）是事件遺失時的第二道保險。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
public class TagArticleLifecycleConsumer {

    /** ConsumerExecutor 指標與日誌共用的 consumer 識別名：發布事件 */
    public static final String CONSUMER_NAME_PUBLISHED = "tag.usage-count.article-published";

    /** ConsumerExecutor 指標與日誌共用的 consumer 識別名：更新事件 */
    public static final String CONSUMER_NAME_UPDATED = "tag.usage-count.article-updated";

    /** ConsumerExecutor 指標與日誌共用的 consumer 識別名：下架事件 */
    public static final String CONSUMER_NAME_ARCHIVED = "tag.usage-count.article-archived";

    /** 標籤使用計數重算元件 */
    private final TagUsageRecalculator recalculator;

    /** MQ consumer 共用執行包裝（失敗日誌、計數） */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 建立消費者。
     *
     * @param recalculator     標籤使用計數重算元件
     * @param consumerExecutor MQ consumer 共用執行包裝
     */
    public TagArticleLifecycleConsumer(TagUsageRecalculator recalculator, ConsumerExecutor consumerExecutor) {
        this.recalculator = recalculator;
        this.consumerExecutor = consumerExecutor;
    }

    /**
     * 處理文章發布事件。
     *
     * @param event 文章發布事件
     */
    @RabbitListener(queues = TagRabbitMqConfig.QUEUE_TAG_USAGE_ARTICLE_PUBLISHED)
    public void onArticlePublished(ArticlePublishedEvent event) {
        recalculateTagsOf(CONSUMER_NAME_PUBLISHED, event.articleUuid(), event.eventId());
    }

    /**
     * 處理文章更新事件。
     *
     * @param event 文章更新事件
     */
    @RabbitListener(queues = TagRabbitMqConfig.QUEUE_TAG_USAGE_ARTICLE_UPDATED)
    public void onArticleUpdated(ArticleUpdatedEvent event) {
        recalculateTagsOf(CONSUMER_NAME_UPDATED, event.articleUuid(), event.eventId());
    }

    /**
     * 處理文章下架事件。
     *
     * @param event 文章下架事件
     */
    @RabbitListener(queues = TagRabbitMqConfig.QUEUE_TAG_USAGE_ARTICLE_ARCHIVED)
    public void onArticleArchived(ArticleArchivedEvent event) {
        recalculateTagsOf(CONSUMER_NAME_ARCHIVED, event.articleUuid(), event.eventId());
    }

    /**
     * 重算指定文章所掛標籤；缺 articleUuid 的舊 payload 記 warn 後略過（重送也不會變好）。
     *
     * @param consumerName consumer 識別名
     * @param articleUuid  文章公開 UUID
     * @param eventId      事件 ID（僅日誌用）
     */
    private void recalculateTagsOf(String consumerName, UUID articleUuid, UUID eventId) {
        consumerExecutor.execute(consumerName,
                () -> "articleUuid=" + articleUuid + ", eventId=" + eventId,
                () -> {
                    if (articleUuid == null) {
                        log.warn("文章事件缺少 articleUuid，略過標籤使用計數重算 consumer={} eventId={}", consumerName, eventId);
                        return;
                    }
                    int corrected = recalculator.recalculateForArticle(articleUuid);
                    log.debug("文章事件觸發標籤使用計數重算 consumer={} articleUuid={} corrected={}",
                            consumerName, articleUuid, corrected);
                });
    }
}
