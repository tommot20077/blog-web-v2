package dowob.xyz.blog.module.series.config;

import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Series 模組 RabbitMQ 設定。訂閱 article.events / article.deleted。
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class SeriesRabbitMqConfig {

    public static final String QUEUE_SERIES_ARTICLE_DELETED = "series.article-deleted";

    @Bean
    public Queue seriesArticleDeletedQueue() {
        return new Queue(QUEUE_SERIES_ARTICLE_DELETED, true, false, false, dlqArgs());
    }

    @Bean
    public Binding bindSeriesArticleDeleted(
            Queue seriesArticleDeletedQueue,
            @Qualifier("articleEventsExchange") TopicExchange articleEventsExchange) {
        return BindingBuilder
                .bind(seriesArticleDeletedQueue)
                .to(articleEventsExchange)
                .with(ArticleRabbitMqConfig.ROUTING_KEY_DELETED);
    }

    private Map<String, Object> dlqArgs() {
        return Map.of(
            "x-dead-letter-exchange", "blog.dlq",
            "x-dead-letter-routing-key", "dead-letter"
        );
    }
}
