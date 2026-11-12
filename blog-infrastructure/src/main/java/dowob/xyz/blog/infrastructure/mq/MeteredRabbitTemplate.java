package dowob.xyz.blog.infrastructure.mq;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;

/**
 * 會對「發送失敗」計數的 {@link RabbitTemplate}（ARCH-16，決策 C7）。
 *
 * <p>本專案各模組的事件 publisher（article／series／user／file、AuthService）都採 best-effort：
 * 發送失敗只 log warn、不影響主流程。這是正確的可用性取捨，但先前沒有配套——一致性漂移只存在 log 裡。
 * 因為全站共用<b>同一個</b> {@code RabbitTemplate} bean，在這裡的唯一 chokepoint 計數，
 * 就不需要每個 publisher 各自加一份。</p>
 *
 * <p>覆寫所有 {@code convertAndSend}／{@code send} 最終都會經過的
 * {@link #send(String, String, Message, CorrelationData)}：失敗時遞增
 * {@value #FAILED_METRIC}（tag：{@code exchange}、{@code routing_key}），之後<b>原樣外拋</b>，
 * 由呼叫端既有的 catch 決定如何處理。限制：訊息轉換（序列化）階段的失敗發生在 {@code send} 之前，不計入；
 * Broker 的非同步 nack／return 只記 log（見 {@code RabbitMqConfig}），亦不計入。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class MeteredRabbitTemplate extends RabbitTemplate {

    /** 發送失敗計數的指標名稱（命名對齊 {@code mq.consumer.*}） */
    public static final String FAILED_METRIC = "mq.publisher.failed";

    /** 預設 exchange（空字串）在 tag 中的代稱：Micrometer 不接受空 tag 值 */
    static final String DEFAULT_EXCHANGE_TAG = "(default)";

    /**
     * 建立會計數發送失敗的 template。
     *
     * @param connectionFactory 連接工廠
     */
    public MeteredRabbitTemplate(ConnectionFactory connectionFactory) {
        super(connectionFactory);
    }

    /**
     * 發送訊息；失敗時先計數再外拋。
     *
     * @param exchange        exchange 名稱
     * @param routingKey      routing key
     * @param message         訊息
     * @param correlationData publisher confirm 關聯資料
     */
    @Override
    public void send(String exchange, String routingKey, Message message, CorrelationData correlationData) {
        try {
            super.send(exchange, routingKey, message, correlationData);
        } catch (RuntimeException e) {
            MqMetrics.increment(FAILED_METRIC,
                    "exchange", exchange == null || exchange.isEmpty() ? DEFAULT_EXCHANGE_TAG : exchange,
                    "routing_key", routingKey == null || routingKey.isEmpty() ? DEFAULT_EXCHANGE_TAG : routingKey);
            throw e;
        }
    }
}
