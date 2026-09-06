package dowob.xyz.blog.module.tag.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 標籤模組 RabbitMQ 設定
 *
 * <p>
 * 宣告標籤模組所需的 Queue，並綁定至文章事件 Exchange，
 * 訂閱文章發布事件以更新標籤使用計數。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class TagRabbitMqConfig {

    /**
     * 文章事件 Exchange 名稱（與 blog-module-article 共用）
     */
    public static final String ARTICLE_EVENTS_EXCHANGE = "article.events";

    /**
     * 標籤模組訂閱的文章已標籤事件 Queue 名稱
     */
    public static final String QUEUE_TAG_ARTICLE_TAGGED = "tag.article.tagged";

    /**
     * 文章已標籤 Routing Key
     */
    public static final String ROUTING_KEY_ARTICLE_TAGGED = "article.tagged";

    /**
     * 建立文章事件 Topic Exchange（宣告式，若已存在則共用）
     *
     * @return TopicExchange 實例
     */
    @Bean
    public TopicExchange tagArticleEventsExchange() {
        return new TopicExchange(ARTICLE_EVENTS_EXCHANGE);
    }

    /**
     * 建立標籤模組消費用 Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue tagArticleTaggedQueue() {
        return QueueBuilder.durable(QUEUE_TAG_ARTICLE_TAGGED)
                .withArgument("x-dead-letter-exchange", "blog.dlq")
                .withArgument("x-dead-letter-routing-key", "dead-letter")
                .build();
    }

    /**
     * 建立 Queue 與 Exchange 的綁定
     *
     * @return Binding 實例
     */
    @Bean
    public Binding tagArticleTaggedBinding() {
        return BindingBuilder
                .bind(tagArticleTaggedQueue())
                .to(tagArticleEventsExchange())
                .with(ROUTING_KEY_ARTICLE_TAGGED);
    }
}
