package dowob.xyz.blog.module.series.config;

import dowob.xyz.blog.infrastructure.event.SeriesEventContract;
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
 * Series 模組 RabbitMQ 設定。訂閱 user.events / user.deleted（作者刪帳連動），並宣告自己的 series.events exchange
 * （發送 {@code series.deleted}，目前沒有 consumer）。
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class SeriesRabbitMqConfig {

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

    /** 使用者帳號刪除事件的 queue 名稱 */
    public static final String QUEUE_SERIES_USER_DELETED = "series.user-deleted";

    /**
     * 宣告使用者事件 exchange（與 user 模組宣告的 {@code user.events} 參數相同，重複宣告冪等）。
     *
     * <p>exchange 由 producer（user 模組）宣告；本模組不依賴 user 模組，故以事件契約常數
     * {@link UserEventContract#EXCHANGE} 自行宣告同名 exchange，bean 名稱與 user 端不同以避免衝突。</p>
     *
     * @return TopicExchange 實例
     */
    @Bean
    public TopicExchange seriesUserEventsExchange() {
        return new TopicExchange(UserEventContract.EXCHANGE);
    }

    /**
     * 宣告使用者帳號刪除事件的 queue（durable，失敗訊息轉往 DLQ）。
     *
     * @return Queue 實例
     */
    @Bean
    public Queue seriesUserDeletedQueue() {
        return new Queue(QUEUE_SERIES_USER_DELETED, true, false, false, dlqArgs());
    }

    /**
     * 將 queue 綁定到使用者事件 exchange 的 {@code user.deleted} routing key。
     *
     * @param seriesUserDeletedQueue   本模組的 queue
     * @param seriesUserEventsExchange 使用者事件 exchange 的宣告
     * @return Binding 實例
     */
    @Bean
    public Binding bindSeriesUserDeleted(
            Queue seriesUserDeletedQueue,
            @Qualifier("seriesUserEventsExchange") TopicExchange seriesUserEventsExchange) {
        return BindingBuilder
                .bind(seriesUserDeletedQueue)
                .to(seriesUserEventsExchange)
                .with(UserEventContract.ROUTING_KEY_DELETED);
    }
}
