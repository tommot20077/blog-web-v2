package dowob.xyz.blog.module.version.config;

import dowob.xyz.blog.infrastructure.config.DeadLetterTopology;
import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import dowob.xyz.blog.infrastructure.event.UserEventContract;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * Version 模組 RabbitMQ 設定。訂閱 article.events 的 article.content.changed（寫快照）與 article.deleted（清除快照），
 * 以及 user.events 的 user.deleted（使用者刪帳後清除偏好設定，DATA-02）。
 *
 * <p>三個 queue 一律只帶 {@link DeadLetterTopology#dlqArgs()}：原有的 10 分鐘 {@code x-message-ttl}
 * 已依 Yuan 2026-11-07 決定（ARCH-18／C5）移除，與全站一致。既有 broker 若已宣告帶 TTL 的同名 queue，
 * 重新宣告會 406，須先刪 queue 再重建。</p>
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
        return new Queue(QUEUE_VERSION_SNAPSHOT, true, false, false, DeadLetterTopology.dlqArgs());
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
        return new Queue(QUEUE_VERSION_ARTICLE_DELETED, true, false, false, DeadLetterTopology.dlqArgs());
    }

    /**
     * 將 queue 綁定到文章事件 exchange 的 {@code article.deleted} routing key。
     *
     * @param versionArticleDeletedQueue 本模組的 queue
     * @param articleEventsExchange      共用的 article.events exchange（由 infrastructure 宣告）
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

    /** 使用者帳號刪除事件的 queue 名稱 */
    public static final String QUEUE_VERSION_USER_DELETED = "version.user-deleted";

    /**
     * 宣告使用者事件 exchange（與 user 模組宣告的 {@code user.events} 參數相同，重複宣告冪等）。
     *
     * <p>exchange 由 producer（user 模組）宣告；本模組不依賴 user 模組，故以事件契約常數
     * {@link UserEventContract#EXCHANGE} 自行宣告同名 exchange，bean 名稱與 user 端不同以避免衝突。</p>
     *
     * @return exchange
     */
    @Bean
    public TopicExchange versionUserEventsExchange() {
        return new TopicExchange(UserEventContract.EXCHANGE);
    }

    /**
     * 宣告使用者帳號刪除事件的 queue（durable，失敗訊息轉往 DLQ）。
     *
     * @return queue
     */
    @Bean
    public Queue versionUserDeletedQueue() {
        return new Queue(QUEUE_VERSION_USER_DELETED, true, false, false, DeadLetterTopology.dlqArgs());
    }

    /**
     * 將 queue 綁定到使用者事件 exchange 的 {@code user.deleted} routing key。
     *
     * @param versionUserDeletedQueue   本模組的 queue
     * @param versionUserEventsExchange 使用者事件 exchange 的宣告
     * @return binding
     */
    @Bean
    public Binding bindVersionUserDeleted(
            Queue versionUserDeletedQueue,
            @Qualifier("versionUserEventsExchange") TopicExchange versionUserEventsExchange) {
        return BindingBuilder
                .bind(versionUserDeletedQueue)
                .to(versionUserEventsExchange)
                .with(UserEventContract.ROUTING_KEY_DELETED);
    }
}
