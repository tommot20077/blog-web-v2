package dowob.xyz.blog.module.version.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.version.config.VersionRabbitMqConfig;
import dowob.xyz.blog.module.version.service.AutoSnapshotPolicy;
import dowob.xyz.blog.module.version.service.VersioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Article content changed 事件消費者 — 觸發 version 模組寫快照。
 *
 * <p>共用 {@code rabbitListenerContainerFactory} 為 MANUAL ACK，必須手動 basicAck/basicNack；
 * 失敗一律 basicNack(requeue=false) 送 DLQ，避免重試風暴。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArticleVersionConsumer {

    private final VersioningService versioningService;
    private final AutoSnapshotPolicy autoSnapshotPolicy;

    /**
     * 消費 article.content.changed 事件，依 action 觸發對應的快照流程。
     *
     * @param event       文章內容變更事件
     * @param channel     RabbitMQ Channel，用於手動 ACK
     * @param deliveryTag 訊息遞送標籤
     */
    @RabbitListener(queues = VersionRabbitMqConfig.QUEUE_VERSION_SNAPSHOT)
    public void onContentChanged(
            ArticleContentChangedEvent event,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            switch (event.action()) {
                case SAVED -> {
                    if (autoSnapshotPolicy.shouldSnapshot(event.articleId())) {
                        versioningService.recordAutoSnapshot(event.articleId());
                    }
                }
                case PUBLISHED -> versioningService.freezePublished(event.articleId());
                case RESTORED -> {
                    /* no-op：restore 已在 VersioningService 內部寫過 stash */
                }
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("處理 ArticleContentChangedEvent 失敗 articleId={} action={}，訊息送往 DLQ",
                event.articleId(), event.action(), e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException nackException) {
                log.error("NACK 亦失敗 articleId={}", event.articleId(), nackException);
            }
        }
    }
}
