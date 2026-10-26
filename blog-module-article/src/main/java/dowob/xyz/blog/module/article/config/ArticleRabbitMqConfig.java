package dowob.xyz.blog.module.article.config;

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
 * 文章模組 RabbitMQ 設定
 *
 * <p>
 * 定義文章事件相關的 Exchange 與瀏覽計數事件的 Queue、Binding，以及訂閱 series 刪除事件
 * （{@link SeriesEventContract#ROUTING_KEY_DELETED}，M6-b）的 Queue 與 Binding。
 * 文章發布事件（{@link ArticleEventContract#ROUTING_KEY_PUBLISHED}）本模組只負責發布，
 * 實際消費由 search／recommend 模組各自宣告的 Queue 承接，不在本模組宣告。
 * 所有 Queue 均配置死信交換器（DLQ），以保障消息可靠性。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class ArticleRabbitMqConfig {

    /**
     * 文章瀏覽計數 Queue 名稱
     */
    public static final String QUEUE_VIEW_COUNT = "article.view.count";

    /**
     * 文章被瀏覽 Routing Key
     */
    public static final String ROUTING_KEY_VIEWED = "article.viewed";

    /**
     * 建立死信隊列（DLQ）參數
     *
     * @return 包含死信交換器與路由 Key 的 Map
     */
    private Map<String, Object> dlqArgs() {
        return Map.of(
                "x-dead-letter-exchange", "blog.dlq",
                "x-dead-letter-routing-key", "dead-letter");
    }

    /**
     * 建立文章事件 Topic Exchange
     *
     * @return TopicExchange 實例
     */
    @Bean
    public TopicExchange articleEventsExchange() {
        return new TopicExchange(ArticleEventContract.EXCHANGE);
    }

    /**
     * 建立文章瀏覽計數 Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue articleViewCountQueue() {
        return new Queue(QUEUE_VIEW_COUNT, true, false, false, dlqArgs());
    }

    /**
     * 建立文章瀏覽計數 Queue 與 Exchange 的綁定
     *
     * @return Binding 實例
     */
    @Bean
    public Binding articleViewCountBinding() {
        return BindingBuilder
                .bind(articleViewCountQueue())
                .to(articleEventsExchange())
                .with(ROUTING_KEY_VIEWED);
    }

    /**
     * 系列已刪除事件的 Queue 名稱（M6-b：取代原本 FK 的 {@code ON DELETE SET NULL}）
     */
    public static final String QUEUE_SERIES_DELETED = "article.series-deleted";

    /**
     * 建立系列事件 Topic Exchange 的宣告（與 series 模組宣告的 {@code series.events} 參數相同）。
     *
     * <p>exchange 由 producer（series 模組的 {@code SeriesRabbitMqConfig}）宣告；本模組<b>不依賴 series 模組</b>，
     * 故不能以 {@code @Qualifier} 引用其 bean（article 單獨啟動的測試與 article 不含 series 的 context 會找不到），
     * 改為以事件契約常數 {@link SeriesEventContract#EXCHANGE} 自行宣告同名 exchange。RabbitMQ 對參數完全相同的
     * 重複宣告是冪等的（durable、不自動刪除，與 series 端一致）；若日後任一端改參數，宣告會被 broker 拒絕而
     * 在啟動時立即現形，不會靜默分歧。bean 名稱刻意與 series 端的 {@code seriesEventsExchange} 不同，避免同名衝突。</p>
     *
     * @return TopicExchange 實例
     */
    @Bean
    public TopicExchange articleSeriesEventsExchange() {
        return new TopicExchange(SeriesEventContract.EXCHANGE);
    }

    /**
     * 建立系列刪除事件 Queue（持久化，含 DLQ 設定）
     *
     * @return Queue 實例
     */
    @Bean
    public Queue articleSeriesDeletedQueue() {
        return new Queue(QUEUE_SERIES_DELETED, true, false, false, dlqArgs());
    }

    /**
     * 將系列刪除事件 Queue 綁定到 {@code series.events} 的 {@code series.deleted} routing key
     * （exchange 與 routing key 取自 {@link SeriesEventContract}）
     *
     * @param articleSeriesDeletedQueue  本模組的 queue
     * @param articleSeriesEventsExchange 系列事件 exchange 的宣告
     * @return Binding 實例
     */
    @Bean
    public Binding articleSeriesDeletedBinding(
            Queue articleSeriesDeletedQueue,
            @Qualifier("articleSeriesEventsExchange") TopicExchange articleSeriesEventsExchange) {
        return BindingBuilder
                .bind(articleSeriesDeletedQueue)
                .to(articleSeriesEventsExchange)
                .with(SeriesEventContract.ROUTING_KEY_DELETED);
    }

}
