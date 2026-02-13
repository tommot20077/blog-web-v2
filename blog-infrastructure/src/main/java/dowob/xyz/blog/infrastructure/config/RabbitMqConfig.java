package dowob.xyz.blog.infrastructure.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置類
 *
 * <p>
 * 配置消息轉換器和 RabbitTemplate，使用 JSON 格式序列化消息，
 * 提供跨語言相容性和可讀性
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class RabbitMqConfig {

    /**
     * 消息轉換器
     *
     * <p>
     * 使用 Jackson 將 Java 物件轉換為 JSON 格式，
     * </p>
     *
     * @return Jackson2JsonMessageConverter 實例
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitMQ 操作模板
     *
     * <p>
     * 用於發送消息到 RabbitMQ，自動使用 JSON 序列化
     * </p>
     *
     * @param connectionFactory RabbitMQ 連接工廠 (由 Spring Boot 自動配置)
     * @return 配置好的 RabbitTemplate
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        return template;
    }
}
