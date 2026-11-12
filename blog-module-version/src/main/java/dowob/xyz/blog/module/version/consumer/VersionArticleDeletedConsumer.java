package dowob.xyz.blog.module.version.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.version.config.VersionRabbitMqConfig;
import dowob.xyz.blog.module.version.mapper.VersionArticleCleanupMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Version 模組訂閱 {@link ArticleDeletedEvent}：文章被刪除後，刪除該文章的所有版本快照。
 *
 * <p>V28 起 {@code article_versions} 沒有指向 {@code articles} 的 FK，原本的
 * {@code ON DELETE CASCADE} 改由本 consumer（即時）與 {@code ArticleVersionOrphanReconciliationJob}
 * （每日校正，事件遺失時的第二道保險）負責。{@code user_preferences} 以使用者識別、使用者只軟刪除，
 * 不受文章刪除影響，故不在此清除。</p>
 *
 * <p>只讀事件的 {@code articleUuid}，不讀任何 Long 欄位。{@code DELETE ... WHERE article_uuid = ?}
 * 是單一 SQL 敘述（本身即原子）且天然冪等，重送只會刪除 0 筆，因此不使用 IdempotencyService、也不需要交易模板。</p>
 *
 * <p>ACK 策略：AUTO（正常返回即 ack；拋例外由容器重試，耗盡進 DLQ）；失敗日誌與計數由 {@link ConsumerExecutor} 統一處理：</p>
 * <ul>
 *   <li>成功（含刪除 0 筆）→ ack</li>
 *   <li>舊 payload 缺 {@code articleUuid} → 無法定位要刪的資料，記 warn 後 ack（重送也不會變好）</li>
 *   <li>例外 → 往外拋，容器重試（最多 3 次），耗盡進 DLQ；每日孤兒校正排程是事件遺失時的第二道保險</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VersionArticleDeletedConsumer {

    /** ConsumerExecutor 指標與日誌共用的 consumer 識別名 */
    public static final String CONSUMER_NAME = "version.article-deleted";

    /** 文章刪除連動用的 mapper */
    private final VersionArticleCleanupMapper cleanupMapper;

    /** MQ consumer 共用執行包裝（失敗日誌、計數、冪等） */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理文章刪除事件。
     *
     * @param event       文章刪除事件
     */
    @RabbitListener(queues = VersionRabbitMqConfig.QUEUE_VERSION_ARTICLE_DELETED)
    public void onArticleDeleted(ArticleDeletedEvent event) {
        consumerExecutor.execute(CONSUMER_NAME,
                () -> "articleUuid=" + event.articleUuid() + ", eventId=" + event.eventId(),
                () -> {
                    if (event.articleUuid() == null) {
                        log.warn("文章刪除事件缺少 articleUuid，略過版本快照連動刪除 eventId={}", event.eventId());
                        return;
                    }
                    int deleted = cleanupMapper.deleteByArticleUuid(event.articleUuid());
                    log.info("文章刪除連動：已刪除版本快照 articleUuid={} eventId={} versions={}",
                            event.articleUuid(), event.eventId(), deleted);
                });
    }
}
