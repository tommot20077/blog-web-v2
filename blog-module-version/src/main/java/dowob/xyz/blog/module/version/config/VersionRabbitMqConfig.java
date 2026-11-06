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

import java.util.Map;

/**
 * Version 模組 RabbitMQ 設定。訂閱 article.events 的 article.content.changed（寫快照）與 article.deleted（清除快照），
 * 以及 user.events 的 user.deleted（使用者刪帳後清除偏好設定，DATA-02）。
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
        return new Queue(QUEUE_VERSION_USER_DELETED, true, false, false, dlqArgs());
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

    /**
     * Version 模組 queue 的 DLQ 參數：全站共用的 {@link DeadLetterTopology#dlqArgs()} 再加上 {@code x-message-ttl=600000}（10 分鐘）。
     *
     * <p><b>TTL 是否保留待 Yuan 決策（findings ARCH-18）</b>：全站其他 queue 都沒有 TTL，唯獨 version 的三個 queue
     * （snapshot、article-deleted、user-deleted）會讓 10 分鐘內未被消費的訊息過期並進 DLQ。ARCH-19 收斂 DLQ 常數時
     * <b>刻意完整保留此行為</b>（queue 參數不變，否則既有 queue 重新宣告會 406、需先刪再建）；移除 TTL 屬 ARCH-18 的獨立變更。</p>
     *
     * @return 含 DLQ 與 TTL 的新 Map
     */
    private Map<String, Object> dlqArgs() {
        Map<String, Object> args = DeadLetterTopology.dlqArgs();
        args.put("x-message-ttl", 600_000);
        return args;
    }
}
