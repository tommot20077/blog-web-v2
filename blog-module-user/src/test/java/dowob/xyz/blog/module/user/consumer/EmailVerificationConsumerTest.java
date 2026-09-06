package dowob.xyz.blog.module.user.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.module.user.model.event.UserRegisteredEvent;
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
 * {@link EmailVerificationConsumer} 單元測試
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailVerificationConsumer 單元測試")
class EmailVerificationConsumerTest {

    /** 受測物件 */
    @InjectMocks
    private EmailVerificationConsumer consumer;

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
    @DisplayName("處理用戶註冊事件後應呼叫 basicAck")
    void handleUserRegistered_success_acksMessage() throws IOException {
        UserRegisteredEvent event = new UserRegisteredEvent(1L, "test@example.com", "TestUser", "token-abc", "123456");
        long deliveryTag = 42L;

        consumer.handleUserRegistered(event, channel, deliveryTag);

        verify(userMailService).sendVerificationEmail(event);
        verify(channel).basicAck(deliveryTag, false);
    }

    /**
     * 驗證寄送驗證信失敗時，應呼叫 basicNack 將訊息送至 DLQ
     *
     * @throws IOException basicNack 可能拋出的 IO 例外
     */
    @Test
    @DisplayName("寄送驗證信失敗時呼叫 basicNack")
    void handleUserRegistered_onMailFailure_callsBasicNack() throws IOException {
        UserRegisteredEvent event = new UserRegisteredEvent(1L, "test@example.com", "TestUser", "token-abc", "123456");
        long deliveryTag = 88L;

        doThrow(new IllegalStateException("SMTP failed")).when(userMailService).sendVerificationEmail(event);

        consumer.handleUserRegistered(event, channel, deliveryTag);

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
    void handleUserRegistered_onAckFailure_callsBasicNack() throws IOException {
        UserRegisteredEvent event = new UserRegisteredEvent(1L, "test@example.com", "TestUser", "token-abc", "123456");
        long deliveryTag = 99L;

        doThrow(new IOException("ACK failed")).when(channel).basicAck(deliveryTag, false);

        consumer.handleUserRegistered(event, channel, deliveryTag);

        verify(channel).basicNack(eq(deliveryTag), eq(false), eq(false));
    }
}
