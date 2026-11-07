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
import dowob.xyz.blog.module.user.util.TokenHasher;
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
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 用戶安全郵件服務
 *
 * <p>負責寄送信箱驗證與密碼重設等安全通知信。寄信事件不含個資與 token（ARCH-24），
 * 本服務於寄信當下依 {@code userUuid} 查詢 users 與 Redis，並在此時<b>產生</b>驗證／重設 token（A1）：
 * 信件內容是明文 token，{@code verification_tokens} 只存其 SHA-256（見 {@link TokenHasher}）。</p>
 *
 * <p><b>交易與冪等（寄信失敗重試）</b>：兩個產生 token 的方法標 {@link Transactional}，被 consumer 的
 * {@code executeIdempotent} 呼叫時加入其交易，順序為「冪等標記 → 刪同類舊 token → 存新雜湊 → 寄信 → commit」。
 * 寄信（外部副作用）失敗 → 例外往外拋 → 交易 rollback，<b>標記、新雜湊、舊 token 的刪除一起消失</b>，
 * 重試時會重新產生 token 並再寄，不會因為「已標記處理過」而不寄，舊 token 也在新信寄出前一直保持有效。
 * 代價與既有的 Z3-a 取捨相同：寄信成功但 commit 失敗時，使用者拿到的 token 不在 DB（rollback），
 * 重送會再寄一封並產生新 token 取代；使用者最多多收一封，且只有最新那封有效。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserMailService {

    /** 電子信箱驗證 token 類型（與 {@code AuthService} 查詢的值一致） */
    private static final String TOKEN_TYPE_EMAIL_VERIFICATION = "EMAIL_VERIFICATION";

    /** 密碼重設 token 類型（與 {@code AuthService} 查詢的值一致） */
    private static final String TOKEN_TYPE_PASSWORD_RESET = "PASSWORD_RESET";

    /** 電子信箱驗證 token 有效期（小時） */
    private static final long EMAIL_VERIFICATION_TTL_HOURS = 24;

    /** 密碼重設 token 有效期（分鐘） */
    private static final long PASSWORD_RESET_TTL_MINUTES = 15;

    /** Spring Mail 發送器 */
    private final JavaMailSender mailSender;

    /** 用戶 Repository（寄信當下查 email、暱稱與狀態） */
    private final UserRepository userRepository;

    /** 驗證 Token Repository（寄信當下刪舊 token、存新 token 的 SHA-256） */
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
     * <p><b>事件不含個資與 token（ARCH-24）</b>，本方法於寄信當下以 {@code event.userUuid()} 查詢 email、暱稱
     * （{@code users}）與驗證碼（Redis，故障時降級為只寄連結），並<b>產生新的驗證連結 token</b>（A1）：
     * 先刪該使用者同類舊 token（讓「最新＝唯一」，舊連結立即失效）、存入新 token 的 SHA-256、最後才寄信，
     * 信中是明文。順序與交易語意見類別說明。</p>
     *
     * <p><b>略過不寄的情況</b>（皆以 INFO 記一行，不記 email 與 token，不產生 token，回傳 {@code false}，呼叫端正常 ack）：
     * 使用者不存在、使用者已刪帳（{@code DELETED}，email 已是墓碑值）、帳號已不是 {@code PENDING_VERIFICATION}
     * （已驗證，晚到的舊事件不該再發驗證連結）。連結有效但 Redis 已沒有驗證碼（TTL 10 分鐘，比連結 24 小時短）→ 照寄，信中只含連結。</p>
     *
     * @param event 用戶註冊事件
     * @return 是否真的寄出
     */
    @Transactional
    public boolean sendVerificationEmail(UserRegisteredEvent event) {
        Optional<User> user = findDeliverableUser(event.userUuid(), "驗證信");
        if (user.isEmpty()) {
            return false;
        }
        if (user.get().getStatus() != UserStatus.PENDING_VERIFICATION) {
            log.info("略過驗證信：帳號已不需驗證（status={}） - userUuid={}", user.get().getStatus(), user.get().getUuid());
            return false;
        }
        String plainToken = issueToken(user.get(), TOKEN_TYPE_EMAIL_VERIFICATION,
                LocalDateTime.now().plusHours(EMAIL_VERIFICATION_TTL_HOURS));
        String verifyUrl = buildUrl("/verify-email", plainToken);
        String code = readVerificationCode(user.get());
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
     * 並<b>產生新的重設 token</b>（A1，15 分鐘有效）：先刪該使用者同類舊 token、存新 token 的 SHA-256、最後才寄信，
     * 信中是明文。使用者不存在或已刪帳時不寄、不產生 token，回傳 {@code false}。</p>
     *
     * @param event 密碼重設事件
     * @return 是否真的寄出
     */
    @Transactional
    public boolean sendPasswordResetEmail(UserPasswordResetRequestedEvent event) {
        Optional<User> user = findDeliverableUser(event.userUuid(), "密碼重設信");
        if (user.isEmpty()) {
            return false;
        }
        String plainToken = issueToken(user.get(), TOKEN_TYPE_PASSWORD_RESET,
                LocalDateTime.now().plusMinutes(PASSWORD_RESET_TTL_MINUTES));
        String resetUrl = buildUrl("/reset-password", plainToken);
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
     * 讀取 Redis 中的驗證碼；Redis 故障時降級為 {@code null}（只寄連結），記 WARN 且不含 email。
     *
     * @param user 收件使用者
     * @return 驗證碼；不存在或 Redis 故障為 {@code null}
     */
    private String readVerificationCode(User user) {
        try {
            return redisTemplate.opsForValue().get(RedisKeyConstant.getEmailVerifyCodeKey(user.getEmail()));
        } catch (RuntimeException e) {
            log.warn("讀取驗證碼失敗，降級為只寄驗證連結 - userUuid={}, error={}", user.getUuid(), e.getClass().getSimpleName());
            return null;
        }
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
     * 產生新的高熵 token（UUID，沿用既有格式與長度），先刪該使用者同類舊 token，再存入新 token 的 SHA-256。
     *
     * <p>必須在呼叫端的交易內執行，且<b>寄信要在本方法之後</b>：寄信失敗 → 交易 rollback → 刪除與新雜湊一起撤銷。
     * 明文只回傳給呼叫端組信，不寫 DB、不寫日誌。</p>
     *
     * @param user      收件使用者
     * @param type      token 類型
     * @param expiresAt 過期時間
     * @return 明文 token（僅供組成信中連結）
     */
    private String issueToken(User user, String type, LocalDateTime expiresAt) {
        String plainToken = UUID.randomUUID().toString();
        verificationTokenRepository.deleteByUserIdAndType(user.getId(), type);

        VerificationToken token = new VerificationToken();
        token.setUserId(user.getId());
        token.setTokenHash(TokenHasher.sha256Hex(plainToken));
        token.setType(type);
        token.setExpiresAt(expiresAt);
        token.setCreatedAt(LocalDateTime.now());
        verificationTokenRepository.save(token);
        return plainToken;
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
