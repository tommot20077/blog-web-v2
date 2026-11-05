package dowob.xyz.blog.module.file.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleContentChangedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.file.config.FileRabbitMqConfig;
import dowob.xyz.blog.module.file.service.ArticleFileBindingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * File 模組訂閱 {@link ArticleContentChangedEvent}（SAVED／PUBLISHED／RESTORED）：依文章最新內文回填
 * 「檔案 → 文章」綁定（W5A／Q12，取代 article 模組在儲存／還原後的同步呼叫）。
 *
 * <p>三種 action 的處理相同——不論哪種，目標都是讓「內文引用的圖 == 已綁定的圖」，細節見
 * {@link ArticleFileBindingService}。</p>
 *
 * <p>冪等：以 {@link ConsumerExecutor#executeIdempotent} 依 {@code eventId} 去重，標記與綁定寫入在同一個資料庫交易
 * （{@code FileService#bindToArticle} 為 REQUIRED，會加入此交易）；失敗 → 標記一起 rollback → 重試時真的重做。
 * 回填本身也冪等（完整替換），所以舊訊息沒有 eventId 時略過去重照常處理亦安全。</p>
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
public class FileArticleContentChangedConsumer {

    /** ConsumerExecutor 指標、日誌與 processed_events 去重共用的 consumer 識別名 */
    public static final String CONSUMER_NAME = "file.article-binding";

    /** 依文章內文回填檔案綁定的服務 */
    private final ArticleFileBindingService bindingService;

    /** MQ consumer 共用執行包裝（失敗日誌、計數、冪等） */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理文章內容變更事件。
     *
     * @param event 文章內容變更事件
     */
    @RabbitListener(queues = FileRabbitMqConfig.QUEUE_ARTICLE_CONTENT_CHANGED)
    public void onContentChanged(ArticleContentChangedEvent event) {
        Supplier<String> logContext = () -> "articleUuid=" + event.articleUuid()
                + ", action=" + event.action() + ", eventId=" + event.eventId();
        if (event.articleUuid() == null) {
            consumerExecutor.execute(CONSUMER_NAME, logContext,
                    () -> log.warn("文章內容變更事件缺少 articleUuid，略過檔案綁定回填 eventId={}", event.eventId()));
            return;
        }
        consumerExecutor.executeIdempotent(CONSUMER_NAME, logContext, event.eventId(),
                () -> bindingService.rebindFromArticleContent(event.articleUuid()));
    }
}
