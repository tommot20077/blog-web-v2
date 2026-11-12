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

    /**
     * 驗證重複信箱註冊通知 Queue：名稱正確、持久化、帶 DLQ 參數
     */
    @Test
    @DisplayName("registrationAttemptQueue 應為持久化且包含 DLQ args")
    void registrationAttemptQueue_isDurable_withDlqArgs() {
        Queue queue = config.registrationAttemptQueue();

        assertThat(queue.getName()).isEqualTo("user.registration.duplicate-email");
        assertThat(UserRabbitMqConfig.QUEUE_REGISTRATION_ATTEMPT).isEqualTo(queue.getName());
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq")
                .containsEntry("x-dead-letter-routing-key", "dead-letter");
    }

    /**
     * 驗證重複信箱註冊通知 Queue 以專屬 Routing Key 綁到 user.events Exchange，
     * 且不與註冊／重設密碼的 Routing Key 重疊（否則驗證信會被誤送到通知 Queue）
     */
    @Test
    @DisplayName("registrationAttemptBinding 應以專屬 routing key 綁定 user.events")
    void registrationAttemptBinding_usesDedicatedRoutingKey() {
        org.springframework.amqp.core.Binding binding = config.registrationAttemptBinding();

        assertThat(binding.getExchange()).isEqualTo(UserRabbitMqConfig.EXCHANGE);
        assertThat(binding.getDestination()).isEqualTo(UserRabbitMqConfig.QUEUE_REGISTRATION_ATTEMPT);
        assertThat(binding.getRoutingKey()).isEqualTo("user.registration.duplicate-email");
        assertThat(binding.getRoutingKey())
                .isNotEqualTo(UserRabbitMqConfig.ROUTING_KEY_REGISTERED)
                .isNotEqualTo(UserRabbitMqConfig.ROUTING_KEY_PASSWORD_RESET);
    }
}
