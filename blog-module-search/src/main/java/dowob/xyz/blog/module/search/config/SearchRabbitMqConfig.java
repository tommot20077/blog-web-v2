package dowob.xyz.blog.module.search.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

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
     * 文章更新 Routing Key
     */
    public static final String ROUTING_KEY_UPDATED = "article.updated";

    /**
     * 文章刪除 Routing Key
     */
    public static final String ROUTING_KEY_DELETED = "article.deleted";

    /**
     * 搜尋索引更新 Queue 名稱
     */
    public static final String QUEUE_SEARCH_INDEX_UPDATE = "queue.search.index.update";

    /**
     * 搜尋索引刪除 Queue 名稱
     */
    public static final String QUEUE_SEARCH_INDEX_DELETE = "queue.search.index.delete";

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
     * 宣告搜尋索引 Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue searchIndexQueue() {
        return new Queue(QUEUE_SEARCH_INDEX, true, false, false, dlqArgs());
    }

    /**
     * 宣告搜尋索引更新 Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue searchIndexUpdateQueue() {
        return new Queue(QUEUE_SEARCH_INDEX_UPDATE, true, false, false, dlqArgs());
    }

    /**
     * 宣告搜尋索引刪除 Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue searchIndexDeleteQueue() {
        return new Queue(QUEUE_SEARCH_INDEX_DELETE, true, false, false, dlqArgs());
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
     * 將搜尋索引 Queue 綁定至文章事件 Exchange（Published）
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

    /**
     * 將搜尋索引更新 Queue 綁定至文章事件 Exchange（Updated）
     *
     * @return Binding 實例
     */
    @Bean
    public Binding searchIndexUpdateBinding() {
        return BindingBuilder
                .bind(searchIndexUpdateQueue())
                .to(searchArticleEventsExchange())
                .with(ROUTING_KEY_UPDATED);
    }

    /**
     * 將搜尋索引刪除 Queue 綁定至文章事件 Exchange（Deleted）
     *
     * @return Binding 實例
     */
    @Bean
    public Binding searchIndexDeleteBinding() {
        return BindingBuilder
                .bind(searchIndexDeleteQueue())
                .to(searchArticleEventsExchange())
                .with(ROUTING_KEY_DELETED);
    }
}
