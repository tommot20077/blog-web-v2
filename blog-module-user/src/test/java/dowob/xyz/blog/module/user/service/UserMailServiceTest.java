package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.model.event.UserRegisteredEvent;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserMailService} 單元測試
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("UserMailService 單元測試")
class UserMailServiceTest {

    @Test
    @DisplayName("sendVerificationEmail → 應寄送包含前端驗證連結的安全信")
    void sendVerificationEmail_shouldSendSecurityEmailWithFrontendLink() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);
        UserMailService service = buildService(mailSender);
        UserRegisteredEvent event =
                new UserRegisteredEvent(1L, "new-user@example.com", "Yuan", "verify-token-abc", "123456");

        service.sendVerificationEmail(event);

        verify(mailSender).send(any(MimeMessage.class));
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("new-user@example.com");
        assertThat(message.getFrom()[0].toString())
                .contains("Blog Security Bot")
                .contains("no-reply@90030.xyz");
        assertThat(message.getSubject()).isEqualTo("Verify your email address");
        assertThat((String) message.getContent())
                .contains("https://90030.xyz/verify-email?token=verify-token-abc")
                .contains("123456")
                .contains("Yuan");
    }

    @Test
    @DisplayName("sendPasswordResetEmail → 應寄送包含前端重設密碼連結的安全信")
    void sendPasswordResetEmail_shouldSendSecurityEmailWithFrontendLink() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);
        UserMailService service = buildService(mailSender);
        UserPasswordResetRequestedEvent event =
                new UserPasswordResetRequestedEvent(1L, "user@example.com", "reset-token-abc");

        service.sendPasswordResetEmail(event);

        verify(mailSender).send(any(MimeMessage.class));
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("user@example.com");
        assertThat(message.getFrom()[0].toString())
                .contains("Blog Security Bot")
                .contains("no-reply@90030.xyz");
        assertThat(message.getSubject()).isEqualTo("Reset your password");
        assertThat((String) message.getContent())
                .contains("https://90030.xyz/reset-password?token=reset-token-abc");
    }

    private UserMailService buildService(JavaMailSender mailSender) {
        UserMailService service = new UserMailService(mailSender);
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "https://90030.xyz");
        ReflectionTestUtils.setField(service, "securityFromAddress", "no-reply@90030.xyz");
        ReflectionTestUtils.setField(service, "securityFromName", "Blog Security Bot");
        return service;
    }
}
