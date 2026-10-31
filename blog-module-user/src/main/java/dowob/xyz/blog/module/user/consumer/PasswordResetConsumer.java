package dowob.xyz.blog.module.user.consumer;

import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.user.config.UserRabbitMqConfig;
import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.service.UserMailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 密碼重設訊息消費者
 *
 * <p>監聽 {@code user.password.reset} Queue，接收密碼重設請求後發送重設密碼信。
 * 採用 Manual Ack 模式，確保訊息成功處理後才回報 ACK；
 * 失敗日誌與計數統一交由 {@link ConsumerExecutor} 處理；失敗往外拋，由容器重試、耗盡後進 DLQ。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mail.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class PasswordResetConsumer {

    /** consumer 識別名（作為 ConsumerExecutor 指標與日誌的 consumer tag） */
    public static final String CONSUMER_NAME = "user.password-reset";

    /** 用戶郵件服務 */
    private final UserMailService userMailService;

    /** MQ consumer 共用執行包裝（失敗日誌、計數、冪等） */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理密碼重設請求事件
     *
     * <p><b>冪等</b>：事件帶發送端產生的 {@code eventId}，以 {@link ConsumerExecutor#executeIdempotent}（依
     * {@code (eventId, CONSUMER_NAME)}）去重——同一事件重送時不會重寄重設密碼信，但仍 ack（不進 DLQ）。
     * 使用者再次申請忘記密碼時 {@code AuthService} 會產生新的 {@code eventId}，不會被誤判為重送。
     * 舊格式 in-flight 訊息的 {@code eventId} 為 null，無法去重，照常寄送。</p>
     *
     * <p><b>取捨（Yuan 2026-10-31 決定：寄信成功後才標記）</b>：{@code processed_events} 的標記與寄信放在同一個
     * 資料庫交易內，寄信（SMTP）成功之後交易才 commit；寄信失敗 → 交易 rollback、標記一起消失，
     * 訊息進 DLQ 後人工以同一 eventId 重投時會真的重寄，而不是被去重擋下。</p>
     *
     * <p><b>風險</b>：寄信成功但 commit 失敗（資料庫在寄信完成的瞬間出問題）→ 標記消失，重送會再寄一封
     * （at-least-once，可能重寄同一連結）。另外交易在寄信期間（SMTP timeout 5 秒）佔用一條資料庫連線。</p>
     *
     * <p><b>營運須知</b>：被去重跳過時會以 INFO 記錄「重複事件已跳過」（含 eventId、userUuid，由
     * {@link ConsumerExecutor} 輸出）。ack 前當機造成的重投會被擋下而不重寄；若使用者仍沒收到信，
     * 請其走「再次忘記密碼」（產生新 eventId）。</p>
     *
     * <p>郵件發送成功後正常返回（容器 ack）；失敗則往外拋，由容器重試（最多 3 次，寄信失敗可能重寄同一連結），耗盡後送 DLQ。</p>
     *
     * @param event       密碼重設請求事件，包含用戶 UUID、信箱與重設 Token
     */
    @RabbitListener(
            queues = UserRabbitMqConfig.QUEUE_PASSWORD_RESET,
            autoStartup = "${app.mail.consumer-enabled:true}"
    )
    public void handlePasswordResetRequested(UserPasswordResetRequestedEvent event) {
        consumerExecutor.executeIdempotent(CONSUMER_NAME,
                () -> "userUuid=" + event.userUuid() + ", eventId=" + event.eventId(),
                event.eventId(), () -> {
                    log.info("收到密碼重設請求 - userUuid={}, eventId={}", event.userUuid(), event.eventId());
                    userMailService.sendPasswordResetEmail(event);
                    log.info("密碼重設信已送出 - userUuid={}", event.userUuid());
                });
    }
}
