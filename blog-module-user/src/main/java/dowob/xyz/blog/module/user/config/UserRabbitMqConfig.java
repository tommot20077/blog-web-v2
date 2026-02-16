package dowob.xyz.blog.module.user.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 用戶模組 RabbitMQ 設定
 *
 * <p>定義用戶事件相關的 Exchange、Queue 與 Binding，
 * 包含電子信箱驗證與密碼重設的訊息路由設定。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class UserRabbitMqConfig {

    /**
     * 用戶事件 Exchange 名稱
     */
    public static final String EXCHANGE = "user.events";

    /**
     * 電子信箱驗證 Queue 名稱
     */
    public static final String QUEUE_EMAIL_VERIFICATION = "user.email.verification";

    /**
     * 用戶已註冊 Routing Key
     */
    public static final String ROUTING_KEY_REGISTERED = "user.registered";

    /**
     * 密碼重設 Queue 名稱
     */
    public static final String QUEUE_PASSWORD_RESET = "user.password.reset";

    /**
     * 密碼重設 Routing Key
     */
    public static final String ROUTING_KEY_PASSWORD_RESET = "user.password.reset";

    /**
     * 建立死信佇列（DLQ）參數
     *
     * @return 包含 x-dead-letter-exchange 與 x-dead-letter-routing-key 的 Map
     */
    private Map<String, Object> dlqArgs() {
        return Map.of(
                "x-dead-letter-exchange", "blog.dlq",
                "x-dead-letter-routing-key", "dead-letter"
        );
    }

    /**
     * 建立用戶事件 Topic Exchange
     *
     * @return TopicExchange 實例
     */
    @Bean
    public TopicExchange userEventsExchange() {
        return new TopicExchange(EXCHANGE);
    }

    /**
     * 建立電子信箱驗證 Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue emailVerificationQueue() {
        return new Queue(QUEUE_EMAIL_VERIFICATION, true, false, false, dlqArgs());
    }

    /**
     * 建立電子信箱驗證 Queue 與 Exchange 的綁定
     *
     * @return Binding 實例
     */
    @Bean
    public Binding emailVerificationBinding() {
        return BindingBuilder
                .bind(emailVerificationQueue())
                .to(userEventsExchange())
                .with(ROUTING_KEY_REGISTERED);
    }

    /**
     * 建立密碼重設 Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue passwordResetQueue() {
        return new Queue(QUEUE_PASSWORD_RESET, true, false, false, dlqArgs());
    }

    /**
     * 建立密碼重設 Queue 與 Exchange 的綁定
     *
     * @return Binding 實例
     */
    @Bean
    public Binding passwordResetBinding() {
        return BindingBuilder
                .bind(passwordResetQueue())
                .to(userEventsExchange())
                .with(ROUTING_KEY_PASSWORD_RESET);
    }
}
