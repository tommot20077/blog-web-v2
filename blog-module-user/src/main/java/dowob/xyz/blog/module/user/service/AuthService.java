package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.module.user.config.UserRabbitMqConfig;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.VerificationToken;
import dowob.xyz.blog.module.user.model.dto.response.LoginResult;
import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.model.event.UserRegisteredEvent;
import dowob.xyz.blog.module.user.repository.UserRepository;
import dowob.xyz.blog.module.user.repository.VerificationTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 認證業務服務
 *
 * <p>負責用戶的註冊、登入、登出、電子信箱驗證、忘記密碼與重設密碼等認證相關業務邏輯。
 * 登入採用雙 Token 架構（Access Token + Refresh Token）。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    /** JWT 服務，用於生成與驗證 Token */
    private final JwtService jwtService;

    /** 用戶資料存取 */
    private final UserRepository userRepository;

    /** 密碼加密器 */
    private final PasswordEncoder passwordEncoder;

    /** Redis 操作模板 */
    private final StringRedisTemplate redisTemplate;

    /** RabbitMQ 發送模板 */
    private final RabbitTemplate rabbitTemplate;

    /** 驗證 Token 資料存取 */
    private final VerificationTokenRepository verificationTokenRepository;

    /**
     * 用戶註冊
     *
     * <p>驗證信箱與暱稱唯一性後建立帳號（PENDING_VERIFICATION 狀態），
     * 並發布 {@link UserRegisteredEvent} 至 RabbitMQ 以觸發驗證信發送。</p>
     *
     * @param email    電子信箱
     * @param password 明文密碼
     * @param nickname 暱稱
     */
    @Transactional
    public void register(String email, String password, String nickname) {
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(UserErrorCode.EMAIL_DUPLICATED);
        }
        if (userRepository.existsByNickname(nickname)) {
            throw new BusinessException(UserErrorCode.NICKNAME_DUPLICATED);
        }

        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setEmail(email);
        user.setNickname(nickname);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(Role.USER);
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.setTokenVersion("v1");

        User savedUser = userRepository.save(user);

        String tokenValue = UUID.randomUUID().toString();
        VerificationToken verificationToken = new VerificationToken();
        verificationToken.setUserId(savedUser.getId());
        verificationToken.setToken(tokenValue);
        verificationToken.setType("EMAIL_VERIFICATION");
        verificationToken.setExpiresAt(LocalDateTime.now().plusHours(24));
        verificationToken.setCreatedAt(LocalDateTime.now());
        verificationTokenRepository.save(verificationToken);

        rabbitTemplate.convertAndSend(
                UserRabbitMqConfig.EXCHANGE,
                UserRabbitMqConfig.ROUTING_KEY_REGISTERED,
                new UserRegisteredEvent(savedUser.getId(), savedUser.getEmail(), savedUser.getNickname(), tokenValue)
        );
    }

    /**
     * 用戶登入（雙 Token 架構）
     *
     * <p>支援以電子信箱或暱稱登入。成功後生成 Access Token 與 Refresh Token，
     * 並將 Refresh Token 寫入 Redis，同時更新 Auth Hash 快取。</p>
     *
     * @param identifier 登入識別符（電子信箱或暱稱）
     * @param password   明文密碼
     * @return 包含 accessToken 與 refreshToken 的 {@link LoginResult}
     */
    public LoginResult login(String identifier, String password) {
        User user = userRepository.findByEmail(identifier)
                .or(() -> userRepository.findByNickname(identifier))
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_PASSWORD_ERROR));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(UserErrorCode.USER_PASSWORD_ERROR);
        }

        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            throw new BusinessException(UserErrorCode.EMAIL_NOT_VERIFIED);
        }

        if (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.DELETED) {
            throw new BusinessException(UserErrorCode.ACCOUNT_SUSPENDED);
        }

        String accessToken = jwtService.generateAccessToken(user.getId(), user.getRole().name(), user.getTokenVersion());
        String refreshToken = jwtService.generateRefreshToken(user.getId());

        String authKey = RedisKeyConstant.getUserAuthKey(user.getId());
        redisTemplate.opsForHash().put(authKey, RedisKeyConstant.FIELD_VERSION, user.getTokenVersion());
        redisTemplate.opsForHash().put(authKey, RedisKeyConstant.FIELD_STATUS, user.getStatus().name());

        String refreshKey = RedisKeyConstant.getUserRefreshKey(user.getId());
        redisTemplate.opsForValue().set(refreshKey, refreshToken, 7, TimeUnit.DAYS);

        return new LoginResult(accessToken, refreshToken);
    }

    /**
     * 用戶登出
     *
     * <p>刪除 Redis 中儲存的 Refresh Token，使舊 Refresh Token 立即失效。</p>
     *
     * @param userId 用戶 ID
     */
    public void logout(Long userId) {
        String refreshKey = RedisKeyConstant.getUserRefreshKey(userId);
        redisTemplate.delete(refreshKey);
    }

    /**
     * 驗證電子信箱
     *
     * <p>查找並驗證電子信箱驗證 Token，成功後將用戶狀態更新為 ACTIVE
     * 並標記信箱已驗證，最後刪除已使用的驗證 Token。</p>
     *
     * @param token 電子信箱驗證 Token 字串
     */
    @Transactional
    public void verifyEmail(String token) {
        VerificationToken verificationToken = verificationTokenRepository
                .findByTokenAndType(token, "EMAIL_VERIFICATION")
                .orElseThrow(() -> new BusinessException(UserErrorCode.TOKEN_INVALID));

        if (verificationToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(UserErrorCode.TOKEN_INVALID);
        }

        User user = userRepository.findById(verificationToken.getUserId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        user.setEmailVerified(true);
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        verificationTokenRepository.delete(verificationToken);
    }

    /**
     * 申請忘記密碼
     *
     * <p>若信箱存在，建立密碼重設 Token（15 分鐘有效），並發布
     * {@link UserPasswordResetRequestedEvent} 至 RabbitMQ。
     * 若信箱不存在，靜默忽略（安全考量，不洩漏信箱是否已註冊）。</p>
     *
     * @param email 用戶電子信箱
     */
    @Transactional
    public void forgotPassword(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            String tokenValue = UUID.randomUUID().toString();
            VerificationToken resetToken = new VerificationToken();
            resetToken.setUserId(user.getId());
            resetToken.setToken(tokenValue);
            resetToken.setType("PASSWORD_RESET");
            resetToken.setExpiresAt(LocalDateTime.now().plusMinutes(15));
            resetToken.setCreatedAt(LocalDateTime.now());
            verificationTokenRepository.save(resetToken);

            rabbitTemplate.convertAndSend(
                    UserRabbitMqConfig.EXCHANGE,
                    "user.password.reset",
                    new UserPasswordResetRequestedEvent(user.getId(), user.getEmail(), tokenValue)
            );
        });
    }

    /**
     * 重設密碼
     *
     * <p>驗證密碼重設 Token 有效後，更新密碼雜湊並遞增 tokenVersion 使舊 Token 失效，
     * 最後刪除已使用的重設 Token。</p>
     *
     * @param token       密碼重設 Token 字串
     * @param newPassword 新的明文密碼
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        VerificationToken resetToken = verificationTokenRepository
                .findByTokenAndType(token, "PASSWORD_RESET")
                .orElseThrow(() -> new BusinessException(UserErrorCode.TOKEN_INVALID));

        if (resetToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(UserErrorCode.TOKEN_INVALID);
        }

        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion(incrementVersion(user.getTokenVersion()));
        userRepository.save(user);

        verificationTokenRepository.delete(resetToken);
    }

    /**
     * 遞增 Token 版本號
     *
     * <p>解析 "v{n}" 格式的版本字串，遞增數字後回傳新版本。
     * 若格式不符，預設從 v1 開始。</p>
     *
     * @param currentVersion 當前版本號，例如 "v1"
     * @return 新的版本號，例如 "v2"
     */
    public static String incrementVersion(String currentVersion) {
        if (currentVersion == null || !currentVersion.startsWith("v")) {
            return "v1";
        }
        try {
            int num = Integer.parseInt(currentVersion.substring(1));
            return "v" + (num + 1);
        } catch (NumberFormatException e) {
            return "v1";
        }
    }
}
