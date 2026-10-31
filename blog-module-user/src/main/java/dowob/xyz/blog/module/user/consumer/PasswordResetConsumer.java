package dowob.xyz.blog.module.user.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.user.config.UserRabbitMqConfig;
import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.service.UserMailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 密碼重設訊息消費者
 *
 * <p>監聽 {@code user.password.reset} Queue，接收密碼重設請求後發送重設密碼信。
 * 採用 Manual Ack 模式，確保訊息成功處理後才回報 ACK；
 * ack／nack 與失敗計數統一交由 {@link ConsumerExecutor} 處理。</p>
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

    /** MQ consumer 共用 ack/nack 包裝與計數 */
    private final ConsumerExecutor consumerExecutor;

    /** MQ event 冪等處理服務 */
    private final IdempotencyService idempotencyService;

    /**
     * 處理密碼重設請求事件
     *
     * <p><b>冪等</b>：事件帶發送端產生的 {@code eventId}，以 {@link IdempotencyService}（依
     * {@code (eventId, CONSUMER_NAME)}）去重——同一事件重送時不會重寄重設密碼信，但仍 ack（不進 DLQ）。
     * 使用者再次申請忘記密碼時 {@code AuthService} 會產生新的 {@code eventId}，不會被誤判為重送。
     * 舊格式 in-flight 訊息的 {@code eventId} 為 null，無法去重，照常寄送。</p>
     *
     * <p><b>取捨</b>：標記發生在寄信「前」（沿用 {@link IdempotencyService} 既有語意）。寄信失敗進 DLQ 後，
     * 若人工以同一訊息重新投遞會被去重擋下；使用者可重新申請忘記密碼（產生新 eventId）取得新信。
     * 這是刻意選擇「寧可漏寄一次、不重複寄送含一次性 token 的信」。</p>
     *
     * <p><b>營運須知</b>：被去重跳過時會以 INFO 記錄「重複事件已跳過」（含 eventId、userUuid）。
     * DLQ 人工重投同一個 eventId 的訊息、或 ack 前當機造成的重投，都會被擋下而不寄信；
     * 要補寄，請使用者走「再次忘記密碼」（產生新 eventId）。</p>
     *
     * <p>郵件發送成功後由 {@link ConsumerExecutor} 確認（ack）訊息；失敗則 nack 送 DLQ。</p>
     *
     * @param event       密碼重設請求事件，包含用戶 UUID、信箱與重設 Token
     * @param channel     RabbitMQ Channel（交由 {@link ConsumerExecutor} 執行 ack／nack）
     * @param deliveryTag 訊息投遞標籤
     */
    @RabbitListener(
            queues = UserRabbitMqConfig.QUEUE_PASSWORD_RESET,
            autoStartup = "${app.mail.consumer-enabled:true}"
    )
    public void handlePasswordResetRequested(UserPasswordResetRequestedEvent event,
                                              Channel channel,
                                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        consumerExecutor.execute(CONSUMER_NAME,
                () -> "userUuid=" + event.userUuid() + ", eventId=" + event.eventId(),
                channel, deliveryTag, () -> {
                    if (event.eventId() != null
                            && !idempotencyService.markProcessed(event.eventId(), CONSUMER_NAME)) {
                        log.info("重複事件已跳過（未重寄重設密碼信）- eventId={}, userUuid={}", event.eventId(), event.userUuid());
                        return;
                    }
                    log.info("收到密碼重設請求 - userUuid={}, eventId={}", event.userUuid(), event.eventId());
                    userMailService.sendPasswordResetEmail(event);
                    log.info("密碼重設信已送出 - userUuid={}", event.userUuid());
                });
    }
}
