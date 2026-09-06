package dowob.xyz.blog.module.user.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.service.UserMailService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PasswordResetConsumer} 單元測試
 *
 * <p>驗證消費者在處理密碼重設事件後，是否正確呼叫 Manual Ack。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordResetConsumer 單元測試")
class PasswordResetConsumerTest {

    /** 受測物件 */
    @InjectMocks
    private PasswordResetConsumer consumer;

    /** Mock RabbitMQ Channel */
    @Mock
    private Channel channel;

    /** Mock 郵件服務 */
    @Mock
    private UserMailService userMailService;

    /**
     * 驗證成功處理事件後，basicAck 被正確呼叫
     *
     * @throws IOException basicAck 可能拋出的 IO 例外
     */
    @Test
    @DisplayName("處理密碼重設事件後應呼叫 basicAck")
    void handlePasswordResetRequested_success_acksMessage() throws IOException {
        UserPasswordResetRequestedEvent event =
                new UserPasswordResetRequestedEvent(1L, "test@example.com", "reset-token-abc");
        long deliveryTag = 42L;

        consumer.handlePasswordResetRequested(event, channel, deliveryTag);

        verify(userMailService).sendPasswordResetEmail(event);
        verify(channel).basicAck(deliveryTag, false);
    }

    /**
     * 驗證寄送密碼重設信失敗時，應呼叫 basicNack 將訊息送至 DLQ
     *
     * @throws IOException basicNack 可能拋出的 IO 例外
     */
    @Test
    @DisplayName("寄送密碼重設信失敗時呼叫 basicNack")
    void handlePasswordResetRequested_onMailFailure_callsBasicNack() throws IOException {
        UserPasswordResetRequestedEvent event =
                new UserPasswordResetRequestedEvent(1L, "test@example.com", "reset-token-abc");
        long deliveryTag = 88L;

        doThrow(new IllegalStateException("SMTP failed")).when(userMailService).sendPasswordResetEmail(event);

        consumer.handlePasswordResetRequested(event, channel, deliveryTag);

        verify(channel).basicNack(eq(deliveryTag), eq(false), eq(false));
        verify(channel, never()).basicAck(deliveryTag, false);
    }

    /**
     * 驗證 basicAck 失敗時，應呼叫 basicNack 將訊息送至 DLQ
     *
     * @throws IOException basicNack 可能拋出的 IO 例外
     */
    @Test
    @DisplayName("basicAck 失敗時呼叫 basicNack")
    void handlePasswordResetRequested_onAckFailure_callsBasicNack() throws IOException {
        UserPasswordResetRequestedEvent event =
                new UserPasswordResetRequestedEvent(1L, "test@example.com", "reset-token-abc");
        long deliveryTag = 99L;

        doThrow(new IOException("ACK failed")).when(channel).basicAck(deliveryTag, false);

        consumer.handlePasswordResetRequested(event, channel, deliveryTag);

        verify(channel).basicNack(eq(deliveryTag), eq(false), eq(false));
    }
}
