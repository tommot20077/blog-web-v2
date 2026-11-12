package dowob.xyz.blog.module.reading.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.reading.config.ReadingRabbitMqConfig;
import dowob.xyz.blog.module.reading.mapper.ReadingArticleCleanupMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reading 模組訂閱 {@link ArticleDeletedEvent}：文章被刪除後，刪除該文章的按讚、收藏、劃線與閱讀進度。
 *
 * <p>只讀事件的 {@code articleUuid}，不讀任何 Long 欄位。本模組對文章刪除要清的資料表以
 * {@link ReadingArticleCleanupMapper} 的「每表一個 delete 方法」逐一呼叫，並在<b>同一個交易</b>內完成：
 * 任何一張表刪除失敗則整體回滾，例外往外拋、由容器重試並於耗盡後進 DLQ，不會留下「只清了一半」的狀態。
 * 目前涵蓋 {@code user_article_likes}、{@code user_bookmarks}、{@code user_highlights}、
 * {@code user_reading_progress} 四張表。</p>
 *
 * <p>冪等性：{@code DELETE ... WHERE article_uuid = ?} 本身天然冪等，重送只會刪除 0 筆，
 * 因此不使用 IdempotencyService。</p>
 *
 * <p>ACK 策略：AUTO（正常返回即 ack；拋例外由容器重試，耗盡進 DLQ）；失敗日誌與計數由 {@link ConsumerExecutor} 統一處理：</p>
 * <ul>
 *   <li>成功（含刪除 0 筆）→ ack</li>
 *   <li>舊 payload 缺 {@code articleUuid} → 無法定位要刪的資料，記 warn 後 ack（重送也不會變好）</li>
 *   <li>例外 → 交易回滾、往外拋，容器重試（最多 3 次），耗盡進 DLQ；每日孤兒校正排程是事件遺失時的第二道保險</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadingArticleDeletedConsumer {

    /** ConsumerExecutor 指標與日誌共用的 consumer 識別名 */
    public static final String CONSUMER_NAME = "reading.article-deleted";

    /** 文章刪除連動用的 mapper（每張表一個 delete 方法） */
    private final ReadingArticleCleanupMapper cleanupMapper;

    /** 讓多張表的刪除落在同一個交易（consumer 方法本身不加 {@code @Transactional}，與專案的交易慣例一致） */
    private final TransactionTemplate transactionTemplate;

    /** MQ consumer 共用執行包裝（失敗日誌、計數、冪等） */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理文章刪除事件。
     *
     * @param event       文章刪除事件
     */
    @RabbitListener(queues = ReadingRabbitMqConfig.QUEUE_READING_ARTICLE_DELETED)
    public void onArticleDeleted(ArticleDeletedEvent event) {
        consumerExecutor.execute(CONSUMER_NAME,
                () -> "articleUuid=" + event.articleUuid() + ", eventId=" + event.eventId(),
                () -> {
                    if (event.articleUuid() == null) {
                        log.warn("文章刪除事件缺少 articleUuid，略過按讚／收藏／劃線／閱讀進度連動刪除 eventId={}", event.eventId());
                        return;
                    }
                    int[] deleted = new int[4];
                    transactionTemplate.executeWithoutResult(status -> {
                        deleted[0] = cleanupMapper.deleteLikesByArticleUuid(event.articleUuid());
                        deleted[1] = cleanupMapper.deleteBookmarksByArticleUuid(event.articleUuid());
                        deleted[2] = cleanupMapper.deleteHighlightsByArticleUuid(event.articleUuid());
                        deleted[3] = cleanupMapper.deleteProgressByArticleUuid(event.articleUuid());
                    });
                    log.info("文章刪除連動：已刪除按讚／收藏／劃線／閱讀進度 articleUuid={} eventId={} likes={} bookmarks={} highlights={} progress={}",
                            event.articleUuid(), event.eventId(), deleted[0], deleted[1], deleted[2], deleted[3]);
                });
    }
}
