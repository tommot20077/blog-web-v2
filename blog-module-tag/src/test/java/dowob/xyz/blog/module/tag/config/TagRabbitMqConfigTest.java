package dowob.xyz.blog.module.tag.config;

import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TagRabbitMqConfig} 單元測試：文章刪除 queue 的名稱、DLQ 參數與對 article 事件契約的綁定。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("TagRabbitMqConfig 單元測試")
class TagRabbitMqConfigTest {

    private final TagRabbitMqConfig config = new TagRabbitMqConfig();

    @Test
    @DisplayName("queue：名稱為 tag.article-deleted、durable，並帶 DLQ 參數")
    void articleDeletedQueue_isDurableWithDlqArgs() {
        Queue queue = config.tagArticleDeletedQueue();

        assertThat(queue.getName()).isEqualTo("tag.article-deleted");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq")
                .containsEntry("x-dead-letter-routing-key", "dead-letter");
    }

    @Test
    @DisplayName("binding：綁定 article.events exchange 與 article.deleted routing key（取自事件契約常數）")
    void bindTagArticleDeleted_bindsContractExchangeAndRoutingKey() {
        Binding binding = config.bindTagArticleDeleted();

        assertThat(binding.getExchange()).isEqualTo(ArticleEventContract.EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(ArticleEventContract.ROUTING_KEY_DELETED);
        assertThat(binding.getDestination()).isEqualTo("tag.article-deleted");
    }

    @Test
    @DisplayName("既有的 article.tagged queue 與 binding 不受影響")
    void taggedQueueAndBinding_areUnchanged() {
        assertThat(config.tagArticleTaggedQueue().getName()).isEqualTo("tag.article.tagged");
        assertThat(config.tagArticleTaggedBinding().getRoutingKey()).isEqualTo(ArticleEventContract.ROUTING_KEY_TAGGED);
    }
}
