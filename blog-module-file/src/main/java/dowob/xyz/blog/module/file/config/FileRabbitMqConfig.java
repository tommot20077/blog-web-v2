package dowob.xyz.blog.module.file.config;

import dowob.xyz.blog.infrastructure.config.DeadLetterTopology;
import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import dowob.xyz.blog.infrastructure.event.UserEventContract;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 檔案模組 RabbitMQ 配置
 *
 * <p>
 * 宣告圖片上傳相關的 Exchange、Queue 與 Binding；另訂閱 {@code user.events} 的 {@code user.deleted}，
 * 於使用者刪除帳號後清除其未被保留文章引用的檔案（DATA-02）；訂閱 {@code article.events} 的
 * {@code article.content.changed}，依文章最新內文回填「檔案 → 文章」綁定（Q12）；訂閱 {@code article.deleted}，
 * 清理綁定該文章的檔案（DATA-07）。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
@EnableConfigurationProperties(FileProperties.class)
public class FileRabbitMqConfig {

    /**
     * Topic Exchange 名稱
     */
    public static final String EXCHANGE = "file.topic";

    /**
     * 縮圖處理 Queue 名稱
     */
    public static final String THUMBNAIL_QUEUE = "file.thumbnail";

    /**
     * 圖片上傳完成的 Routing Key
     */
    public static final String IMAGE_UPLOADED_KEY = "file.image.uploaded";

    /**
     * 宣告 file.topic Topic Exchange
     *
     * @return TopicExchange 實例
     */
    @Bean
    public TopicExchange fileTopicExchange() {
        return new TopicExchange(EXCHANGE);
    }

    /**
     * 宣告 file.thumbnail Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue thumbnailQueue() {
        return DeadLetterTopology.withDeadLetter(QueueBuilder.durable(THUMBNAIL_QUEUE))
                .build();
    }

    /**
     * 將 thumbnailQueue 綁定至 fileTopicExchange，routing key 為 IMAGE_UPLOADED_KEY
     *
     * @param thumbnailQueue    縮圖 Queue
     * @param fileTopicExchange 檔案 Topic Exchange
     * @return Binding 實例
     */
    @Bean
    public Binding thumbnailBinding(Queue thumbnailQueue, TopicExchange fileTopicExchange) {
        return BindingBuilder.bind(thumbnailQueue).to(fileTopicExchange).with(IMAGE_UPLOADED_KEY);
    }

    /** 使用者帳號刪除事件 Queue 名稱 */
    public static final String QUEUE_USER_DELETED = "file.user-deleted";

    /**
     * 宣告使用者事件 Topic Exchange（與 user 模組宣告的 {@code user.events} 參數相同，重複宣告冪等）。
     *
     * <p>exchange 由 producer（user 模組）宣告；本模組不依賴 user 模組，故以事件契約常數
     * {@link UserEventContract#EXCHANGE} 自行宣告同名 exchange，bean 名稱與 user 端不同以避免衝突。</p>
     *
     * @return TopicExchange 實例
     */
    @Bean
    public TopicExchange fileUserEventsExchange() {
        return new TopicExchange(UserEventContract.EXCHANGE);
    }

    /**
     * 宣告 file.user-deleted Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue fileUserDeletedQueue() {
        return DeadLetterTopology.withDeadLetter(QueueBuilder.durable(QUEUE_USER_DELETED))
                .build();
    }

    /**
     * 將 fileUserDeletedQueue 綁定至 user.events 的 {@code user.deleted} routing key
     *
     * @param fileUserDeletedQueue   使用者帳號刪除 Queue
     * @param fileUserEventsExchange 使用者事件 Topic Exchange 的宣告
     * @return Binding 實例
     */
    @Bean
    public Binding bindFileUserDeleted(Queue fileUserDeletedQueue,
                                       @Qualifier("fileUserEventsExchange") TopicExchange fileUserEventsExchange) {
        return BindingBuilder.bind(fileUserDeletedQueue).to(fileUserEventsExchange)
                .with(UserEventContract.ROUTING_KEY_DELETED);
    }

    /** 文章內容變更事件（檔案綁定回填）Queue 名稱 */
    public static final String QUEUE_ARTICLE_CONTENT_CHANGED = "file.article-binding";

    /**
     * 宣告 file.article-binding Queue（持久化，含 DLQ 設定）：消費文章內容變更事件，回填「檔案 → 文章」綁定（Q12）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue fileArticleBindingQueue() {
        return DeadLetterTopology.withDeadLetter(QueueBuilder.durable(QUEUE_ARTICLE_CONTENT_CHANGED))
                .build();
    }

    /**
     * 將 fileArticleBindingQueue 綁定至 article.events 的 {@code article.content.changed} routing key
     *
     * @param fileArticleBindingQueue  檔案綁定回填 Queue
     * @param articleEventsExchange 共用的 article.events exchange（由 infrastructure 的 RabbitMqConfig 宣告）
     * @return Binding 實例
     */
    @Bean
    public Binding bindFileArticleBinding(Queue fileArticleBindingQueue,
                                          @Qualifier("articleEventsExchange") TopicExchange articleEventsExchange) {
        return BindingBuilder.bind(fileArticleBindingQueue).to(articleEventsExchange)
                .with(ArticleEventContract.ROUTING_KEY_CONTENT_CHANGED);
    }

    /** 文章刪除事件（清理綁定該文章的檔案，DATA-07）Queue 名稱 */
    public static final String QUEUE_ARTICLE_DELETED = "file.article-deleted";

    /**
     * 宣告 file.article-deleted Queue（持久化，含 DLQ 設定）：消費文章刪除事件，清理綁定該文章的檔案（DATA-07）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue fileArticleDeletedQueue() {
        return DeadLetterTopology.withDeadLetter(QueueBuilder.durable(QUEUE_ARTICLE_DELETED))
                .build();
    }

    /**
     * 將 fileArticleDeletedQueue 綁定至 article.events 的 {@code article.deleted} routing key
     *
     * @param fileArticleDeletedQueue   文章刪除 Queue
     * @param articleEventsExchange 共用的 article.events exchange（由 infrastructure 的 RabbitMqConfig 宣告）
     * @return Binding 實例
     */
    @Bean
    public Binding bindFileArticleDeleted(Queue fileArticleDeletedQueue,
                                          @Qualifier("articleEventsExchange") TopicExchange articleEventsExchange) {
        return BindingBuilder.bind(fileArticleDeletedQueue).to(articleEventsExchange)
                .with(ArticleEventContract.ROUTING_KEY_DELETED);
    }
}
