package dowob.xyz.blog.module.user.consumer;

import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.user.config.UserRabbitMqConfig;
import dowob.xyz.blog.module.user.model.event.RegistrationAttemptOnExistingEmailEvent;
import dowob.xyz.blog.module.user.service.UserMailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 重複信箱註冊通知消費者（SEC-14／Q8）
 *
 * <p>監聽 {@code user.registration.duplicate-email} Queue，接收「有人嘗試以已註冊信箱註冊」事件後，
 * 透過郵件服務寄通知信給原信箱主人。失敗日誌與計數統一交由 {@link ConsumerExecutor} 處理；
 * 失敗往外拋，由容器重試、耗盡後進 DLQ。與驗證信／重設密碼 consumer 一樣受
 * {@code app.mail.consumer-enabled} 開關控制。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mail.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class RegistrationAttemptNoticeConsumer {

    /** consumer 識別名（作為 ConsumerExecutor 指標、冪等標記與日誌的 consumer tag） */
    public static final String CONSUMER_NAME = "user.registration-attempt-notice";

    /** 用戶郵件服務 */
    private final UserMailService userMailService;

    /** MQ consumer 共用執行包裝（失敗日誌、計數、冪等） */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理重複信箱註冊事件，寄通知信給原信箱主人
     *
     * <p><b>冪等</b>：事件帶發送端產生的 {@code eventId}，以 {@link ConsumerExecutor#executeIdempotent}（依
     * {@code (eventId, CONSUMER_NAME)}）去重——同一事件重送時不會重寄，但仍 ack（不進 DLQ）。
     * 沿用 Z3-a 的「寄信成功後才標記」：標記與寄信在同一個資料庫交易內，寄信（SMTP）成功之後才 commit；
     * 寄信失敗 → rollback、標記一起消失，進 DLQ 後人工以同一 eventId 重投時會真的重寄。
     * 風險與營運須知同 {@link EmailVerificationConsumer#handleUserRegistered}：寄信成功但 commit 失敗時重送會再寄一封
     * （at-least-once；通知信內容固定、無 token，重複的影響僅是多一封提醒）。</p>
     *
     * <p>同一信箱的通知頻率另由發送端限流（{@code AuthService} 的 24 小時名額），consumer 不重複限流。</p>
     *
     * @param event 重複信箱註冊事件，包含原信箱主人的 uuid、信箱與暱稱
     */
    @RabbitListener(
            queues = UserRabbitMqConfig.QUEUE_REGISTRATION_ATTEMPT,
            autoStartup = "${app.mail.consumer-enabled:true}"
    )
    public void handleRegistrationAttempt(RegistrationAttemptOnExistingEmailEvent event) {
        consumerExecutor.executeIdempotent(CONSUMER_NAME,
                () -> "userUuid=" + event.userUuid() + ", eventId=" + event.eventId(),
                event.eventId(), () -> {
                    log.info("收到重複信箱註冊事件 - userUuid={}, eventId={}", event.userUuid(), event.eventId());
                    userMailService.sendRegistrationAttemptNotice(event);
                    log.info("重複信箱註冊通知信已送出 - userUuid={}", event.userUuid());
                });
    }
}
