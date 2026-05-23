package dowob.xyz.blog.config;

import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Mail consumer profile config")
class MailConsumerProfileConfigTest {

    @Test
    @DisplayName("email verification listener should honor the mail consumer toggle")
    void emailVerificationListenerShouldHonorToggle() throws IOException {
        String source = Files.readString(Path.of(
                "..",
                "blog-module-user",
                "src",
                "main",
                "java",
                "dowob",
                "xyz",
                "blog",
                "module",
                "user",
                "consumer",
                "EmailVerificationConsumer.java"
        ));

        assertThat(source).contains("autoStartup = \"${app.mail.consumer-enabled:true}\"");
    }

    @Test
    @DisplayName("password reset listener should honor the mail consumer toggle")
    void passwordResetListenerShouldHonorToggle() throws IOException {
        String source = Files.readString(Path.of(
                "..",
                "blog-module-user",
                "src",
                "main",
                "java",
                "dowob",
                "xyz",
                "blog",
                "module",
                "user",
                "consumer",
                "PasswordResetConsumer.java"
        ));

        assertThat(source).contains("autoStartup = \"${app.mail.consumer-enabled:true}\"");
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
