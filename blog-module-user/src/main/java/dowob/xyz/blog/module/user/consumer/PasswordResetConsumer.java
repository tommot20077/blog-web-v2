package dowob.xyz.blog.module.user.consumer;

import com.rabbitmq.client.Channel;
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

    /**
     * 處理密碼重設請求事件
     *
     * <p>郵件發送成功後由 {@link ConsumerExecutor} 確認（ack）訊息；失敗則 nack 送 DLQ。</p>
     *
     * @param event       密碼重設請求事件，包含用戶 ID、信箱與重設 Token
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
        consumerExecutor.execute(CONSUMER_NAME, () -> "userId=" + event.userId(),
                channel, deliveryTag, () -> {
                    log.info("收到密碼重設請求 - userId={}, email={}", event.userId(), event.email());
                    userMailService.sendPasswordResetEmail(event);
                    log.info("密碼重設信已送出 - userId={}", event.userId());
                });
    }
}
