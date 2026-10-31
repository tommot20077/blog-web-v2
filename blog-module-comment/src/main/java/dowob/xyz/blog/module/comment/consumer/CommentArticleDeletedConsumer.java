package dowob.xyz.blog.module.comment.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.comment.config.CommentRabbitMqConfig;
import dowob.xyz.blog.module.comment.mapper.CommentArticleCleanupMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Comment 模組訂閱 {@link ArticleDeletedEvent}：文章被刪除後，刪除該文章的所有留言。
 *
 * <p>留言讚（{@code comment_likes}）由模組內 FK {@code ON DELETE CASCADE} 隨留言一併清除。
 * 只讀事件的 {@code articleUuid}，不讀任何 Long 欄位。</p>
 *
 * <p>冪等性：{@code DELETE ... WHERE article_uuid = ?} 本身天然冪等，重送只會刪除 0 筆，
 * 因此不使用 IdempotencyService。</p>
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
public class CommentArticleDeletedConsumer {

    /** ConsumerExecutor 指標與日誌共用的 consumer 識別名 */
    public static final String CONSUMER_NAME = "comment.article-deleted";

    private final CommentArticleCleanupMapper cleanupMapper;

    /** MQ consumer 共用執行包裝（失敗日誌、計數、冪等） */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理文章刪除事件。
     *
     * @param event       文章刪除事件
     */
    @RabbitListener(queues = CommentRabbitMqConfig.QUEUE_COMMENT_ARTICLE_DELETED)
    public void onArticleDeleted(ArticleDeletedEvent event) {
        consumerExecutor.execute(CONSUMER_NAME,
                () -> "articleUuid=" + event.articleUuid() + ", eventId=" + event.eventId(),
                () -> {
                    if (event.articleUuid() == null) {
                        log.warn("文章刪除事件缺少 articleUuid，略過留言連動刪除 eventId={}", event.eventId());
                        return;
                    }
                    int deleted = cleanupMapper.deleteByArticleUuid(event.articleUuid());
                    log.info("文章刪除連動：已刪除留言 articleUuid={} eventId={} deleted={}",
                            event.articleUuid(), event.eventId(), deleted);
                });
    }
}
