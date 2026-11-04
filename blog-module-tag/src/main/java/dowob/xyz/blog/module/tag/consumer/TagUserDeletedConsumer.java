package dowob.xyz.blog.module.tag.consumer;

import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.tag.config.TagRabbitMqConfig;
import dowob.xyz.blog.module.tag.repository.UserTagFollowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Tag 模組訂閱 {@link UserDeletedEvent}：使用者刪除帳號（匿名化，DATA-02）後，刪除該使用者的標籤追蹤（{@code user_tag_follows}）。
 *
 * <p>追蹤紀錄沒有任何衍生計數（{@code tags} 不存追蹤人數），刪除不需要重算。{@code tags} 本身與文章標籤關聯不動。</p>
 *
 * <p>冪等性：{@code DELETE ... WHERE user_id = ?} 是單一 SQL 敘述（本身即原子）且天然冪等，重送只會刪除 0 筆，
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
public class TagUserDeletedConsumer {

    /** ConsumerExecutor 指標與日誌共用的 consumer 識別名 */
    public static final String CONSUMER_NAME = "tag.user-deleted";

    /** 使用者標籤追蹤資料存取 */
    private final UserTagFollowRepository followRepository;

    /** MQ consumer 共用執行包裝（失敗日誌、計數、冪等） */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理使用者帳號刪除事件。
     *
     * @param event 使用者帳號刪除事件
     */
    @RabbitListener(queues = TagRabbitMqConfig.QUEUE_TAG_USER_DELETED)
    public void onUserDeleted(UserDeletedEvent event) {
        consumerExecutor.execute(CONSUMER_NAME,
                () -> "userUuid=" + event.userUuid() + ", eventId=" + event.eventId(),
                () -> {
                    if (event.userUuid() == null) {
                        log.warn("使用者刪除事件缺少 userUuid，略過標籤追蹤連動刪除 eventId={}", event.eventId());
                        return;
                    }
                    int deleted = followRepository.deleteByUserId(event.userUuid());
                    log.info("使用者刪除連動：已刪除標籤追蹤 userUuid={} eventId={} follows={}",
                            event.userUuid(), event.eventId(), deleted);
                });
    }
}
