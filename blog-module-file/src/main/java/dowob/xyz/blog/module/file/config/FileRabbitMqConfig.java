package dowob.xyz.blog.module.file.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 檔案模組 RabbitMQ 配置
 *
 * <p>
 * 宣告圖片上傳相關的 Exchange、Queue 與 Binding。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
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
     * 宣告 file.thumbnail Queue
     *
     * @return Queue 實例
     */
    @Bean
    public Queue thumbnailQueue() {
        return new Queue(THUMBNAIL_QUEUE);
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
}
