package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.module.user.model.event.RegistrationAttemptOnExistingEmailEvent;
import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.model.event.UserRegisteredEvent;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
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

    /**
     * 寄送「有人嘗試用你的信箱註冊」通知信給原信箱主人（SEC-14／Q8）
     *
     * <p>信箱已被註冊時，註冊端點對外一律回成功（避免帳號枚舉），因此由這封信告知原主人。
     * 內容只含固定文字、原主人的暱稱與前端登入／忘記密碼連結：<b>不含</b>任何驗證或重設 token，
     * 也不含嘗試者提交的資料，攻擊者無法藉由註冊請求在這封信裡注入文字或連結。</p>
     *
     * @param event 重複信箱註冊事件
     */
    public void sendRegistrationAttemptNotice(RegistrationAttemptOnExistingEmailEvent event) {
        String base = frontendBaseUrl.replaceAll("/+$", "");
        String body = """
                Hi %s,

                Someone tried to create a new account using this email address.
                Because an account with this email already exists, no new account was created
                and nothing about your account has changed.

                If this was you, you can sign in here:
                %s

                If you have forgotten your password, you can reset it here:
                %s

                If you have not verified this email address yet, request a new verification email from the
                sign-in page.

                If this was not you, you can safely ignore this email.
                """.formatted(event.nickname(), base + "/login", base + "/forgot-password");

        sendSecurityMail(event.email(), "Someone tried to register with your email address", body);
    }

    private void sendSecurityMail(String to, String subject, String body) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(securityFromAddress, securityFromName, StandardCharsets.UTF_8.name()));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailPreparationException("Failed to prepare security email", e);
        }
        mailSender.send(message);
    }

    private String buildUrl(String path, String token) {
        return frontendBaseUrl.replaceAll("/+$", "") + path + "?token=" + token;
    }
}
