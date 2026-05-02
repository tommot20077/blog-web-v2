package dowob.xyz.blog.module.version.consumer;

import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.version.config.VersionRabbitMqConfig;
import dowob.xyz.blog.module.version.service.AutoSnapshotPolicy;
import dowob.xyz.blog.module.version.service.VersioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Article content changed 事件消費者 — 觸發 version 模組寫快照。
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

    @RabbitListener(queues = VersionRabbitMqConfig.QUEUE_VERSION_SNAPSHOT)
    public void onContentChanged(ArticleContentChangedEvent event) {
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
        } catch (Exception e) {
            log.error("處理 ArticleContentChangedEvent 失敗 articleId={} action={}",
                event.articleId(), event.action(), e);
            throw e;
        }
    }
}
