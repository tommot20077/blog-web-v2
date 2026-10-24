package dowob.xyz.blog.infrastructure.mq;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.function.Supplier;

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
     * <p>等同 {@link #execute(String, Supplier, Channel, long, ThrowingRunnable)}
     * 不帶業務上下文。本方法<b>保證不往外拋例外</b>：任何失敗都被轉為 nack（requeue=false）。</p>
     *
     * @param consumerName consumer 識別名稱，作為指標的 {@code consumer} tag 與日誌識別
     * @param channel      AMQP channel
     * @param deliveryTag  訊息的 delivery tag
     * @param body         業務邏輯；拋出任何例外即視為處理失敗
     */
    public void execute(String consumerName, Channel channel, long deliveryTag, ThrowingRunnable body) {
        execute(consumerName, null, channel, deliveryTag, body);
    }

    /**
     * 執行 consumer 業務邏輯，並依結果 ack 或 nack；失敗日誌帶業務上下文。
     *
     * <p>既有 consumer 的錯誤日誌都帶業務識別（articleUuid、eventId、userId……），
     * 供告警與人工補送定位。{@code logContext} 讓呼叫端把這些資訊交給本類統一輸出。
     * 它是 {@link Supplier}，僅在<b>失敗時</b>才求值，成功路徑零成本。</p>
     *
     * <p>本方法<b>保證不往外拋例外</b>，包含：</p>
     * <ul>
     *   <li>{@code logContext} 自身拋例外（或本身為 {@code null}）：退回不帶上下文的日誌</li>
     *   <li>計數失敗：只記 warn，絕不影響 ack/nack（見 {@link #increment}）</li>
     *   <li>body 拋 {@link InterruptedException}：照常 nack，並還原執行緒的 interrupt 旗標</li>
     * </ul>
     *
     * @param consumerName consumer 識別名稱，作為指標的 {@code consumer} tag 與日誌識別
     * @param logContext   失敗時附加於 ERROR 日誌的業務上下文；可為 {@code null}，
     *                     回傳 {@code null} 或空白亦視為無上下文
     * @param channel      AMQP channel
     * @param deliveryTag  訊息的 delivery tag
     * @param body         業務邏輯；拋出任何例外即視為處理失敗
     */
    public void execute(String consumerName, Supplier<String> logContext,
                        Channel channel, long deliveryTag, ThrowingRunnable body) {
        try {
            body.run();
            channel.basicAck(deliveryTag, false);
            increment(PROCESSED_METRIC, consumerName);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String context = resolveContext(logContext);
            log.error("consumer {} 處理訊息失敗，訊息送往 DLQ，deliveryTag={}{}",
                    consumerName, deliveryTag, context, e);
            increment(FAILED_METRIC, consumerName);
            nackWithoutRequeue(consumerName, context, channel, deliveryTag);
        }
    }

    /**
     * 求值業務上下文並格式化為日誌後綴。
     *
     * <p>{@link Supplier} 拋任何例外都被吞掉並退回空字串——取日誌上下文不得讓失敗處理本身失敗。</p>
     *
     * @param logContext 上下文來源；可為 {@code null}
     * @return 形如 {@code "，articleUuid=..."} 的後綴；無上下文時為空字串
     */
    private String resolveContext(Supplier<String> logContext) {
        if (logContext == null) {
            return "";
        }
        try {
            String value = logContext.get();
            return value == null || value.isBlank() ? "" : "，" + value;
        } catch (RuntimeException e) {
            log.warn("取得 consumer 日誌上下文失敗，改以不帶上下文輸出", e);
            return "";
        }
    }

    /**
     * 以 requeue=false 拒絕訊息；nack 失敗只記錄、不外拋。
     *
     * @param consumerName consumer 識別名稱
     * @param context      已格式化的業務上下文後綴；可為空字串
     * @param channel      AMQP channel
     * @param deliveryTag  訊息的 delivery tag
     */
    private void nackWithoutRequeue(String consumerName, String context, Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, false);
            increment(DEAD_LETTERED_METRIC, consumerName);
        } catch (IOException nackEx) {
            log.error("consumer {} NACK 亦失敗，deliveryTag={}{}", consumerName, deliveryTag, context, nackEx);
        }
    }

    /**
     * 將指定指標加一；<b>絕不外拋</b>。
     *
     * <p>計數是旁路觀測，不得影響訊息的 ack/nack：
     * 失敗路徑若因計數例外而外拋，訊息不會被 nack；
     * 成功路徑若在 ack 之後計數失敗落入 catch，會對已 ack 的 tag 再 nack
     * （broker 端 PRECONDITION_FAILED、關閉 channel）。故任何 {@link RuntimeException}
     * （例如 MeterFilter 拋錯、{@code consumerName} 為 {@code null}）都只記 warn。</p>
     *
     * <p>全域 registry 未掛任何子 registry 時直接跳過——這只是省去建立 Counter 的成本，
     * <b>不是正確性所需</b>：即使沒有此 guard，無子 registry 時註冊 Counter 也是安全的 no-op。</p>
     *
     * @param metric       指標名稱
     * @param consumerName consumer 識別名稱
     */
    private void increment(String metric, String consumerName) {
        try {
            if (Metrics.globalRegistry.getRegistries().isEmpty()) {
                return;
            }
            Counter.builder(metric)
                    .tag(CONSUMER_TAG, consumerName)
                    .register(Metrics.globalRegistry)
                    .increment();
        } catch (RuntimeException e) {
            log.warn("consumer {} 指標 {} 計數失敗，已忽略（不影響 ack/nack）", consumerName, metric, e);
        }
    }
}
