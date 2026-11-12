package dowob.xyz.blog.module.comment.config;

import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CommentRabbitMqConfig} 單元測試：queue 名稱、DLQ 參數與對 article 事件契約的綁定。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("CommentRabbitMqConfig")
class CommentRabbitMqConfigTest {

    private final CommentRabbitMqConfig config = new CommentRabbitMqConfig();

    @Test
    @DisplayName("queue：名稱為 comment.article-deleted、durable，並帶 DLQ 參數")
    void articleDeletedQueue_isDurableWithDlqArgs() {
        Queue queue = config.commentArticleDeletedQueue();

        assertThat(CommentRabbitMqConfig.QUEUE_COMMENT_ARTICLE_DELETED).isEqualTo("comment.article-deleted");
        assertThat(queue.getName()).isEqualTo("comment.article-deleted");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq")
                .containsEntry("x-dead-letter-routing-key", "dead-letter");
    }

    @Test
    @DisplayName("binding：綁定 article.events exchange 與 article.deleted routing key")
    void bindCommentArticleDeleted_bindsContractExchangeAndRoutingKey() {
        Queue queue = config.commentArticleDeletedQueue();
        TopicExchange exchange = new TopicExchange(ArticleEventContract.EXCHANGE);

        Binding binding = config.bindCommentArticleDeleted(queue, exchange);

        assertThat(binding.getExchange()).isEqualTo(ArticleEventContract.EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(ArticleEventContract.ROUTING_KEY_DELETED);
        assertThat(binding.getDestination()).isEqualTo("comment.article-deleted");
    }
}
