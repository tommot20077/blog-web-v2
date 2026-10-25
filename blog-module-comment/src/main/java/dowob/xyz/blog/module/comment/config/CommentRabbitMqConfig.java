package dowob.xyz.blog.module.comment.config;

import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Comment 模組 RabbitMQ 設定：訂閱 {@code article.events} 的 {@code article.deleted}，
 * 於文章刪除後連動清除該文章的留言。
 *
 * <p>exchange 本身由 article 模組宣告（bean 名稱 {@code articleEventsExchange}），
 * 這裡只宣告本模組的 queue 與 binding；exchange 名稱與 routing key 取自
 * {@link ArticleEventContract}，不 import article 模組。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class CommentRabbitMqConfig {

    /** 文章刪除事件的 queue 名稱 */
    public static final String QUEUE_COMMENT_ARTICLE_DELETED = "comment.article-deleted";

    /**
     * 宣告文章刪除事件的 queue（durable，失敗訊息轉往 DLQ）。
     *
     * @return queue
     */
    @Bean
    public Queue commentArticleDeletedQueue() {
        return new Queue(QUEUE_COMMENT_ARTICLE_DELETED, true, false, false, dlqArgs());
    }

    /**
     * 將 queue 綁定到文章事件 exchange 的 {@code article.deleted} routing key。
     *
     * @param commentArticleDeletedQueue 本模組的 queue
     * @param articleEventsExchange      article 模組宣告的文章事件 exchange
     * @return binding
     */
    @Bean
    public Binding bindCommentArticleDeleted(
            Queue commentArticleDeletedQueue,
            @Qualifier("articleEventsExchange") TopicExchange articleEventsExchange) {
        return BindingBuilder
                .bind(commentArticleDeletedQueue)
                .to(articleEventsExchange)
                .with(ArticleEventContract.ROUTING_KEY_DELETED);
    }

    /**
     * 死信參數（與其他模組的寫法一致）：失敗訊息交給 {@code blog.dlq} exchange。
     *
     * @return queue 參數
     */
    private Map<String, Object> dlqArgs() {
        return Map.of(
                "x-dead-letter-exchange", "blog.dlq",
                "x-dead-letter-routing-key", "dead-letter"
        );
    }
}
