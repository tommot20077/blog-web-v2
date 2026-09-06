package dowob.xyz.blog.module.recommend.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 推薦模組 RabbitMQ 設定
 *
 * <p>
 * 訂閱文章事件以清除推薦快取：
 * 當文章發布時，清除對應的相關文章推薦快取，
 * 確保推薦結果反映最新的文章索引狀態。
 * 推薦模組的 Consumer 採用手動 ACK 模式，以防止訊息遺失。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class RecommendRabbitMqConfig {

    /**
     * 文章事件 Exchange 名稱（與 article 模組共用）
     */
    public static final String ARTICLE_EVENTS_EXCHANGE = "article.events";

    /**
     * 推薦模組監聽文章發布事件的 Queue 名稱
     */
    public static final String QUEUE_RECOMMEND_ARTICLE_PUBLISHED = "recommend.article.published";

    /**
     * 文章已發布 Routing Key
     */
    public static final String ROUTING_KEY_ARTICLE_PUBLISHED = "article.published";

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
     * 參照文章事件 TopicExchange（已由 article 模組定義，此處僅宣告參照）
     *
     * @return TopicExchange 實例
     */
    @Bean
    public TopicExchange recommendArticleEventsExchange() {
        return new TopicExchange(ARTICLE_EVENTS_EXCHANGE);
    }

    /**
     * 建立推薦模組專屬的文章發布事件 Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue recommendArticlePublishedQueue() {
        return new Queue(QUEUE_RECOMMEND_ARTICLE_PUBLISHED, true, false, false, dlqArgs());
    }

    /**
     * 將推薦模組的 Queue 綁定至文章事件 Exchange
     *
     * @return Binding 實例
     */
    @Bean
    public Binding recommendArticlePublishedBinding() {
        return BindingBuilder
                .bind(recommendArticlePublishedQueue())
                .to(recommendArticleEventsExchange())
                .with(ROUTING_KEY_ARTICLE_PUBLISHED);
    }
}
