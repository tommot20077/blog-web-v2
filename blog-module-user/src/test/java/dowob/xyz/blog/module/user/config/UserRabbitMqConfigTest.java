package dowob.xyz.blog.module.user.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Queue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserRabbitMqConfig} 單元測試
 *
 * <p>驗證 Queue 宣告是否具備正確的持久化設定與死信佇列（DLQ）參數。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserRabbitMqConfig 單元測試")
class UserRabbitMqConfigTest {

    /** 受測物件 */
    private final UserRabbitMqConfig config = new UserRabbitMqConfig();

    /**
     * 驗證密碼重設 Queue 名稱正確、持久化，且帶有 DLQ 參數
     */
    @Test
    @DisplayName("passwordResetQueue 應為持久化且包含 DLQ args")
    void passwordResetQueue_isDurable_withDlqArgs() {
        Queue queue = config.passwordResetQueue();

        assertThat(queue.getName()).isEqualTo(UserRabbitMqConfig.QUEUE_PASSWORD_RESET);
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq");
    }

    /**
     * 驗證電子信箱驗證 Queue 也已加入 DLQ 參數
     */
    @Test
    @DisplayName("emailVerificationQueue 應包含 DLQ args")
    void emailVerificationQueue_hasDlqArgs() {
        Queue queue = config.emailVerificationQueue();

        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq");
    }
}
