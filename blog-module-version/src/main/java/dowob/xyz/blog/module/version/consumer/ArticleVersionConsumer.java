package dowob.xyz.blog.module.version.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.infrastructure.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.version.config.VersionRabbitMqConfig;
import dowob.xyz.blog.module.version.service.AutoSnapshotPolicy;
import dowob.xyz.blog.module.version.service.VersioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Article content changed 事件消費者 — 觸發 version 模組寫快照。
 *
 * <p>共用 {@code rabbitListenerContainerFactory} 為 MANUAL ACK，ack／nack 統一交由
 * {@link ConsumerExecutor} 處理；失敗一律 nack（requeue=false）送 DLQ，避免重試風暴。</p>
 *
 * <p>冪等：SAVED／PUBLISHED 會新增快照（非冪等），由 {@link ConsumerExecutor#executeIdempotent}
 * 以 {@code eventId} 去重，標記與快照寫入在同一個資料庫交易；快照失敗 → 標記一起 rollback。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArticleVersionConsumer {

    /** consumer 識別名（作為 ConsumerExecutor 指標與日誌的 consumer tag） */
    public static final String CONSUMER_NAME = "version.snapshot";

    private final VersioningService versioningService;
    private final AutoSnapshotPolicy autoSnapshotPolicy;

    /** MQ consumer 共用 ack/nack 包裝與計數 */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 消費 article.content.changed 事件，依 action 觸發對應的快照流程。
     *
     * @param event       文章內容變更事件
     * @param channel     RabbitMQ Channel，交由 {@link ConsumerExecutor} 執行 ack／nack
     * @param deliveryTag 訊息遞送標籤
     */
    @RabbitListener(queues = VersionRabbitMqConfig.QUEUE_VERSION_SNAPSHOT)
    public void onContentChanged(
            ArticleContentChangedEvent event,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        Supplier<String> logContext = () -> "articleUuid=" + event.articleUuid()
                + ", action=" + event.action() + ", eventId=" + event.eventId();
        if (event.articleUuid() == null) {
            consumerExecutor.execute(CONSUMER_NAME, logContext, channel, deliveryTag,
                    () -> log.warn("文章內容變更事件缺少 articleUuid，略過快照流程 eventId={}", event.eventId()));
            return;
        }
        if (event.action() == ArticleContentChangedEvent.Action.RESTORED) {
            /* no-op：restore 已在 VersioningService 內部寫過 stash；沒有業務可去重，不寫 processed_events */
            consumerExecutor.execute(CONSUMER_NAME, logContext, channel, deliveryTag, () -> { });
            return;
        }
        /* 快照是非冪等寫入（重複處理會多一份快照），標記與快照在同一個資料庫交易內；
         * VersioningService 的 @Transactional 為 REQUIRED，會加入這個交易，快照失敗時標記一起 rollback */
        consumerExecutor.executeIdempotent(CONSUMER_NAME, logContext, channel, deliveryTag, event.eventId(), () -> {
            switch (event.action()) {
                case SAVED -> {
                    if (autoSnapshotPolicy.shouldSnapshot(event.articleUuid())) {
                        versioningService.recordAutoSnapshot(event.articleUuid());
                    }
                }
                case PUBLISHED -> versioningService.freezePublished(event.articleUuid());
                default -> { }
            }
        });
    }
}
