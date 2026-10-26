package dowob.xyz.blog.module.version.config;

import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Version 模組 RabbitMQ 設定。訂閱 article.events 的 article.content.changed（寫快照）與 article.deleted（清除快照）。
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class VersionRabbitMqConfig {

    public static final String QUEUE_VERSION_SNAPSHOT = "version.snapshot";

    /** 文章刪除事件的 queue 名稱 */
    public static final String QUEUE_VERSION_ARTICLE_DELETED = "version.article-deleted";

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
                .with(ArticleEventContract.ROUTING_KEY_CONTENT_CHANGED);
    }

    /**
     * 宣告文章刪除事件的 queue（durable，失敗訊息轉往 DLQ）。
     *
     * @return queue
     */
    @Bean
    public Queue versionArticleDeletedQueue() {
        return new Queue(QUEUE_VERSION_ARTICLE_DELETED, true, false, false, dlqArgs());
    }

    /**
     * 將 queue 綁定到文章事件 exchange 的 {@code article.deleted} routing key。
     *
     * @param versionArticleDeletedQueue 本模組的 queue
     * @param articleEventsExchange      article 模組宣告的文章事件 exchange
     * @return binding
     */
    @Bean
    public Binding bindVersionArticleDeleted(
            Queue versionArticleDeletedQueue,
            @Qualifier("articleEventsExchange") TopicExchange articleEventsExchange) {
        return BindingBuilder
                .bind(versionArticleDeletedQueue)
                .to(articleEventsExchange)
                .with(ArticleEventContract.ROUTING_KEY_DELETED);
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
