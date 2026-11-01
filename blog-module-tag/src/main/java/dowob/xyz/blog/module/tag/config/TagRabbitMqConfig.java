package dowob.xyz.blog.module.tag.config;

import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
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
 * 宣告標籤模組所需的 Queue，並綁定至文章事件 Exchange：
 * 訂閱文章已標籤事件以重算標籤使用計數，訂閱文章刪除事件以連動刪除 {@code article_tags} 列；
 * 另訂閱文章發布／更新／下架事件，讓「已發布文章數」在文章狀態變化時也被重算（DATA-01）。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class TagRabbitMqConfig {

    /**
     * 文章事件 Exchange 名稱（取自共用事件契約 {@link ArticleEventContract#EXCHANGE}）
     */
    public static final String ARTICLE_EVENTS_EXCHANGE = ArticleEventContract.EXCHANGE;

    /**
     * 標籤模組訂閱的文章已標籤事件 Queue 名稱
     */
    public static final String QUEUE_TAG_ARTICLE_TAGGED = "tag.article.tagged";

    /**
     * 標籤模組訂閱的文章刪除事件 Queue 名稱（文章刪除後連動刪除其 {@code article_tags} 列）
     */
    public static final String QUEUE_TAG_ARTICLE_DELETED = "tag.article-deleted";

    /** 使用計數重算：文章發布事件 Queue 名稱 */
    public static final String QUEUE_TAG_USAGE_ARTICLE_PUBLISHED = "tag.usage.article-published";

    /** 使用計數重算：文章更新事件 Queue 名稱 */
    public static final String QUEUE_TAG_USAGE_ARTICLE_UPDATED = "tag.usage.article-updated";

    /** 使用計數重算：文章下架事件 Queue 名稱 */
    public static final String QUEUE_TAG_USAGE_ARTICLE_ARCHIVED = "tag.usage.article-archived";

    /**
     * 文章已標籤 Routing Key（取自共用事件契約 {@link ArticleEventContract#ROUTING_KEY_TAGGED}）
     */
    public static final String ROUTING_KEY_ARTICLE_TAGGED = ArticleEventContract.ROUTING_KEY_TAGGED;

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
     * 建立文章刪除事件消費用 Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue tagArticleDeletedQueue() {
        return QueueBuilder.durable(QUEUE_TAG_ARTICLE_DELETED)
                .withArgument("x-dead-letter-exchange", "blog.dlq")
                .withArgument("x-dead-letter-routing-key", "dead-letter")
                .build();
    }

    /**
     * 建立文章刪除 Queue 與 Exchange 的綁定（routing key 取自共用事件契約
     * {@link ArticleEventContract#ROUTING_KEY_DELETED}，不 import article 模組）
     *
     * @return Binding 實例
     */
    @Bean
    public Binding bindTagArticleDeleted() {
        return BindingBuilder
                .bind(tagArticleDeletedQueue())
                .to(tagArticleEventsExchange())
                .with(ArticleEventContract.ROUTING_KEY_DELETED);
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

    /**
     * 建立使用計數重算用的文章發布事件 Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue tagUsageArticlePublishedQueue() {
        return durableQueueWithDlq(QUEUE_TAG_USAGE_ARTICLE_PUBLISHED);
    }

    /**
     * 建立使用計數重算用的文章更新事件 Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue tagUsageArticleUpdatedQueue() {
        return durableQueueWithDlq(QUEUE_TAG_USAGE_ARTICLE_UPDATED);
    }

    /**
     * 建立使用計數重算用的文章下架事件 Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue tagUsageArticleArchivedQueue() {
        return durableQueueWithDlq(QUEUE_TAG_USAGE_ARTICLE_ARCHIVED);
    }

    /**
     * 綁定文章發布 routing key（取自共用事件契約 {@link ArticleEventContract#ROUTING_KEY_PUBLISHED}）
     *
     * @return Binding 實例
     */
    @Bean
    public Binding bindTagUsageArticlePublished() {
        return BindingBuilder.bind(tagUsageArticlePublishedQueue())
                .to(tagArticleEventsExchange())
                .with(ArticleEventContract.ROUTING_KEY_PUBLISHED);
    }

    /**
     * 綁定文章更新 routing key（取自共用事件契約 {@link ArticleEventContract#ROUTING_KEY_UPDATED}）
     *
     * @return Binding 實例
     */
    @Bean
    public Binding bindTagUsageArticleUpdated() {
        return BindingBuilder.bind(tagUsageArticleUpdatedQueue())
                .to(tagArticleEventsExchange())
                .with(ArticleEventContract.ROUTING_KEY_UPDATED);
    }

    /**
     * 綁定文章下架 routing key（取自共用事件契約 {@link ArticleEventContract#ROUTING_KEY_ARCHIVED}）
     *
     * @return Binding 實例
     */
    @Bean
    public Binding bindTagUsageArticleArchived() {
        return BindingBuilder.bind(tagUsageArticleArchivedQueue())
                .to(tagArticleEventsExchange())
                .with(ArticleEventContract.ROUTING_KEY_ARCHIVED);
    }

    /**
     * 建立持久化並帶 DLQ 設定的 Queue。
     *
     * @param name Queue 名稱
     * @return Queue 實例
     */
    private static Queue durableQueueWithDlq(String name) {
        return QueueBuilder.durable(name)
                .withArgument("x-dead-letter-exchange", "blog.dlq")
                .withArgument("x-dead-letter-routing-key", "dead-letter")
                .build();
    }
}
