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
 * 定義文章事件相關的 Exchange、Queue 與 Binding，
 * 包含文章發布事件與瀏覽計數事件的訊息路由設定。
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
     * 文章已發布 Queue 名稱
     */
    public static final String QUEUE_PUBLISHED = "article.published";

    /**
     * 文章已發布 Routing Key
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
     * 建立死信隊列（DLQ）參數
     *
     * @return 包含死信交換器與路由 Key 的 Map
     */
    private Map<String, Object> dlqArgs() {
        return Map.of(
                "x-dead-letter-exchange", "blog.dlq",
                "x-dead-letter-routing-key", "dead-letter"
        );
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
     * 建立文章已發布 Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue articlePublishedQueue() {
        return new Queue(QUEUE_PUBLISHED, true, false, false, dlqArgs());
    }

    /**
     * 建立文章已發布 Queue 與 Exchange 的綁定
     *
     * @return Binding 實例
     */
    @Bean
    public Binding articlePublishedBinding() {
        return BindingBuilder
                .bind(articlePublishedQueue())
                .to(articleEventsExchange())
                .with(ROUTING_KEY_PUBLISHED);
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
