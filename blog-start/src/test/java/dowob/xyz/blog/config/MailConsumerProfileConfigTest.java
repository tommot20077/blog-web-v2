package dowob.xyz.blog.config;

import dowob.xyz.blog.module.user.consumer.EmailVerificationConsumer;
import dowob.xyz.blog.module.user.consumer.PasswordResetConsumer;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Mail consumer profile config")
class MailConsumerProfileConfigTest {

    @Test
    @DisplayName("email verification listener annotations should honor the mail consumer toggle")
    void emailVerificationListenerAnnotationsShouldHonorToggle() throws NoSuchMethodException {
        ConditionalOnProperty conditional = EmailVerificationConsumer.class.getAnnotation(ConditionalOnProperty.class);
        Method listenerMethod = EmailVerificationConsumer.class.getMethod(
                "handleUserRegistered",
                dowob.xyz.blog.module.user.model.event.UserRegisteredEvent.class,
                com.rabbitmq.client.Channel.class,
                long.class
        );
        RabbitListener listener = listenerMethod.getAnnotation(RabbitListener.class);

        assertThat(conditional).isNotNull();
        assertThat(conditional.name()).containsExactly("app.mail.consumer-enabled");
        assertThat(conditional.havingValue()).isEqualTo("true");
        assertThat(conditional.matchIfMissing()).isTrue();

        assertThat(listener).isNotNull();
        assertThat(listener.autoStartup()).isEqualTo("${app.mail.consumer-enabled:true}");
    }

    @Test
    @DisplayName("password reset listener annotations should honor the mail consumer toggle")
    void passwordResetListenerAnnotationsShouldHonorToggle() throws NoSuchMethodException {
        ConditionalOnProperty conditional = PasswordResetConsumer.class.getAnnotation(ConditionalOnProperty.class);
        Method listenerMethod = PasswordResetConsumer.class.getMethod(
                "handlePasswordResetRequested",
                dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent.class,
                com.rabbitmq.client.Channel.class,
                long.class
        );
        RabbitListener listener = listenerMethod.getAnnotation(RabbitListener.class);

        assertThat(conditional).isNotNull();
        assertThat(conditional.name()).containsExactly("app.mail.consumer-enabled");
        assertThat(conditional.havingValue()).isEqualTo("true");
        assertThat(conditional.matchIfMissing()).isTrue();

        assertThat(listener).isNotNull();
        assertThat(listener.autoStartup()).isEqualTo("${app.mail.consumer-enabled:true}");
    }

    @Test
    @DisplayName("dev and e2e profiles should disable mail consumers")
    void devAndE2EProfilesShouldDisableMailConsumers() throws IOException {
        String devYaml = new ClassPathResource("application-dev.yaml")
                .getContentAsString(StandardCharsets.UTF_8);
        String e2eYaml = new ClassPathResource("application-e2e.yaml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(devYaml).contains("consumer-enabled: false");
        assertThat(e2eYaml).contains("consumer-enabled: false");
    }
}
