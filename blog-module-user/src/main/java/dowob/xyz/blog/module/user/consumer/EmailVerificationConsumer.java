package dowob.xyz.blog.module.user.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.module.user.config.UserRabbitMqConfig;
import dowob.xyz.blog.module.user.model.event.UserRegisteredEvent;
import dowob.xyz.blog.module.user.service.UserMailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 電子信箱驗證訊息消費者
 *
 * <p>監聽 {@code user.email.verification} Queue，接收用戶註冊事件後
 * 負責透過郵件服務發送驗證信至用戶信箱。
 * 採用 Manual Ack 模式，確保訊息成功處理後才回報 ACK。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mail.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class EmailVerificationConsumer {

    /** 用戶郵件服務 */
    private final UserMailService userMailService;

    /**
     * 處理用戶已註冊事件，發送電子信箱驗證信
     *
     * <p>郵件發送成功後呼叫 {@code channel.basicAck} 確認訊息。</p>
     *
     * @param event       用戶已註冊事件，包含信箱、暱稱與驗證 Token
     * @param channel     RabbitMQ Channel（用於 Manual Ack）
     * @param deliveryTag 訊息投遞標籤
     */
    @RabbitListener(
            queues = UserRabbitMqConfig.QUEUE_EMAIL_VERIFICATION,
            autoStartup = "${app.mail.consumer-enabled:true}"
    )
    public void handleUserRegistered(UserRegisteredEvent event,
                                     Channel channel,
                                     @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            log.info("收到用戶註冊事件 - userId={}, email={}, nickname={}",
                    event.userId(), event.email(), event.nickname());
            userMailService.sendVerificationEmail(event);
            log.info("驗證信已送出 - userId={}", event.userId());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("處理用戶註冊事件失敗，訊息送往 DLQ，userId={}", event.userId(), e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException nackEx) {
                log.error("NACK 亦失敗，userId={}", event.userId(), nackEx);
            }
        }
    }
}
