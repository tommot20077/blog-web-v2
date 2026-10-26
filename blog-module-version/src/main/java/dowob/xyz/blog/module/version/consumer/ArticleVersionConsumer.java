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

/**
 * Article content changed 事件消費者 — 觸發 version 模組寫快照。
 *
 * <p>共用 {@code rabbitListenerContainerFactory} 為 MANUAL ACK，ack／nack 統一交由
 * {@link ConsumerExecutor} 處理；失敗一律 nack（requeue=false）送 DLQ，避免重試風暴。</p>
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
        consumerExecutor.execute(CONSUMER_NAME,
                () -> "articleUuid=" + event.articleUuid() + ", action=" + event.action(),
                channel, deliveryTag, () -> {
                    if (event.articleUuid() == null) {
                        log.warn("文章內容變更事件缺少 articleUuid，略過快照流程 eventId={}", event.eventId());
                        return;
                    }
                    switch (event.action()) {
                        case SAVED -> {
                            if (autoSnapshotPolicy.shouldSnapshot(event.articleUuid())) {
                                versioningService.recordAutoSnapshot(event.articleUuid());
                            }
                        }
                        case PUBLISHED -> versioningService.freezePublished(event.articleUuid());
                        case RESTORED -> {
                            /* no-op：restore 已在 VersioningService 內部寫過 stash */
                        }
                    }
                });
    }
}
