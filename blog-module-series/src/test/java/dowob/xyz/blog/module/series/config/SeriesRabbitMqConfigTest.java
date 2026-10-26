package dowob.xyz.blog.module.series.config;

import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import dowob.xyz.blog.infrastructure.event.SeriesEventContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SeriesRabbitMqConfig} 單元測試：訂閱 article 刪除事件的 queue 與綁定，以及 series 自己的事件 exchange。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("SeriesRabbitMqConfig")
class SeriesRabbitMqConfigTest {

    private final SeriesRabbitMqConfig config = new SeriesRabbitMqConfig();

    @Test
    @DisplayName("series.events exchange：Topic、durable、不自動刪除；沒有任何 queue 綁定也能宣告")
    void seriesEventsExchange_isDurableTopicExchange() {
        TopicExchange exchange = config.seriesEventsExchange();

        assertThat(exchange.getName()).isEqualTo(SeriesEventContract.EXCHANGE).isEqualTo("series.events");
        assertThat(exchange.getType()).isEqualTo("topic");
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
    }

    @Test
    @DisplayName("queue：series.article-deleted、durable，並帶 DLQ 參數")
    void articleDeletedQueue_isDurableWithDlqArgs() {
        Queue queue = config.seriesArticleDeletedQueue();

        assertThat(queue.getName()).isEqualTo("series.article-deleted");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq")
                .containsEntry("x-dead-letter-routing-key", "dead-letter");
    }

    @Test
    @DisplayName("binding：綁定 article.events exchange 與 article.deleted routing key")
    void bindSeriesArticleDeleted_bindsArticleContract() {
        Binding binding = config.bindSeriesArticleDeleted(
                config.seriesArticleDeletedQueue(), new TopicExchange(ArticleEventContract.EXCHANGE));

        assertThat(binding.getExchange()).isEqualTo(ArticleEventContract.EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(ArticleEventContract.ROUTING_KEY_DELETED);
        assertThat(binding.getDestination()).isEqualTo("series.article-deleted");
    }
}
