package dowob.xyz.blog.module.user.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
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

/**
 * 電子信箱驗證訊息消費者
 *
 * <p>監聽 {@code user.email.verification} Queue，接收用戶註冊事件後
 * 負責透過郵件服務發送驗證信至用戶信箱。
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
public class EmailVerificationConsumer {

    /** consumer 識別名（作為 ConsumerExecutor 指標與日誌的 consumer tag） */
    public static final String CONSUMER_NAME = "user.email-verification";

    /** 用戶郵件服務 */
    private final UserMailService userMailService;

    /** MQ consumer 共用 ack/nack 包裝與計數 */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理用戶已註冊事件，發送電子信箱驗證信
     *
     * <p>郵件發送成功後由 {@link ConsumerExecutor} 確認（ack）訊息；失敗則 nack 送 DLQ。</p>
     *
     * @param event       用戶已註冊事件，包含信箱、暱稱與驗證 Token
     * @param channel     RabbitMQ Channel（交由 {@link ConsumerExecutor} 執行 ack／nack）
     * @param deliveryTag 訊息投遞標籤
     */
    @RabbitListener(
            queues = UserRabbitMqConfig.QUEUE_EMAIL_VERIFICATION,
            autoStartup = "${app.mail.consumer-enabled:true}"
    )
    public void handleUserRegistered(UserRegisteredEvent event,
                                     Channel channel,
                                     @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        consumerExecutor.execute(CONSUMER_NAME, () -> "userId=" + event.userId(),
                channel, deliveryTag, () -> {
                    log.info("收到用戶註冊事件 - userId={}, email={}, nickname={}",
                            event.userId(), event.email(), event.nickname());
                    userMailService.sendVerificationEmail(event);
                    log.info("驗證信已送出 - userId={}", event.userId());
                });
    }
}
