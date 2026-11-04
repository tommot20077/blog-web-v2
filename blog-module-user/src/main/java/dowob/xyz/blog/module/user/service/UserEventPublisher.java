package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.infrastructure.event.UserEventContract;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * User 跨模組事件發布元件。
 *
 * <p>統一封裝 user 模組對<b>其他模組</b>發送的 RabbitMQ 訊息，採 best-effort 策略：失敗時僅記錄、不往外拋，
 * 不影響已 commit 的主流程（比照 {@code SeriesEventPublisher}、{@code ArticleEventPublisher}）。
 * <b>呼叫端必須在 DB 交易 commit 之後才呼叫</b>（{@code code-standards.md}「Transaction + MQ 時序」）：
 * 本類別本身沒有 {@code @Transactional}，但被交易方法呼叫時一樣在交易內，所以只能由已離開交易的程式碼
 * （例如 {@code TransactionTemplate} 之後）呼叫。user 模組內部的註冊、重設密碼事件由 {@code AuthService} 直接發送，
 * 不屬於本元件的職責。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventPublisher {

    /** MQ 樣板（Jackson JSON 轉換由 {@code RabbitMqConfig} 設定） */
    private final RabbitTemplate rabbitTemplate;

    /**
     * 發送帳號刪除事件至 {@code user.events}（routing key {@code user.deleted}）。
     *
     * <p>article、reading、version、tag、file、search 模組訂閱此事件，清理該使用者的個人資料與衍生資料（DATA-02）。
     * 發送失敗時匿名化已 commit，不往外拋；但各模組的連動清理不會發生，故記錄 <b>error</b>
     * （帶 userUuid 與 eventId，供追查與人工補送）。事件遺失沒有第二道自動保險——
     * 每日孤兒校正是否需要涵蓋「指向已刪除使用者的資料」見 DATA-02 的後續建議。</p>
     *
     * @param userUuid 已被匿名化（且已 commit）的使用者公開 UUID
     */
    public void publishDeleted(UUID userUuid) {
        UUID eventId = UUID.randomUUID();
        try {
            rabbitTemplate.convertAndSend(
                    UserEventContract.EXCHANGE,
                    UserEventContract.ROUTING_KEY_DELETED,
                    new UserDeletedEvent(eventId, userUuid, Instant.now()));
        } catch (Exception e) {
            log.error("UserDeletedEvent MQ 發送失敗（best-effort），各模組不會連動清理該使用者的資料，需人工補送："
                            + "userUuid={}, eventId={}, error={}",
                    userUuid, eventId, e.getMessage(), e);
        }
    }
}
