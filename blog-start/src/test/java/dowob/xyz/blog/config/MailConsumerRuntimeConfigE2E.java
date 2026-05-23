package dowob.xyz.blog.config;

import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.module.user.config.UserRabbitMqConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Mail consumer runtime config")
class MailConsumerRuntimeConfigE2E extends AbstractE2ETest {

    @Autowired
    private Environment environment;

    @Autowired
    private RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;

    @Test
    @DisplayName("e2e profile should not start mail consumer listener containers")
    void e2eProfileShouldNotStartMailConsumerListenerContainers() {
        assertThat(environment.getProperty("app.mail.consumer-enabled"))
                .isEqualTo("false");

        List<AbstractMessageListenerContainer> mailContainers = rabbitListenerEndpointRegistry.getListenerContainers()
                .stream()
                .filter(AbstractMessageListenerContainer.class::isInstance)
                .map(AbstractMessageListenerContainer.class::cast)
                .filter(this::isMailConsumerContainer)
                .toList();

        assertThat(mailContainers)
                .as("mail listener containers should be absent or stopped in e2e")
                .extracting(MessageListenerContainer::isRunning)
                .doesNotContain(true);
    }

    private boolean isMailConsumerContainer(AbstractMessageListenerContainer container) {
        List<String> queueNames = List.of(container.getQueueNames());
        return queueNames.contains(UserRabbitMqConfig.QUEUE_EMAIL_VERIFICATION)
                || queueNames.contains(UserRabbitMqConfig.QUEUE_PASSWORD_RESET);
    }
}
