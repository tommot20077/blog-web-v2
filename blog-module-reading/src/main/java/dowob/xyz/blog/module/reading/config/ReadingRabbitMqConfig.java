package dowob.xyz.blog.module.reading.config;

import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import dowob.xyz.blog.infrastructure.event.UserEventContract;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static dowob.xyz.blog.infrastructure.config.DeadLetterTopology.dlqArgs;

/**
 * Reading 模組 RabbitMQ 設定：訂閱 {@code article.events} 的 {@code article.deleted}，
 * 於文章刪除後連動清除該文章的按讚、收藏、劃線與閱讀進度；另訂閱 {@code user.events} 的 {@code user.deleted}，
 * 於使用者刪除帳號後清除該使用者的收藏、劃線與閱讀進度（DATA-02）。
 *
 * <p>exchange 本身由 infrastructure 的 {@code RabbitMqConfig} 宣告（bean 名稱 {@code articleEventsExchange}，ARCH-06），
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
     * @param articleEventsExchange      共用的 article.events exchange（由 infrastructure 宣告）
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

    /** 使用者帳號刪除事件的 queue 名稱 */
    public static final String QUEUE_READING_USER_DELETED = "reading.user-deleted";

    /**
     * 宣告使用者事件 exchange（與 user 模組宣告的 {@code user.events} 參數相同，重複宣告冪等）。
     *
     * <p>exchange 由 producer（user 模組）宣告；本模組不依賴 user 模組，故以事件契約常數
     * {@link UserEventContract#EXCHANGE} 自行宣告同名 exchange，bean 名稱與 user 端不同以避免衝突。</p>
     *
     * @return exchange
     */
    @Bean
    public TopicExchange readingUserEventsExchange() {
        return new TopicExchange(UserEventContract.EXCHANGE);
    }

    /**
     * 宣告使用者帳號刪除事件的 queue（durable，失敗訊息轉往 DLQ）。
     *
     * @return queue
     */
    @Bean
    public Queue readingUserDeletedQueue() {
        return new Queue(QUEUE_READING_USER_DELETED, true, false, false, dlqArgs());
    }

    /**
     * 將 queue 綁定到使用者事件 exchange 的 {@code user.deleted} routing key。
     *
     * @param readingUserDeletedQueue   本模組的 queue
     * @param readingUserEventsExchange 使用者事件 exchange 的宣告
     * @return binding
     */
    @Bean
    public Binding bindReadingUserDeleted(
            Queue readingUserDeletedQueue,
            @Qualifier("readingUserEventsExchange") TopicExchange readingUserEventsExchange) {
        return BindingBuilder
                .bind(readingUserDeletedQueue)
                .to(readingUserEventsExchange)
                .with(UserEventContract.ROUTING_KEY_DELETED);
    }
}
