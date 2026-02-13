package dowob.xyz.blog.module.user.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
     * 建立用戶事件 Topic Exchange
     *
     * @return TopicExchange 實例
     */
    @Bean
    public TopicExchange userEventsExchange() {
        return new TopicExchange(EXCHANGE);
    }

    /**
     * 建立電子信箱驗證 Queue（持久化）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue emailVerificationQueue() {
        return new Queue(QUEUE_EMAIL_VERIFICATION, true);
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
}
