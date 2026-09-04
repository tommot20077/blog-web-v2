package dowob.xyz.blog.module.article.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 文章模組 RabbitMQ 設定
 *
 * <p>
 * 定義文章事件相關的 Exchange 與瀏覽計數事件的 Queue、Binding。
 * 文章發布事件（{@link #ROUTING_KEY_PUBLISHED}）本模組只負責發布，
 * 實際消費由 search／recommend 模組各自宣告的 Queue 承接，不在本模組宣告。
 * 所有 Queue 均配置死信交換器（DLQ），以保障消息可靠性。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class ArticleRabbitMqConfig {

    /**
     * 文章事件 Exchange 名稱
     */
    public static final String EXCHANGE = "article.events";

    /**
     * 文章已發布 Routing Key
     *
     * <p>
     * 本模組僅作為 producer 使用此 routing key（見 {@code ArticleEventPublisher#publishPublished}），
     * 不在本模組宣告對應 Queue／Consumer——實際消費者是 search／recommend 模組各自宣告、
     * 綁定同一 routing key 的 queue（topic exchange 會將訊息複製給每個綁定的 queue）。
     * 本模組先前曾額外宣告 {@code article.published} queue 卻從未消費，是無人消費的孤兒 queue，
     * 已於 ARCH-05／PERF-14 移除，僅保留此 routing key 常數供 producer 使用。
     * </p>
     */
    public static final String ROUTING_KEY_PUBLISHED = "article.published";

    /**
     * 文章瀏覽計數 Queue 名稱
     */
    public static final String QUEUE_VIEW_COUNT = "article.view.count";

    /**
     * 文章被瀏覽 Routing Key
     */
    public static final String ROUTING_KEY_VIEWED = "article.viewed";

    /**
     * 文章已更新 Routing Key
     */
    public static final String ROUTING_KEY_UPDATED = "article.updated";

    /**
     * 文章已刪除 Routing Key
     */
    public static final String ROUTING_KEY_DELETED = "article.deleted";

    /**
     * 文章被標記標籤 Routing Key
     */
    public static final String ROUTING_KEY_TAGGED = "article.tagged";

    /** 文章內容變更（含 SAVED / PUBLISHED / RESTORED）— 給 version 模組訂閱 */
    public static final String ROUTING_KEY_CONTENT_CHANGED = "article.content.changed";

    /**
     * 建立死信隊列（DLQ）參數
     *
     * @return 包含死信交換器與路由 Key 的 Map
     */
    private Map<String, Object> dlqArgs() {
        return Map.of(
                "x-dead-letter-exchange", "blog.dlq",
                "x-dead-letter-routing-key", "dead-letter");
    }

    /**
     * 建立文章事件 Topic Exchange
     *
     * @return TopicExchange 實例
     */
    @Bean
    public TopicExchange articleEventsExchange() {
        return new TopicExchange(EXCHANGE);
    }

    /**
     * 建立文章瀏覽計數 Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue articleViewCountQueue() {
        return new Queue(QUEUE_VIEW_COUNT, true, false, false, dlqArgs());
    }

    /**
     * 建立文章瀏覽計數 Queue 與 Exchange 的綁定
     *
     * @return Binding 實例
     */
    @Bean
    public Binding articleViewCountBinding() {
        return BindingBuilder
                .bind(articleViewCountQueue())
                .to(articleEventsExchange())
                .with(ROUTING_KEY_VIEWED);
    }

}
