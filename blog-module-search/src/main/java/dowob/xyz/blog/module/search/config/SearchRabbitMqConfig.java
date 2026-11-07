package dowob.xyz.blog.module.search.config;

import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import dowob.xyz.blog.infrastructure.event.UserEventContract;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static dowob.xyz.blog.infrastructure.config.DeadLetterTopology.dlqArgs;

/**
 * 搜尋模組 RabbitMQ 設定
 *
 * <p>
 * 宣告搜尋索引 Queue，並綁定至文章事件 Exchange，
 * 接收 {@code article.published} / {@code article.updated} / {@code article.deleted} /
 * {@code article.archived} 路由鍵的訊息，觸發 Elasticsearch 索引更新或移除；另訂閱 {@code user.events} 的
 * {@code user.deleted}，於使用者刪除帳號後改寫索引內的作者暱稱並清除搜尋歷史（DATA-02）。
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
     * 文章事件 Exchange 名稱（取自共用事件契約 {@link ArticleEventContract#EXCHANGE}）
     */
    public static final String ARTICLE_EVENTS_EXCHANGE = ArticleEventContract.EXCHANGE;

    /**
     * 文章發布 Routing Key
     */
    public static final String ROUTING_KEY_PUBLISHED = ArticleEventContract.ROUTING_KEY_PUBLISHED;

    /**
     * 文章更新 Routing Key
     */
    public static final String ROUTING_KEY_UPDATED = ArticleEventContract.ROUTING_KEY_UPDATED;

    /**
     * 文章刪除 Routing Key
     */
    public static final String ROUTING_KEY_DELETED = ArticleEventContract.ROUTING_KEY_DELETED;

    /**
     * 搜尋索引更新 Queue 名稱
     */
    public static final String QUEUE_SEARCH_INDEX_UPDATE = "queue.search.index.update";

    /**
     * 搜尋索引刪除 Queue 名稱
     */
    public static final String QUEUE_SEARCH_INDEX_DELETE = "queue.search.index.delete";

    /**
     * 文章下架 Routing Key
     *
     * <p>
     * 與 {@link #ROUTING_KEY_DELETED} 分開訂閱是刻意的：{@code article.deleted}
     * 另有 comment、reading、tag 等模組綁定並清除各自的衍生資料，
     * 下架（資料列仍在）若共用同一 routing key 會讓它們誤清。對搜尋模組而言兩者處置相同
     * （移除 document），故指向同一段處理邏輯但各自一條 queue。
     * </p>
     */
    public static final String ROUTING_KEY_ARCHIVED = ArticleEventContract.ROUTING_KEY_ARCHIVED;

    /**
     * 搜尋索引下架移除 Queue 名稱
     */
    public static final String QUEUE_SEARCH_INDEX_ARCHIVE = "queue.search.index.archive";

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
     * 宣告搜尋索引下架移除 Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue searchIndexArchiveQueue() {
        return new Queue(QUEUE_SEARCH_INDEX_ARCHIVE, true, false, false, dlqArgs());
    }

    /**
     * 將搜尋索引 Queue 綁定至文章事件 Exchange（Published）
     *
     * @param articleEventsExchange 共用的 article.events exchange（由 infrastructure 的 RabbitMqConfig 宣告）
     * @return Binding 實例
     */
    @Bean
    public Binding searchIndexBinding(@Qualifier("articleEventsExchange") TopicExchange articleEventsExchange) {
        return BindingBuilder
                .bind(searchIndexQueue())
                .to(articleEventsExchange)
                .with(ROUTING_KEY_PUBLISHED);
    }

    /**
     * 將搜尋索引更新 Queue 綁定至文章事件 Exchange（Updated）
     *
     * @param articleEventsExchange 共用的 article.events exchange（由 infrastructure 的 RabbitMqConfig 宣告）
     * @return Binding 實例
     */
    @Bean
    public Binding searchIndexUpdateBinding(@Qualifier("articleEventsExchange") TopicExchange articleEventsExchange) {
        return BindingBuilder
                .bind(searchIndexUpdateQueue())
                .to(articleEventsExchange)
                .with(ROUTING_KEY_UPDATED);
    }

    /**
     * 將搜尋索引刪除 Queue 綁定至文章事件 Exchange（Deleted）
     *
     * @param articleEventsExchange 共用的 article.events exchange（由 infrastructure 的 RabbitMqConfig 宣告）
     * @return Binding 實例
     */
    @Bean
    public Binding searchIndexDeleteBinding(@Qualifier("articleEventsExchange") TopicExchange articleEventsExchange) {
        return BindingBuilder
                .bind(searchIndexDeleteQueue())
                .to(articleEventsExchange)
                .with(ROUTING_KEY_DELETED);
    }

    /**
     * 將搜尋索引下架移除 Queue 綁定至文章事件 Exchange（Archived）
     *
     * @param articleEventsExchange 共用的 article.events exchange（由 infrastructure 的 RabbitMqConfig 宣告）
     * @return Binding 實例
     */
    @Bean
    public Binding searchIndexArchiveBinding(@Qualifier("articleEventsExchange") TopicExchange articleEventsExchange) {
        return BindingBuilder
                .bind(searchIndexArchiveQueue())
                .to(articleEventsExchange)
                .with(ROUTING_KEY_ARCHIVED);
    }

    /** 使用者帳號刪除事件 Queue 名稱 */
    public static final String QUEUE_SEARCH_USER_DELETED = "search.user-deleted";

    /**
     * 宣告使用者事件 Exchange（與 user 模組宣告的 {@code user.events} 參數相同，重複宣告冪等）。
     *
     * <p>exchange 由 producer（user 模組）宣告；本模組不依賴 user 模組，故以事件契約常數
     * {@link UserEventContract#EXCHANGE} 自行宣告同名 exchange，bean 名稱與 user 端不同以避免衝突。</p>
     *
     * @return TopicExchange 實例
     */
    @Bean
    public TopicExchange searchUserEventsExchange() {
        return new TopicExchange(UserEventContract.EXCHANGE);
    }

    /**
     * 宣告使用者帳號刪除 Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue searchUserDeletedQueue() {
        return new Queue(QUEUE_SEARCH_USER_DELETED, true, false, false, dlqArgs());
    }

    /**
     * 將使用者帳號刪除 Queue 綁定至使用者事件 Exchange（{@code user.deleted}）
     *
     * @return Binding 實例
     */
    @Bean
    public Binding searchUserDeletedBinding() {
        return BindingBuilder
                .bind(searchUserDeletedQueue())
                .to(searchUserEventsExchange())
                .with(UserEventContract.ROUTING_KEY_DELETED);
    }
}
