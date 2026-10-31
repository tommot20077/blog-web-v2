package dowob.xyz.blog.module.tag.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.tag.config.TagRabbitMqConfig;
import dowob.xyz.blog.module.tag.repository.ArticleTagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Tag 模組訂閱 {@link ArticleDeletedEvent}：文章被刪除後，刪除該文章的 {@code article_tags} 列。
 *
 * <p>取代原本 {@code article_tags.article_id → articles(uuid) ON DELETE CASCADE}（V33 已移除該跨模組 FK）。
 * 只讀事件的 {@code articleUuid}。</p>
 *
 * <p><b>只刪關聯列，不動 {@code tags}</b>：標籤本身與 {@code tags.usage_count} 維持不變——
 * 原本的 CASCADE 同樣不會調整 {@code usage_count}，刪除語意不變；使用計數的重算為 DATA-01，另案處理。</p>
 *
 * <p>冪等性：{@code DELETE ... WHERE article_id = ?} 本身天然冪等，重送只會刪除 0 筆，
 * 因此不使用 IdempotencyService。單一 SQL 敘述即原子，不需要 {@code TransactionTemplate}。</p>
 *
 * <p>ACK 策略：AUTO（正常返回即 ack；拋例外由容器重試，耗盡進 DLQ）；失敗日誌與計數由 {@link ConsumerExecutor} 統一處理：</p>
 * <ul>
 *   <li>成功（含刪除 0 筆）→ ack</li>
 *   <li>舊 payload 缺 {@code articleUuid} → 無法定位要刪的資料，記 warn 後 ack（重送也不會變好）</li>
 *   <li>例外 → 往外拋，容器重試（最多 3 次），耗盡進 DLQ；每日孤兒校正排程（{@code ArticleTagOrphanReconciliationJob}）
 *       是事件遺失時的第二道保險</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TagArticleDeletedConsumer {

    /** ConsumerExecutor 指標與日誌共用的 consumer 識別名 */
    public static final String CONSUMER_NAME = "tag.article-deleted";

    /** 文章-標籤關聯資料存取（刪除連動） */
    private final ArticleTagRepository articleTagRepository;

    /** MQ consumer 共用執行包裝（失敗日誌、計數、冪等） */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理文章刪除事件。
     *
     * @param event       文章刪除事件
     */
    @RabbitListener(queues = TagRabbitMqConfig.QUEUE_TAG_ARTICLE_DELETED)
    public void onArticleDeleted(ArticleDeletedEvent event) {
        consumerExecutor.execute(CONSUMER_NAME,
                () -> "articleUuid=" + event.articleUuid() + ", eventId=" + event.eventId(),
                () -> {
                    if (event.articleUuid() == null) {
                        log.warn("文章刪除事件缺少 articleUuid，略過文章標籤關聯連動刪除 eventId={}", event.eventId());
                        return;
                    }
                    int deleted = articleTagRepository.deleteByArticleId(event.articleUuid());
                    log.info("文章刪除連動：已刪除文章標籤關聯 articleUuid={} eventId={} articleTags={}",
                            event.articleUuid(), event.eventId(), deleted);
                });
    }
}
