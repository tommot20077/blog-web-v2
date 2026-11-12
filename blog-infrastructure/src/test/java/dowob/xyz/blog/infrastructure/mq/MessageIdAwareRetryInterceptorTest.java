package dowob.xyz.blog.infrastructure.mq;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MessageIdAwareRetryInterceptor} 的單元測試：沒有 messageId 的訊息（部署前殘留）不可進入 stateful retry。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("MessageIdAwareRetryInterceptor：無 messageId 的處理")
class MessageIdAwareRetryInterceptorTest {

    private final MethodInterceptor delegate = mock(MethodInterceptor.class);

    private final MethodInvocation invocation = mock(MethodInvocation.class);

    private final MessageIdAwareRetryInterceptor interceptor = new MessageIdAwareRetryInterceptor(delegate);

    private static Message message(String messageId) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId);
        properties.setConsumerQueue("q");
        return new Message(new byte[0], properties);
    }

    @Test
    @DisplayName("有 messageId → 交給 stateful retry interceptor 處理")
    void withMessageId_delegatesToRetryInterceptor() throws Throwable {
        when(invocation.getArguments()).thenReturn(new Object[]{null, message("id-1")});
        when(delegate.invoke(invocation)).thenReturn("delegated");

        Object result = interceptor.invoke(invocation);

        assertThat(result).isEqualTo("delegated");
        verify(invocation, never()).proceed();
    }

    @Test
    @DisplayName("沒有 messageId 且處理成功 → 直接執行一次並回傳結果，不經 retry interceptor")
    void withoutMessageId_success_proceedsOnceWithoutRetryInterceptor() throws Throwable {
        when(invocation.getArguments()).thenReturn(new Object[]{null, message(null)});
        when(invocation.proceed()).thenReturn("done");

        Object result = interceptor.invoke(invocation);

        assertThat(result).isEqualTo("done");
        verify(delegate, never()).invoke(invocation);
    }

    @Test
    @DisplayName("沒有 messageId 且處理失敗 → 不重試，拋 ListenerExecutionFailedException（含 AmqpRejectAndDontRequeueException，根因為原始例外）使訊息直接進 DLQ")
    void withoutMessageId_failure_isRejectedWithoutRetry() throws Throwable {
        IllegalStateException boom = new IllegalStateException("boom");
        when(invocation.getArguments()).thenReturn(new Object[]{null, message(null)});
        when(invocation.proceed()).thenThrow(boom);

        assertThatThrownBy(() -> interceptor.invoke(invocation))
                .isInstanceOf(ListenerExecutionFailedException.class)
                .hasCauseInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasRootCause(boom);

        verify(delegate, never()).invoke(invocation);
    }

    @Test
    @DisplayName("空白 messageId 視同沒有")
    void blankMessageId_isTreatedAsMissing() throws Throwable {
        when(invocation.getArguments()).thenReturn(new Object[]{null, message("  ")});
        when(invocation.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> interceptor.invoke(invocation))
                .isInstanceOf(ListenerExecutionFailedException.class)
                .hasCauseInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    @DisplayName("批次訊息（List）等非單一 Message 的呼叫 → 交給 delegate，不自行判斷")
    void nonSingleMessageData_isDelegated() throws Throwable {
        when(invocation.getArguments()).thenReturn(new Object[]{null, List.of(message(null))});
        when(delegate.invoke(invocation)).thenReturn("delegated");

        assertThat(interceptor.invoke(invocation)).isEqualTo("delegated");
    }
}
