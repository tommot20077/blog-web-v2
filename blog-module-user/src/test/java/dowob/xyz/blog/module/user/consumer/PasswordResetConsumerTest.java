package dowob.xyz.blog.module.user.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

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

        verify(channel).basicAck(deliveryTag, false);
    }
}
