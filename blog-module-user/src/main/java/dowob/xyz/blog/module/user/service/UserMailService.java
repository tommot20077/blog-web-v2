package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.VerificationToken;
import dowob.xyz.blog.module.user.model.event.RegistrationAttemptOnExistingEmailEvent;
import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.model.event.UserRegisteredEvent;
import dowob.xyz.blog.module.user.repository.UserRepository;
import dowob.xyz.blog.module.user.repository.VerificationTokenRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 用戶安全郵件服務
 *
 * <p>負責寄送信箱驗證與密碼重設等安全通知信。寄信事件不含個資與 token（ARCH-24），
 * 本服務於寄信當下依 {@code userUuid} 查詢 users、verification_tokens 與 Redis。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserMailService {

    /** 電子信箱驗證 token 類型（與 {@code AuthService} 寫入的值一致） */
    private static final String TOKEN_TYPE_EMAIL_VERIFICATION = "EMAIL_VERIFICATION";

    /** 密碼重設 token 類型（與 {@code AuthService} 寫入的值一致） */
    private static final String TOKEN_TYPE_PASSWORD_RESET = "PASSWORD_RESET";

    /** Spring Mail 發送器 */
    private final JavaMailSender mailSender;

    /** 用戶 Repository（寄信當下查 email、暱稱與狀態） */
    private final UserRepository userRepository;

    /** 驗證 Token Repository（寄信當下查要寄出的 token） */
    private final VerificationTokenRepository verificationTokenRepository;

    /** Redis 操作模板（寄信當下查 6 位數驗證碼） */
    private final StringRedisTemplate redisTemplate;

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
     * <p><b>事件不含個資與 token（ARCH-24）</b>，本方法於寄信當下以 {@code event.userUuid()} 查詢：
     * email、暱稱查 {@code users}；驗證連結 token 查 {@code verification_tokens}
     * 該使用者<b>最新一筆</b>且未過期的 {@code EMAIL_VERIFICATION}；驗證碼查 Redis。</p>
     *
     * <p><b>為何查「最新有效」而非事件指定的那一筆</b>：token 表只有 Long 主鍵（事件契約禁止 Long，且本任務不新增 migration），
     * 而「重寄」本來就會刪舊建新（{@code deleteByUserIdAndType}）。查最新有效即可保證晚到的舊事件<b>絕不會寄出
     * 已被取代或已過期的 token</b>；代價是舊事件與新事件各自寄出同一個（最新的）連結——使用者最多收到內容相同的兩封，
     * 且兩封都是有效連結。</p>
     *
     * <p><b>略過不寄的情況</b>（皆以 INFO 記一行，不記 email 與 token，回傳 {@code false}，呼叫端正常 ack）：
     * 使用者不存在、使用者已刪帳（{@code DELETED}，email 已是墓碑值）、沒有 token 列（已驗證或已被刪除）、token 已過期。
     * 驗證連結與驗證碼視為同一次驗證：沒有有效連結就整封不寄（驗證碼也隨 token 的驗證流程一起失效）。
     * 連結有效但 Redis 已沒有驗證碼（TTL 10 分鐘，比連結 24 小時短）→ 照寄，信中只含連結。</p>
     *
     * @param event 用戶註冊事件
     * @return 是否真的寄出
     */
    public boolean sendVerificationEmail(UserRegisteredEvent event) {
        Optional<User> user = findDeliverableUser(event.userUuid(), "驗證信");
        if (user.isEmpty()) {
            return false;
        }
        Optional<VerificationToken> token = findValidToken(user.get(), TOKEN_TYPE_EMAIL_VERIFICATION, "驗證信");
        if (token.isEmpty()) {
            return false;
        }
        String verifyUrl = buildUrl("/verify-email", token.get().getToken());
        String code = redisTemplate.opsForValue().get(RedisKeyConstant.getEmailVerifyCodeKey(user.get().getEmail()));
        String codePart = code == null ? "" : """

                Or enter this verification code:
                %s
                """.formatted(code);
        String body = """
                Hi %s,

                Please verify your email address by opening this link:
                %s
                %s
                This link expires in 24 hours.
                """.formatted(user.get().getNickname(), verifyUrl, codePart);

        sendSecurityMail(user.get().getEmail(), "Verify your email address", body);
        return true;
    }

    /**
     * 寄送密碼重設信
     *
     * <p><b>事件不含個資與 token（ARCH-24）</b>，本方法以 {@code event.userUuid()} 查 {@code users} 取得 email，
     * 並查該使用者最新一筆未過期的 {@code PASSWORD_RESET} token（選擇「最新有效」的理由同
     * {@link #sendVerificationEmail}）。使用者已刪帳、token 已被使用（列已刪）或已過期時不寄，回傳 {@code false}。</p>
     *
     * @param event 密碼重設事件
     * @return 是否真的寄出
     */
    public boolean sendPasswordResetEmail(UserPasswordResetRequestedEvent event) {
        Optional<User> user = findDeliverableUser(event.userUuid(), "密碼重設信");
        if (user.isEmpty()) {
            return false;
        }
        Optional<VerificationToken> token = findValidToken(user.get(), TOKEN_TYPE_PASSWORD_RESET, "密碼重設信");
        if (token.isEmpty()) {
            return false;
        }
        String resetUrl = buildUrl("/reset-password", token.get().getToken());
        String body = """
                Hi,

                Reset your password by opening this link:
                %s

                This link expires in 15 minutes. If you did not request a password reset, ignore this email.
                """.formatted(resetUrl);

        sendSecurityMail(user.get().getEmail(), "Reset your password", body);
        return true;
    }

    /**
     * 寄送「有人嘗試用你的信箱註冊」通知信給原信箱主人（SEC-14／Q8）
     *
     * <p>信箱已被註冊時，註冊端點回應與新註冊相同的成功（消除直接回錯誤碼的零成本列舉；副作用列舉殘餘見 findings SEC-14），因此由這封信告知原主人。
     * 事件不含個資（ARCH-24），本方法以 {@code event.userUuid()} 查 {@code users} 取得 email 與暱稱；使用者已刪帳時不寄。
     * 內容只含固定文字、原主人的暱稱與前端登入／忘記密碼連結：<b>不含</b>任何驗證或重設 token，
     * 也不含嘗試者提交的資料，攻擊者無法藉由註冊請求在這封信裡注入文字或連結。</p>
     *
     * @param event 重複信箱註冊事件
     * @return 是否真的寄出
     */
    public boolean sendRegistrationAttemptNotice(RegistrationAttemptOnExistingEmailEvent event) {
        Optional<User> owner = findDeliverableUser(event.userUuid(), "重複註冊通知信");
        if (owner.isEmpty()) {
            return false;
        }
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
                """.formatted(owner.get().getNickname(), base + "/login", base + "/forgot-password");

        sendSecurityMail(owner.get().getEmail(), "Someone tried to register with your email address", body);
        return true;
    }

    /**
     * 以 uuid 查可寄信的使用者；不存在或已刪帳（DATA-02 匿名化後 email 為墓碑值）時回 empty 並記 INFO。
     *
     * @param userUuid 用戶公開 UUID（可為 null，視為不存在）
     * @param mailKind 信件種類（日誌用）
     * @return 可寄信的使用者
     */
    private Optional<User> findDeliverableUser(UUID userUuid, String mailKind) {
        Optional<User> user = userUuid == null ? Optional.empty() : userRepository.findByUuid(userUuid);
        if (user.isEmpty()) {
            log.info("略過{}：查無使用者 - userUuid={}", mailKind, userUuid);
            return Optional.empty();
        }
        if (user.get().getStatus() == UserStatus.DELETED) {
            log.info("略過{}：使用者已刪帳 - userUuid={}", mailKind, userUuid);
            return Optional.empty();
        }
        return user;
    }

    /**
     * 查使用者指定類型最新一筆 token，且必須未過期；否則回 empty 並記 INFO（不記 token 內容）。
     *
     * @param user     收件使用者
     * @param type     token 類型
     * @param mailKind 信件種類（日誌用）
     * @return 最新且未過期的 token
     */
    private Optional<VerificationToken> findValidToken(User user, String type, String mailKind) {
        Optional<VerificationToken> token =
                verificationTokenRepository.findFirstByUserIdAndTypeOrderByIdDesc(user.getId(), type);
        if (token.isEmpty()) {
            log.info("略過{}：已無有效 token（已使用或已被取代刪除） - userUuid={}", mailKind, user.getUuid());
            return Optional.empty();
        }
        if (token.get().getExpiresAt().isBefore(LocalDateTime.now())) {
            log.info("略過{}：token 已過期 - userUuid={}", mailKind, user.getUuid());
            return Optional.empty();
        }
        return token;
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
