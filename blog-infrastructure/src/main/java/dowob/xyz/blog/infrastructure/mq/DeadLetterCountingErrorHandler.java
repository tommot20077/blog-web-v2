package dowob.xyz.blog.infrastructure.mq;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;

/**
 * 監聽容器的 error handler：行為與 Spring 預設的 {@link ConditionalRejectingErrorHandler} 完全相同，
 * 並在<b>容器即將以 requeue=false 拒絕訊息</b>時計一次 {@value ConsumerExecutor#DEAD_LETTERED_METRIC}
 * （tag：{@code queue}）。
 *
 * <p><b>為什麼在這裡計數</b>：訊息進 DLQ 有三條路，都會在容器處理例外時經過本 handler，
 * 在這裡計數才不會漏、也不會重複：</p>
 * <ol>
 *   <li>重試耗盡——recoverer 拋出包著 {@link AmqpRejectAndDontRequeueException} 的例外</li>
 *   <li>不可重試的致命例外（訊息轉換失敗等）——父類拋出 {@link AmqpRejectAndDontRequeueException}</li>
 *   <li>沒有 messageId 的訊息失敗一次——{@link MessageIdAwareRetryInterceptor} 拋出同型例外</li>
 * </ol>
 * <p>尚可重試的一般失敗不計。</p>
 *
 * <p><b>與 Spring 預設的唯一差異：不丟棄帶 x-death 的致命訊息</b>。Spring 預設
 * （{@code discardFatalsWithXDeath = true}）會把「帶 x-death header 且遇到致命例外」的訊息靜默 ack 丟棄，
 * 目的是避免死信重投迴圈。但本專案的 DLQ 沒有自動重投，只有維運手動把 DLQ 訊息 shovel／move 回原 queue；
 * 若重投後仍遇到轉換失敗或 {@link ClassCastException}，丟棄會讓訊息永久消失且無任何告警。
 * 故 DLQ 重投後仍致命 → <b>回到 DLQ</b>（並計一次 dead_lettered），不丟棄；DLQ 不會自動重投，所以不會迴圈。
 * 這也讓「有 messageId」與「無 messageId」兩條路徑的行為一致。</p>
 *
 * <p>tag 用 {@code queue} 而不是 {@code consumer}：本 handler 位於容器層，只知道訊息來自哪條 queue，
 * 不知道業務 consumer 名稱；同一指標名稱必須使用一致的 tag 鍵，故一律以 queue 區分。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class DeadLetterCountingErrorHandler extends ConditionalRejectingErrorHandler {

    /** 指標的 queue tag 名稱 */
    private static final String QUEUE_TAG = "queue";

    /** 無法判斷來源 queue 時的 tag 值 */
    private static final String UNKNOWN_QUEUE = "unknown";

    /**
     * 建立 error handler：關閉「帶 x-death 的致命訊息直接丟棄」，使其回到 DLQ。
     */
    public DeadLetterCountingErrorHandler() {
        super();
        setDiscardFatalsWithXDeath(false);
    }

    @Override
    public void handleError(Throwable t) {
        try {
            super.handleError(t);
        } catch (AmqpRejectAndDontRequeueException rejected) {
            countDeadLettered(t);
            throw rejected;
        }
        if (causeChainContains(t)) {
            countDeadLettered(t);
        }
    }

    /**
     * 計一次 dead_lettered（絕不外拋）。
     *
     * @param t 容器收到的例外
     */
    private static void countDeadLettered(Throwable t) {
        MqMetrics.increment(ConsumerExecutor.DEAD_LETTERED_METRIC, QUEUE_TAG, queueOf(t));
    }

    /**
     * 例外鏈中是否含 {@link AmqpRejectAndDontRequeueException}。
     *
     * @param t 例外
     * @return {@code true} 表示容器會以 requeue=false 拒絕
     */
    private static boolean causeChainContains(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof AmqpRejectAndDontRequeueException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 從例外鏈取得失敗訊息的來源 queue。
     *
     * @param t 例外
     * @return queue 名稱；取不到時為 {@value #UNKNOWN_QUEUE}
     */
    private static String queueOf(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof ListenerExecutionFailedException failed) {
                Message message = failed.getFailedMessage();
                if (message != null && message.getMessageProperties().getConsumerQueue() != null) {
                    return message.getMessageProperties().getConsumerQueue();
                }
            }
        }
        return UNKNOWN_QUEUE;
    }
}
