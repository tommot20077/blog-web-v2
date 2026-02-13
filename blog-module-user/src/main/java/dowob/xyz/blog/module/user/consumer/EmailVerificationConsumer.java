package dowob.xyz.blog.module.user.consumer;

import dowob.xyz.blog.module.user.config.UserRabbitMqConfig;
import dowob.xyz.blog.module.user.model.event.UserRegisteredEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 電子信箱驗證訊息消費者
 *
 * <p>監聽 {@code user.email.verification} Queue，接收用戶註冊事件後
 * 負責發送驗證信至用戶信箱。MVP 階段僅記錄日誌，待郵件服務設定完成後再啟用實際發送。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
public class EmailVerificationConsumer {

    /**
     * 處理用戶已註冊事件，發送電子信箱驗證信
     *
     * <p>MVP 階段記錄驗證連結至日誌；正式環境應改為呼叫 JavaMailSender 發送郵件。</p>
     *
     * @param event 用戶已註冊事件，包含信箱、暱稱與驗證 Token
     */
    @RabbitListener(queues = UserRabbitMqConfig.QUEUE_EMAIL_VERIFICATION)
    public void handleUserRegistered(UserRegisteredEvent event) {
        log.info("收到用戶註冊事件 - userId={}, email={}, nickname={}",
                event.userId(), event.email(), event.nickname());
        log.info("驗證連結（開發模式）: /api/v1/auth/verify-email?token={}", event.verificationToken());
    }
}
