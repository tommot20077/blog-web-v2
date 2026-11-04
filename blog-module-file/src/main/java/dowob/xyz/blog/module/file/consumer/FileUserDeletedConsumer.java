package dowob.xyz.blog.module.file.consumer;

import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.file.config.FileRabbitMqConfig;
import dowob.xyz.blog.module.file.service.FileUserCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * File 模組訂閱 {@link UserDeletedEvent}：使用者刪除帳號（匿名化，DATA-02／DATA-07）後，
 * 清除其上傳、且未被保留文章引用的檔案（metadata 列與 MinIO 物件）。判斷與刪除細節見 {@link FileUserCleanupService}。
 *
 * <p>冪等性：清理服務只處理「仍存在」的檔案，已刪除的重送時不再出現（併發刪除的 {@code FILE_NOT_FOUND} 視為已完成），
 * 因此不使用 IdempotencyService。</p>
 *
 * <p>ACK 策略：AUTO（正常返回即 ack；拋例外由容器重試，耗盡進 DLQ）；失敗日誌與計數由 {@link ConsumerExecutor} 統一處理。
 * 缺 {@code userUuid} 的 payload 記 warn 後 ack（重送也不會變好）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileUserDeletedConsumer {

    /** ConsumerExecutor 指標與日誌共用的 consumer 識別名 */
    public static final String CONSUMER_NAME = "file.user-deleted";

    /** 使用者刪帳的檔案清理服務 */
    private final FileUserCleanupService cleanupService;

    /** MQ consumer 共用執行包裝（失敗日誌、計數、冪等） */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理使用者帳號刪除事件。
     *
     * @param event 使用者帳號刪除事件
     */
    @RabbitListener(queues = FileRabbitMqConfig.QUEUE_USER_DELETED)
    public void onUserDeleted(UserDeletedEvent event) {
        consumerExecutor.execute(CONSUMER_NAME,
                () -> "userUuid=" + event.userUuid() + ", eventId=" + event.eventId(),
                () -> {
                    if (event.userUuid() == null) {
                        log.warn("使用者刪除事件缺少 userUuid，略過檔案連動清理 eventId={}", event.eventId());
                        return;
                    }
                    cleanupService.deleteFilesNotReferencedByRetainedArticles(event.userUuid());
                });
    }
}
