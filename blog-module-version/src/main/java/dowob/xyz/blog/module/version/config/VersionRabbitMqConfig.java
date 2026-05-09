package dowob.xyz.blog.module.version.config;

import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Version 模組 RabbitMQ 設定。訂閱 article.events / article.content.changed。
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class VersionRabbitMqConfig {

    public static final String QUEUE_VERSION_SNAPSHOT = "version.snapshot";

    @Bean
    public Queue versionSnapshotQueue() {
        return new Queue(QUEUE_VERSION_SNAPSHOT, true, false, false, dlqArgs());
    }

    @Bean
    public Binding bindVersionSnapshot(
            Queue versionSnapshotQueue,
            @Qualifier("articleEventsExchange") TopicExchange articleEventsExchange) {
        return BindingBuilder
                .bind(versionSnapshotQueue)
                .to(articleEventsExchange)
                .with(ArticleRabbitMqConfig.ROUTING_KEY_CONTENT_CHANGED);
    }

    /**
     * 對齊全站 DLQ 慣例：失敗訊息經 blog.dlq exchange + dead-letter routing key
     * 進入 infrastructure 宣告的共用死信佇列，避免訊息靜默丟失。
     */
    private Map<String, Object> dlqArgs() {
        return Map.of(
            "x-dead-letter-exchange", "blog.dlq",
            "x-dead-letter-routing-key", "dead-letter",
            "x-message-ttl", 600_000
        );
    }
}
