package dowob.xyz.blog.infrastructure.mq;

import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateAcknowledgeAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;

/**
 * 有狀態重試攔截器的前置守門員：沒有 AMQP messageId 的訊息<b>不進入</b> stateful retry。
 *
 * <p><b>為什麼需要</b>：stateful retry 以 messageId 追蹤「這則訊息已嘗試幾次」。沒有 messageId 的訊息
 * （例如部署本變更前已留在 queue 裡的舊訊息）無法計次，若硬交給攔截器，行為取決於 spring-retry 對 null key 的處理，
 * 不應依賴。本類把行為定死：</p>
 * <ul>
 *   <li>有 messageId → 交給 delegate（stateful retry interceptor）</li>
 *   <li>沒有 messageId → 只執行<b>一次</b>；成功則正常返回；失敗則拋
 *       {@link AmqpRejectAndDontRequeueException}（包在 {@link ListenerExecutionFailedException} 內，帶失敗訊息），
 *       容器以 requeue=false 拒絕，經 DLX 進 DLQ，由人工處理——寧可進 DLQ，也不無限重投</li>
 * </ul>
 *
 * <p>批次監聽（資料為 {@code List}）等非單一 {@link Message} 的呼叫不在此判斷，直接交給 delegate
 * （本專案沒有批次監聽）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
public class MessageIdAwareRetryInterceptor implements MethodInterceptor {

    /** 容器 advice 呼叫的參數索引：{@code invokeListener(Channel, Object data)} 的 data */
    private static final int DATA_ARG_INDEX = 1;

    /** 實際的有狀態重試攔截器 */
    private final MethodInterceptor delegate;

    /**
     * 建立守門員。
     *
     * @param delegate 有 messageId 的訊息所交給的有狀態重試攔截器
     */
    public MessageIdAwareRetryInterceptor(MethodInterceptor delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Object[] args = invocation.getArguments();
        if (args.length > DATA_ARG_INDEX && args[DATA_ARG_INDEX] instanceof Message message && !hasMessageId(message)) {
            return invokeOnceWithoutRetry(invocation, message);
        }
        return delegate.invoke(invocation);
    }

    /**
     * 只執行一次；失敗即拒絕且不重新入列。
     *
     * @param invocation 容器對 listener 的呼叫
     * @param message    沒有 messageId 的訊息
     * @return listener 的回傳值
     * @throws Throwable 成功以外的結果
     */
    private Object invokeOnceWithoutRetry(MethodInvocation invocation, Message message) throws Throwable {
        log.warn("訊息沒有 messageId，無法有狀態重試：只嘗試一次，失敗即送往 DLQ。queue={}",
                message.getMessageProperties().getConsumerQueue());
        try {
            return invocation.proceed();
        } catch (AmqpRejectAndDontRequeueException | ImmediateAcknowledgeAmqpException passThrough) {
            throw passThrough;
        } catch (Exception e) {
            throw new ListenerExecutionFailedException("訊息沒有 messageId，不重試",
                    new AmqpRejectAndDontRequeueException("沒有 messageId，無法重試，失敗一次即送往 DLQ", e), message);
        }
    }

    /**
     * 訊息是否有可用的 messageId。
     *
     * @param message 訊息
     * @return {@code true} 表示 messageId 非 null 且非空白
     */
    private static boolean hasMessageId(Message message) {
        String id = message.getMessageProperties().getMessageId();
        return id != null && !id.isBlank();
    }
}
