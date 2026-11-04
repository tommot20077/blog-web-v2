package dowob.xyz.blog.module.version.consumer;

import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.version.config.VersionRabbitMqConfig;
import dowob.xyz.blog.module.version.mapper.UserPreferenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Version 模組訂閱 {@link UserDeletedEvent}：使用者刪除帳號（匿名化，DATA-02）後，刪除該使用者的偏好設定（{@code user_preferences}）。
 *
 * <p>文章版本快照（{@code article_versions}）<b>不在此處理</b>：已發布文章保留，其版本歷史隨文章保留
 * （快照上的作者 uuid 經 users 列匿名化而不再對應到個人）；被刪除的草稿／待審文章，其快照由
 * {@code VersionArticleDeletedConsumer} 在文章刪除時清除。</p>
 *
 * <p>冪等性：{@code DELETE ... WHERE user_uuid = ?} 是單一 SQL 敘述（本身即原子）且天然冪等，重送只會刪除 0 筆，
 * 因此不使用 IdempotencyService、也不需要交易模板。</p>
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
public class VersionUserDeletedConsumer {

    /** ConsumerExecutor 指標與日誌共用的 consumer 識別名 */
    public static final String CONSUMER_NAME = "version.user-deleted";

    /** 使用者偏好設定 mapper */
    private final UserPreferenceMapper preferenceMapper;

    /** MQ consumer 共用執行包裝（失敗日誌、計數、冪等） */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理使用者帳號刪除事件。
     *
     * @param event 使用者帳號刪除事件
     */
    @RabbitListener(queues = VersionRabbitMqConfig.QUEUE_VERSION_USER_DELETED)
    public void onUserDeleted(UserDeletedEvent event) {
        consumerExecutor.execute(CONSUMER_NAME,
                () -> "userUuid=" + event.userUuid() + ", eventId=" + event.eventId(),
                () -> {
                    if (event.userUuid() == null) {
                        log.warn("使用者刪除事件缺少 userUuid，略過偏好設定連動刪除 eventId={}", event.eventId());
                        return;
                    }
                    int deleted = preferenceMapper.deleteByUserUuid(event.userUuid());
                    log.info("使用者刪除連動：已刪除偏好設定 userUuid={} eventId={} preferences={}",
                            event.userUuid(), event.eventId(), deleted);
                });
    }
}
