package dowob.xyz.blog.module.user.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.module.user.config.UserRabbitMqConfig;
import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.service.UserMailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 密碼重設訊息消費者
 *
 * <p>監聽 {@code user.password.reset} Queue，接收密碼重設請求後發送重設密碼信。
 * 採用 Manual Ack 模式，確保訊息成功處理後才回報 ACK。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordResetConsumer {

    /** 用戶郵件服務 */
    private final UserMailService userMailService;

    /**
     * 處理密碼重設請求事件
     *
     * <p>郵件發送成功後呼叫 {@code channel.basicAck} 確認訊息。</p>
     *
     * @param event       密碼重設請求事件，包含用戶 ID、信箱與重設 Token
     * @param channel     RabbitMQ Channel（用於 Manual Ack）
     * @param deliveryTag 訊息投遞標籤
     */
    @RabbitListener(queues = UserRabbitMqConfig.QUEUE_PASSWORD_RESET)
    public void handlePasswordResetRequested(UserPasswordResetRequestedEvent event,
                                              Channel channel,
                                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            log.info("收到密碼重設請求 - userId={}, email={}", event.userId(), event.email());
            userMailService.sendPasswordResetEmail(event);
            log.info("密碼重設信已送出 - userId={}", event.userId());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("處理密碼重設事件失敗，訊息送往 DLQ，userId={}", event.userId(), e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException nackEx) {
                log.error("NACK 亦失敗，userId={}", event.userId(), nackEx);
            }
        }
    }
}
