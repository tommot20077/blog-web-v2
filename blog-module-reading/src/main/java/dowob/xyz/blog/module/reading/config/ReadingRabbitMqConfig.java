package dowob.xyz.blog.module.reading.config;

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
 * Reading 模組 RabbitMQ 設定：訂閱 {@code article.events} 的 {@code article.deleted}，
 * 於文章刪除後連動清除該文章的按讚、收藏、劃線與閱讀進度。
 *
 * <p>exchange 本身由 article 模組宣告（bean 名稱 {@code articleEventsExchange}），
 * 這裡只宣告本模組的 queue 與 binding；exchange 名稱與 routing key 取自
 * {@link ArticleEventContract}，不 import article 模組。</p>
 *
 * <p>同一個 queue 供本模組所有「文章刪除 → 資料清除」使用（{@code user_article_likes}、
 * {@code user_bookmarks}、{@code user_highlights}、{@code user_reading_progress}），
 * 不需要為每張表各開一個 queue。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class ReadingRabbitMqConfig {

    /** 文章刪除事件的 queue 名稱 */
    public static final String QUEUE_READING_ARTICLE_DELETED = "reading.article-deleted";

    /**
     * 宣告文章刪除事件的 queue（durable，失敗訊息轉往 DLQ）。
     *
     * @return queue
     */
    @Bean
    public Queue readingArticleDeletedQueue() {
        return new Queue(QUEUE_READING_ARTICLE_DELETED, true, false, false, dlqArgs());
    }

    /**
     * 將 queue 綁定到文章事件 exchange 的 {@code article.deleted} routing key。
     *
     * @param readingArticleDeletedQueue 本模組的 queue
     * @param articleEventsExchange      article 模組宣告的文章事件 exchange
     * @return binding
     */
    @Bean
    public Binding bindReadingArticleDeleted(
            Queue readingArticleDeletedQueue,
            @Qualifier("articleEventsExchange") TopicExchange articleEventsExchange) {
        return BindingBuilder
                .bind(readingArticleDeletedQueue)
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
