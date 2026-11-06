package dowob.xyz.blog.module.file.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.file.config.FileRabbitMqConfig;
import dowob.xyz.blog.module.file.service.FileArticleCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * File 模組訂閱 {@link ArticleDeletedEvent}（DATA-07）：文章被刪除後，清除綁定該文章、且不再被任何文章引用的檔案
 * （metadata 列與 MinIO 物件），否則儲存空間與使用者配額永久洩漏。判斷與刪除細節見 {@link FileArticleCleanupService}。
 *
 * <p>冪等：以 {@link ConsumerExecutor#executeIdempotent} 依 {@code eventId} 去重，標記與 DB 刪除在同一個資料庫交易；
 * 失敗（含 MinIO 刪除失敗）→ 標記一起 rollback → 容器重試時真的重做，耗盡進 DLQ。清理本身亦冪等
 * （已刪除的檔案重送時不再出現；併發刪除的 {@code FILE_NOT_FOUND} 視為已完成），所以舊訊息沒有 eventId 時略過去重照常處理亦安全。</p>
 *
 * <p><b>並行度固定為 1</b>（{@code concurrency = "1"}，覆蓋全域預設的 2）：{@code executeIdempotent} 在整個 body
 * 期間持有一條 DB 連線，而本 consumer 的 body 含 MinIO 網路呼叫；固定 1 條使連線池預算只多佔 1 條
 * （見 application.yaml 的 hikari 註解與 {@code RabbitListenerYamlBindingTest}）。文章刪除不頻繁，單一 consumer 足夠。</p>
 *
 * <p>ACK 策略：AUTO（正常返回即 ack；拋例外由容器重試，耗盡進 DLQ）；失敗日誌與計數由 {@link ConsumerExecutor} 統一處理。
 * 缺 {@code articleUuid} 的 payload 記 warn 後 ack（重送也不會變好）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileArticleDeletedConsumer {

    /** ConsumerExecutor 指標、日誌與 processed_events 去重共用的 consumer 識別名 */
    public static final String CONSUMER_NAME = "file.article-deleted";

    /** 文章刪除後的檔案清理服務 */
    private final FileArticleCleanupService cleanupService;

    /** MQ consumer 共用執行包裝（失敗日誌、計數、冪等） */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理文章刪除事件。
     *
     * @param event 文章刪除事件
     */
    @RabbitListener(queues = FileRabbitMqConfig.QUEUE_ARTICLE_DELETED, concurrency = "1")
    public void onArticleDeleted(ArticleDeletedEvent event) {
        Supplier<String> logContext = () -> "articleUuid=" + event.articleUuid() + ", eventId=" + event.eventId();
        if (event.articleUuid() == null) {
            consumerExecutor.execute(CONSUMER_NAME, logContext,
                    () -> log.warn("文章刪除事件缺少 articleUuid，略過檔案連動清理 eventId={}", event.eventId()));
            return;
        }
        consumerExecutor.executeIdempotent(CONSUMER_NAME, logContext, event.eventId(),
                () -> cleanupService.deleteFilesBoundToDeletedArticle(event.articleUuid()));
    }
}
