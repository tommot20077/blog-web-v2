package dowob.xyz.blog.module.article.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文章模組 RabbitMQ 設定
 *
 * <p>
 * 定義文章事件相關的 Exchange、Queue 與 Binding，
 * 包含文章發布事件的訊息路由設定。
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
     * 建立文章事件 Topic Exchange
     *
     * @return TopicExchange 實例
     */
    @Bean
    public TopicExchange articleEventsExchange() {
        return new TopicExchange(EXCHANGE);
    }

    /**
     * 建立文章已發布 Queue（持久化）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue articlePublishedQueue() {
        return new Queue(QUEUE_PUBLISHED, true);
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
}
