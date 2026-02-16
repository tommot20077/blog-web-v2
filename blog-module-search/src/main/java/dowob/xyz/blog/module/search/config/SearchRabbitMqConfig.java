package dowob.xyz.blog.module.search.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 搜尋模組 RabbitMQ 設定
 *
 * <p>
 * 宣告搜尋索引 Queue，並綁定至文章事件 Exchange，
 * 接收 {@code article.published} 路由鍵的訊息，
 * 觸發 Elasticsearch 索引更新。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class SearchRabbitMqConfig {

    /**
     * 搜尋索引 Queue 名稱
     */
    public static final String QUEUE_SEARCH_INDEX = "queue.search.index";

    /**
     * 文章事件 Exchange 名稱（由 blog-module-article 宣告）
     */
    public static final String ARTICLE_EVENTS_EXCHANGE = "article.events";

    /**
     * 文章發布 Routing Key
     */
    public static final String ROUTING_KEY_PUBLISHED = "article.published";

    /**
     * 宣告搜尋索引 Queue（持久化）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue searchIndexQueue() {
        return new Queue(QUEUE_SEARCH_INDEX, true);
    }

    /**
     * 宣告文章事件 Exchange（與 blog-module-article 共用同一 Exchange）
     *
     * <p>
     * Spring AMQP 會在 Exchange 已存在時跳過建立，
     * 此宣告僅確保 Binding 有依賴的 Exchange Bean。
     * </p>
     *
     * @return TopicExchange 實例
     */
    @Bean
    public TopicExchange searchArticleEventsExchange() {
        return new TopicExchange(ARTICLE_EVENTS_EXCHANGE);
    }

    /**
     * 將搜尋索引 Queue 綁定至文章事件 Exchange
     *
     * @return Binding 實例
     */
    @Bean
    public Binding searchIndexBinding() {
        return BindingBuilder
                .bind(searchIndexQueue())
                .to(searchArticleEventsExchange())
                .with(ROUTING_KEY_PUBLISHED);
    }
}
