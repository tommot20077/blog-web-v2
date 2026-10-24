package dowob.xyz.blog.infrastructure.mq;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * RabbitMQ consumer 的共用 ack/nack 包裝。
 *
 * <p><b>存在理由</b>：各 {@code @RabbitListener} 方法原本各自手寫
 * {@code try { ... basicAck } catch { basicNack(requeue=false) }} 樣板，
 * 且沒有任何失敗計數——訊息進了 DLQ 沒有人知道。本類把樣板收斂為單一實作，
 * 並在同一處打點。</p>
 *
 * <p><b>行為與既有手寫樣板完全一致</b>：</p>
 * <ul>
 *   <li>body 成功 → {@code basicAck(deliveryTag, false)}</li>
 *   <li>body 拋任何 {@link Exception}（含 ack 本身失敗）→ log.error，
 *       {@code basicNack(deliveryTag, false, false)}（不重新入列，由 DLX 送往 DLQ）</li>
 *   <li>nack 本身拋 {@link IOException} → log.error，<b>不往外拋</b></li>
 * </ul>
 *
 * <p>本類<b>不</b>開啟重試，也<b>不</b>把例外拋給 Spring AMQP 的 retry interceptor；
 * 重試機制於收尾階段才統一開啟。</p>
 *
 * <p><b>指標</b>（皆帶 {@code consumer} tag；全域 registry 未掛任何子 registry 時整段跳過，
 * 與 {@code BatchedQuery} 一致）：</p>
 * <ul>
 *   <li>{@value #PROCESSED_METRIC}：body 成功且 ack 完成</li>
 *   <li>{@value #FAILED_METRIC}：處理失敗（body 或 ack 拋例外）</li>
 *   <li>{@value #DEAD_LETTERED_METRIC}：{@code requeue=false} 的 nack 已成功送出，
 *       亦即訊息交由 DLX 處理。nack 本身失敗時訊息並未確實死信
 *       （channel 關閉後 broker 會重新投遞），故不計入</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
public class ConsumerExecutor {

    /** 成功處理（已 ack）的訊息數指標名稱 */
    public static final String PROCESSED_METRIC = "mq.consumer.processed";

    /** 處理失敗的訊息數指標名稱 */
    public static final String FAILED_METRIC = "mq.consumer.failed";

    /** 以 requeue=false nack、交由 DLX 的訊息數指標名稱 */
    public static final String DEAD_LETTERED_METRIC = "mq.consumer.dead_lettered";

    /** 指標的 consumer tag 名稱 */
    private static final String CONSUMER_TAG = "consumer";

    /**
     * 執行 consumer 業務邏輯，並依結果 ack 或 nack。
     *
     * <p>本方法<b>保證不往外拋例外</b>：任何失敗都被轉為 nack（requeue=false）。</p>
     *
     * @param consumerName consumer 識別名稱，作為指標的 {@code consumer} tag 與日誌識別
     * @param channel      AMQP channel
     * @param deliveryTag  訊息的 delivery tag
     * @param body         業務邏輯；拋出任何例外即視為處理失敗
     */
    public void execute(String consumerName, Channel channel, long deliveryTag, ThrowingRunnable body) {
        try {
            body.run();
            channel.basicAck(deliveryTag, false);
            increment(PROCESSED_METRIC, consumerName);
        } catch (Exception e) {
            log.error("consumer {} 處理訊息失敗，訊息送往 DLQ，deliveryTag={}", consumerName, deliveryTag, e);
            increment(FAILED_METRIC, consumerName);
            nackWithoutRequeue(consumerName, channel, deliveryTag);
        }
    }

    /**
     * 以 requeue=false 拒絕訊息；nack 失敗只記錄、不外拋。
     *
     * @param consumerName consumer 識別名稱
     * @param channel      AMQP channel
     * @param deliveryTag  訊息的 delivery tag
     */
    private void nackWithoutRequeue(String consumerName, Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, false);
            increment(DEAD_LETTERED_METRIC, consumerName);
        } catch (IOException nackEx) {
            log.error("consumer {} NACK 亦失敗，deliveryTag={}", consumerName, deliveryTag, nackEx);
        }
    }

    /**
     * 將指定指標加一。全域 registry 未掛任何子 registry 時直接跳過。
     *
     * @param metric       指標名稱
     * @param consumerName consumer 識別名稱
     */
    private void increment(String metric, String consumerName) {
        if (Metrics.globalRegistry.getRegistries().isEmpty()) {
            return;
        }
        Counter.builder(metric)
                .tag(CONSUMER_TAG, consumerName)
                .register(Metrics.globalRegistry)
                .increment();
    }
}
