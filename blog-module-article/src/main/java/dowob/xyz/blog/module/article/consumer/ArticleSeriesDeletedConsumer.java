package dowob.xyz.blog.module.article.consumer;

import dowob.xyz.blog.infrastructure.event.SeriesDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.module.article.mapper.ArticleSeriesCleanupMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Article 模組訂閱 {@link SeriesDeletedEvent}：系列被刪除後，清空成員文章的系列歸屬。
 *
 * <p>V32 起 {@code articles.series_uuid} 沒有指向 {@code series} 的 FK，原本的
 * {@code ON DELETE SET NULL} 改由本 consumer（即時）與 {@code ArticleSeriesOrphanReconciliationJob}
 * （每日校正，事件遺失時的第二道保險）負責。刪除語意不變：<b>文章留存，只清空 {@code series_uuid} 與
 * {@code series_position}</b>。與原 FK 的 SET NULL 的差異皆為有意：{@code series_position} 一併清空（系列已不存在）；
 * {@code version} 遞增（過期的整筆 save 會撞樂觀鎖，不會把 ghost 歸屬寫回）；{@code updated_at} 不動（與改造前相同）。
 * 細節見 {@code ArticleSeriesCleanupMapper}。</p>
 *
 * <p>只讀事件的 {@code seriesUuid}。{@code UPDATE ... WHERE series_uuid = ?} 是單一 SQL 敘述（本身即原子）
 * 且天然冪等，重送只會更新 0 筆，因此不使用 IdempotencyService、也不需要交易模板。</p>
 *
 * <p>ACK 策略：AUTO（正常返回即 ack；拋例外由容器重試，耗盡進 DLQ）；失敗日誌與計數由 {@link ConsumerExecutor} 統一處理：</p>
 * <ul>
 *   <li>成功（含更新 0 筆）→ ack</li>
 *   <li>缺 {@code seriesUuid} → 無法定位要清空的文章，記 warn 後 ack（重送也不會變好）</li>
 *   <li>例外 → 往外拋，容器重試（最多 3 次），耗盡進 DLQ；每日孤兒校正排程是事件遺失時的第二道保險</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleSeriesDeletedConsumer {

    /** ConsumerExecutor 指標與日誌共用的 consumer 識別名 */
    public static final String CONSUMER_NAME = "article.series-deleted";

    /** 系列刪除連動用的 mapper */
    private final ArticleSeriesCleanupMapper cleanupMapper;

    /** MQ consumer 共用執行包裝（失敗日誌、計數、冪等） */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理系列刪除事件。
     *
     * @param event       系列刪除事件
     */
    @RabbitListener(queues = ArticleRabbitMqConfig.QUEUE_SERIES_DELETED)
    public void onSeriesDeleted(SeriesDeletedEvent event) {
        consumerExecutor.execute(CONSUMER_NAME,
                () -> "seriesUuid=" + event.seriesUuid() + ", eventId=" + event.eventId(),
                () -> {
                    if (event.seriesUuid() == null) {
                        log.warn("系列刪除事件缺少 seriesUuid，略過文章系列歸屬清空 eventId={}", event.eventId());
                        return;
                    }
                    int cleared = cleanupMapper.clearSeriesByUuid(event.seriesUuid());
                    log.info("系列刪除連動：已清空成員文章的系列歸屬 seriesUuid={} eventId={} articles={}",
                            event.seriesUuid(), event.eventId(), cleared);
                });
    }
}
