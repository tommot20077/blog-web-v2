package dowob.xyz.blog.module.series.config;

import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import dowob.xyz.blog.infrastructure.event.SeriesEventContract;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Series 模組 RabbitMQ 設定。訂閱 article.events / article.deleted，並宣告自己的 series.events exchange
 * （發送 {@code series.deleted}，目前沒有 consumer）。
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class SeriesRabbitMqConfig {

    public static final String QUEUE_SERIES_ARTICLE_DELETED = "series.article-deleted";

    /**
     * 建立 series 事件 Topic Exchange（series 是 producer，exchange 由 producer 宣告）。
     *
     * <p>目前沒有任何 queue 綁定（article 於 M6 訂閱 {@code series.deleted}）；topic exchange
     * 在沒有綁定時會直接丟棄訊息，宣告與發送都不會出錯。durable、不自動刪除，以免 broker 重啟後消失。</p>
     *
     * @return TopicExchange 實例
     */
    @Bean
    public TopicExchange seriesEventsExchange() {
        return new TopicExchange(SeriesEventContract.EXCHANGE);
    }

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
                .with(ArticleEventContract.ROUTING_KEY_DELETED);
    }

    private Map<String, Object> dlqArgs() {
        return Map.of(
            "x-dead-letter-exchange", "blog.dlq",
            "x-dead-letter-routing-key", "dead-letter"
        );
    }
}
