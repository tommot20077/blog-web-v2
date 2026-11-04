package dowob.xyz.blog.module.file.config;

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
 * 於使用者刪除帳號後清除其未被保留文章引用的檔案（DATA-02）。
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
        return QueueBuilder.durable(THUMBNAIL_QUEUE)
                .withArgument("x-dead-letter-exchange", "blog.dlq")
                .withArgument("x-dead-letter-routing-key", "dead-letter")
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
        return QueueBuilder.durable(QUEUE_USER_DELETED)
                .withArgument("x-dead-letter-exchange", "blog.dlq")
                .withArgument("x-dead-letter-routing-key", "dead-letter")
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
}
