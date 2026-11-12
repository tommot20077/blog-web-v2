package dowob.xyz.blog.infrastructure.config;

import org.springframework.amqp.core.QueueBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 死信（DLQ）拓撲的全站單一來源（ARCH-19）。
 *
 * <p>重試耗盡的訊息以 {@code requeue=false} 拒絕後，由 queue 的 {@code x-dead-letter-exchange}／
 * {@code x-dead-letter-routing-key} 參數路由到 {@value #DLQ_EXCHANGE} → {@value #DLQ_QUEUE}。
 * 這兩個參數過去在 article、comment、reading、recommend、search、series、user、version 八個模組各複製一份
 * {@code dlqArgs()}，file、tag 也各自寫死字串；現在統一由這裡提供，常數與 {@link RabbitMqConfig} 宣告 DLQ 的值同源。</p>
 *
 * <p><b>參數不可隨意變動</b>：RabbitMQ 對既有 queue 以不同參數重新宣告會回 406 PRECONDITION_FAILED，
 * 使應用程式啟動失敗。全站 queue 參數由 blog-start 的 {@code MqTopologySnapshotTest} 逐一快照守衛。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class DeadLetterTopology {

    /** DLQ Exchange 名稱（DirectExchange，由 {@link RabbitMqConfig} 宣告） */
    public static final String DLQ_EXCHANGE = "blog.dlq";

    /** DLQ Queue 名稱（由 {@link RabbitMqConfig} 宣告） */
    public static final String DLQ_QUEUE = "queue.dead-letter";

    /** DLQ Routing Key */
    public static final String DLQ_ROUTING_KEY = "dead-letter";

    /** queue 參數名稱：死信 exchange */
    private static final String ARG_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";

    /** queue 參數名稱：死信 routing key */
    private static final String ARG_DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";

    private DeadLetterTopology() {
    }

    /**
     * 帶 DLQ 的 queue arguments，供 {@code new Queue(name, durable, exclusive, autoDelete, arguments)} 使用。
     *
     * <p>每次呼叫回傳<b>新的可變</b> Map：呼叫端可在其上追加額外參數而不影響其他 queue
     * （目前全站無 queue 追加額外參數，version 的 TTL 已於 ARCH-18／C5 移除）。</p>
     *
     * @return 恰含 {@code x-dead-letter-exchange} 與 {@code x-dead-letter-routing-key} 兩項的新 Map
     */
    public static Map<String, Object> dlqArgs() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(ARG_DEAD_LETTER_EXCHANGE, DLQ_EXCHANGE);
        args.put(ARG_DEAD_LETTER_ROUTING_KEY, DLQ_ROUTING_KEY);
        return args;
    }

    /**
     * 在 {@link QueueBuilder} 上套用 DLQ 參數（與 {@link #dlqArgs()} 相同），供使用 builder 的模組（file、tag）使用。
     *
     * @param builder queue builder
     * @return 同一個 builder（可繼續串接 {@code build()}）
     */
    public static QueueBuilder withDeadLetter(QueueBuilder builder) {
        return builder.withArguments(dlqArgs());
    }
}
