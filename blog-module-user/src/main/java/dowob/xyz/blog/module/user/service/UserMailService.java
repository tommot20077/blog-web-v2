package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.model.event.UserRegisteredEvent;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 用戶安全郵件服務
 *
 * <p>負責寄送信箱驗證與密碼重設等安全通知信。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class UserMailService {

    /** Spring Mail 發送器 */
    private final JavaMailSender mailSender;

    /** 前端基礎網址 */
    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    /** 安全信寄件地址 */
    @Value("${app.mail.security-from.address}")
    private String securityFromAddress;

    /** 安全信寄件名稱 */
    @Value("${app.mail.security-from.name}")
    private String securityFromName;

    /**
     * 寄送信箱驗證信
     *
     * @param event 用戶註冊事件
     */
    public void sendVerificationEmail(UserRegisteredEvent event) {
        String verifyUrl = buildUrl("/verify-email", event.verificationToken());
        String body = """
                Hi %s,

                Please verify your email address by opening this link:
                %s

                Or enter this verification code:
                %s

                This link expires in 24 hours.
                """.formatted(event.nickname(), verifyUrl, event.verificationCode());

        sendSecurityMail(event.email(), "Verify your email address", body);
    }

    /**
     * 寄送密碼重設信
     *
     * @param event 密碼重設事件
     */
    public void sendPasswordResetEmail(UserPasswordResetRequestedEvent event) {
        String resetUrl = buildUrl("/reset-password", event.resetToken());
        String body = """
                Hi,

                Reset your password by opening this link:
                %s

                This link expires in 15 minutes. If you did not request a password reset, ignore this email.
                """.formatted(resetUrl);

        sendSecurityMail(event.email(), "Reset your password", body);
    }

    private void sendSecurityMail(String to, String subject, String body) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message,
                    false,
                    StandardCharsets.UTF_8.name()
            );
            helper.setFrom(new InternetAddress(securityFromAddress, securityFromName, StandardCharsets.UTF_8.name()));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(message);
        } catch (Exception e) {
            throw new MailPreparationException("Failed to prepare security email", e);
        }
    }

    private String buildUrl(String path, String token) {
        return frontendBaseUrl.replaceAll("/+$", "") + path + "?token=" + token;
    }
}
