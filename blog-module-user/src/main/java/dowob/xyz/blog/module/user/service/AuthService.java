package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.module.user.util.TokenVersionUtils;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
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
 * @version 2.1
 */
@Slf4j
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

    /** Spring 宣告式事務模板（用於縮小事務範圍，避免 MQ 在 transaction 內發送） */
    private final TransactionTemplate transactionTemplate;

    /**
     * 用戶註冊
     *
     * <p>驗證信箱、用戶名與暱稱唯一性後建立帳號（PENDING_VERIFICATION 狀態），
     * 並發布 {@link UserRegisteredEvent} 至 RabbitMQ 以觸發驗證信發送。</p>
     *
     * @param email    電子信箱
     * @param password 明文密碼
     * @param username 用戶名（唯一登入識別符）
     * @param nickname 暱稱
     */
    public void register(String email, String password, String username, String nickname) {
        register(email, password, username, nickname, null);
    }

    /**
     * 用戶註冊（含 client IP 層級限流）
     *
     * <p>在既有註冊流程外，先以 client IP 計數限流（{@link RedisKeyConstant#REGISTER_IP_MAX}
     * 次 / {@link RedisKeyConstant#REGISTER_IP_TTL_MINUTES} 分鐘窗口），超過上限拋出
     * {@link UserErrorCode#RATE_LIMIT_EXCEEDED}。此限流與信箱/用戶名唯一性檢查獨立，
     * 用於抑制單一 IP 大量建立假帳號。{@code clientIp} 為 null 時跳過 IP 限流。</p>
     *
     * @param email    電子信箱
     * @param password 明文密碼
     * @param username 用戶名（唯一登入識別符）
     * @param nickname 暱稱
     * @param clientIp client IP（可為 null，表示無法解析，跳過 IP 限流）
     */
    public void register(String email, String password, String username, String nickname, String clientIp) {
        checkIpRateLimit(
                clientIp == null ? null : RedisKeyConstant.getRegisterIpKey(clientIp),
                RedisKeyConstant.REGISTER_IP_MAX,
                RedisKeyConstant.REGISTER_IP_TTL_MINUTES);

        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(UserErrorCode.EMAIL_DUPLICATED);
        }
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(UserErrorCode.USERNAME_DUPLICATED);
        }
        if (userRepository.existsByNickname(nickname)) {
            throw new BusinessException(UserErrorCode.NICKNAME_DUPLICATED);
        }

        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setEmail(email);
        user.setUsername(username);
        user.setNickname(nickname);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(Role.USER);
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.setTokenVersion("v1");

        /** DB 操作（save user + save verificationToken）在同一個 transaction 內 */
        String tokenValue = UUID.randomUUID().toString();
        String verificationCode = generateVerificationCode();
        User savedUser = transactionTemplate.execute(status -> {
            User saved = userRepository.save(user);

            VerificationToken verificationToken = new VerificationToken();
            verificationToken.setUserId(saved.getId());
            verificationToken.setToken(tokenValue);
            verificationToken.setType("EMAIL_VERIFICATION");
            verificationToken.setExpiresAt(LocalDateTime.now().plusHours(24));
            verificationToken.setCreatedAt(LocalDateTime.now());
            verificationTokenRepository.save(verificationToken);

            return saved;
        });

        /** DB 已 commit，best-effort 發 MQ（失敗不影響註冊結果） */
        saveEmailVerificationCode(savedUser.getEmail(), verificationCode);
        try {
            rabbitTemplate.convertAndSend(
                    UserRabbitMqConfig.EXCHANGE,
                    UserRabbitMqConfig.ROUTING_KEY_REGISTERED,
                    new UserRegisteredEvent(savedUser.getId(), savedUser.getEmail(),
                            savedUser.getNickname(), tokenValue, verificationCode));
        } catch (Exception e) {
            log.warn("MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
        }
    }

    /**
     * 用戶登入（雙 Token 架構）
     *
     * <p>支援以電子信箱或用戶名登入。內建登入鎖定機制：連續失敗 5 次後帳號鎖定 15 分鐘。
     * 成功後生成 Access Token 與 Refresh Token，Refresh Token 以 ZSet 儲存，
     * 最多允許 3 個同時登入的裝置（自動移除最舊的）。</p>
     *
     * @param identifier 登入識別符（電子信箱或暱稱）
     * @param password   明文密碼
     * @return 包含 accessToken 與 refreshToken 的 {@link LoginResult}
     */
    public LoginResult login(String identifier, String password) {
        return login(identifier, password, null);
    }

    /**
     * 用戶登入（雙 Token 架構，含 client IP 層級限流）
     *
     * <p>在既有 user.id 失敗鎖定之外，新增以 client IP 計數的限流：單一 IP 在
     * {@link RedisKeyConstant#LOGIN_IP_TTL_MINUTES} 分鐘窗口內登入嘗試超過
     * {@link RedisKeyConstant#LOGIN_IP_MAX} 次即拋出 {@link UserErrorCode#RATE_LIMIT_EXCEEDED}。
     * IP 限流在查詢用戶之前執行（防帳號枚舉），與 user.id 鎖定互不干擾。
     * {@code clientIp} 為 null 時跳過 IP 限流。</p>
     *
     * @param identifier 登入識別符（電子信箱或用戶名）
     * @param password   明文密碼
     * @param clientIp   client IP（可為 null，表示無法解析，跳過 IP 限流）
     * @return 包含 accessToken 與 refreshToken 的 {@link LoginResult}
     */
    public LoginResult login(String identifier, String password, String clientIp) {
        checkIpRateLimit(
                clientIp == null ? null : RedisKeyConstant.getLoginIpKey(clientIp),
                RedisKeyConstant.LOGIN_IP_MAX,
                RedisKeyConstant.LOGIN_IP_TTL_MINUTES);

        User user = userRepository.findByEmail(identifier)
                .or(() -> userRepository.findByUsername(identifier))
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_PASSWORD_ERROR));

        String loginFailKey = RedisKeyConstant.getLoginFailKey(user.getId());
        String failCountStr = redisTemplate.opsForValue().get(loginFailKey);
        if (failCountStr != null && Integer.parseInt(failCountStr) >= RedisKeyConstant.LOGIN_FAIL_MAX) {
            throw new BusinessException(UserErrorCode.ACCOUNT_LOCKED);
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            Long count = redisTemplate.opsForValue().increment(loginFailKey);
            if (count != null && count == 1L) {
                redisTemplate.expire(loginFailKey, RedisKeyConstant.LOGIN_FAIL_TTL_MINUTES, TimeUnit.MINUTES);
            }
            throw new BusinessException(UserErrorCode.USER_PASSWORD_ERROR);
        }

        redisTemplate.delete(loginFailKey);

        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            throw new BusinessException(UserErrorCode.EMAIL_NOT_VERIFIED);
        }

        if (user.getStatus() == UserStatus.BANNED || user.getStatus() == UserStatus.DELETED) {
            throw new BusinessException(UserErrorCode.ACCOUNT_SUSPENDED);
        }

        String accessToken = jwtService.generateAccessToken(user.getId(), user.getRole().name(), user.getTokenVersion());
        String refreshToken = jwtService.generateRefreshToken(user.getId());

        String authKey = RedisKeyConstant.getUserAuthKey(user.getId());
        redisTemplate.opsForHash().put(authKey, RedisKeyConstant.FIELD_VERSION, user.getTokenVersion());
        redisTemplate.opsForHash().put(authKey, RedisKeyConstant.FIELD_STATUS, user.getStatus().name());
        redisTemplate.expire(authKey, RedisKeyConstant.USER_AUTH_TTL_DAYS, TimeUnit.DAYS);

        String refreshKey = RedisKeyConstant.getUserRefreshKey(user.getId());
        redisTemplate.opsForZSet().add(refreshKey, refreshToken, System.currentTimeMillis());
        Long deviceCount = redisTemplate.opsForZSet().zCard(refreshKey);
        if (deviceCount != null && deviceCount > 3) {
            redisTemplate.opsForZSet().popMin(refreshKey);
        }
        redisTemplate.expire(refreshKey, 7, TimeUnit.DAYS);

        return new LoginResult(accessToken, refreshToken);
    }

    /**
     * 用戶登出
     *
     * <p>從 ZSet 中移除指定的 Refresh Token，使該裝置立即失效。
     * 若 refreshToken 為空，刪除整個 ZSet 以登出所有裝置。</p>
     *
     * @param userId       用戶 ID
     * @param refreshToken 要撤銷的 Refresh Token（可為 null，表示登出所有裝置）
     */
    public void logout(Long userId, String refreshToken) {
        String refreshKey = RedisKeyConstant.getUserRefreshKey(userId);
        if (refreshToken != null && !refreshToken.isBlank()) {
            redisTemplate.opsForZSet().remove(refreshKey, refreshToken);
        } else {
            redisTemplate.delete(refreshKey);
        }
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
        redisTemplate.delete(RedisKeyConstant.getEmailVerifyCodeKey(user.getEmail()));
    }

    /**
     * 使用 6 位數驗證碼驗證電子信箱
     *
     * <p>內建防暴力破解機制：失敗次數達 {@link RedisKeyConstant#EMAIL_VERIFY_CODE_MAX_ATTEMPTS}
     * 後拒絕後續請求，直至驗證碼過期或重新發送。
     * DB 在 {@link TransactionTemplate} 內提交後才清除 Redis 資料，避免 DB rollback 導致驗證碼遺失。</p>
     *
     * @param email 電子信箱
     * @param code  6 位數驗證碼
     */
    public void verifyEmailCode(String email, String code) {
        String failKey = RedisKeyConstant.getEmailVerifyCodeFailKey(email);
        String failCountStr = redisTemplate.opsForValue().get(failKey);
        if (failCountStr != null && Integer.parseInt(failCountStr) >= RedisKeyConstant.EMAIL_VERIFY_CODE_MAX_ATTEMPTS) {
            throw new BusinessException(UserErrorCode.RATE_LIMIT_EXCEEDED);
        }

        String codeKey = RedisKeyConstant.getEmailVerifyCodeKey(email);
        String storedCode = redisTemplate.opsForValue().get(codeKey);
        if (storedCode == null || !storedCode.equals(code)) {
            Long count = redisTemplate.opsForValue().increment(failKey);
            if (count != null && count == 1L) {
                redisTemplate.expire(failKey, RedisKeyConstant.EMAIL_VERIFY_CODE_TTL_MINUTES, TimeUnit.MINUTES);
            }
            throw new BusinessException(UserErrorCode.TOKEN_INVALID);
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.TOKEN_INVALID));

        // 此處所有 non-PENDING 狀態統一回傳 TOKEN_INVALID，避免洩漏帳號狀態資訊（意圖設計）
        if (user.getStatus() != UserStatus.PENDING_VERIFICATION) {
            throw new BusinessException(UserErrorCode.TOKEN_INVALID);
        }

        /** DB 在 transaction 內提交；提交後才清 Redis，避免 DB rollback 導致驗證碼遺失 */
        transactionTemplate.executeWithoutResult(status -> {
            user.setEmailVerified(true);
            user.setStatus(UserStatus.ACTIVE);
            userRepository.save(user);
            verificationTokenRepository.deleteByUserIdAndType(user.getId(), "EMAIL_VERIFICATION");
        });

        try {
            redisTemplate.delete(codeKey);
            redisTemplate.delete(failKey);
        } catch (Exception e) {
            log.warn("Redis 清理驗證碼失敗（best-effort）: {}", e.getMessage(), e);
        }
    }

    /**
     * 申請忘記密碼
     *
     * <p>若信箱存在，建立密碼重設 Token（15 分鐘有效），並發布
     * {@link UserPasswordResetRequestedEvent} 至 RabbitMQ。
     * 若信箱不存在，靜默忽略（安全考量，不洩漏信箱是否已註冊）。
     * 每封信箱每分鐘限呼叫 1 次、每日限 5 次，超過拋出 RATE_LIMIT_EXCEEDED。</p>
     *
     * @param email 用戶電子信箱
     */
    public void forgotPassword(String email) {
        /** 速率限制檢查（Redis 操作，不需要在 DB transaction 內） */
        String minKey = RedisKeyConstant.getForgotPwdMinKey(email);
        String dayKey = RedisKeyConstant.getForgotPwdDayKey(email);

        Long minCount = redisTemplate.opsForValue().increment(minKey);
        if (minCount != null && minCount == 1L) {
            redisTemplate.expire(minKey, 60, TimeUnit.SECONDS);
        }
        if (minCount != null && minCount > 1L) {
            throw new BusinessException(UserErrorCode.RATE_LIMIT_EXCEEDED);
        }

        Long dayCount = redisTemplate.opsForValue().increment(dayKey);
        if (dayCount != null && dayCount == 1L) {
            redisTemplate.expire(dayKey, 86400, TimeUnit.SECONDS);
        }
        if (dayCount != null && dayCount > 5L) {
            throw new BusinessException(UserErrorCode.RATE_LIMIT_EXCEEDED);
        }

        userRepository.findByEmail(email).ifPresent(user -> {
            /** DB 操作在 transaction 內 */
            String tokenValue = UUID.randomUUID().toString();
            transactionTemplate.executeWithoutResult(status -> {
                VerificationToken resetToken = new VerificationToken();
                resetToken.setUserId(user.getId());
                resetToken.setToken(tokenValue);
                resetToken.setType("PASSWORD_RESET");
                resetToken.setExpiresAt(LocalDateTime.now().plusMinutes(15));
                resetToken.setCreatedAt(LocalDateTime.now());
                verificationTokenRepository.save(resetToken);
            });

            /** DB 已 commit，best-effort 發 MQ（失敗不影響忘記密碼結果） */
            try {
                rabbitTemplate.convertAndSend(
                        UserRabbitMqConfig.EXCHANGE,
                        UserRabbitMqConfig.ROUTING_KEY_PASSWORD_RESET,
                        new UserPasswordResetRequestedEvent(user.getId(), user.getEmail(), tokenValue));
            } catch (Exception e) {
                log.warn("MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * 重發驗證信
     *
     * <p>若信箱存在且帳號處於 PENDING_VERIFICATION 狀態，刪除舊有驗證 Token 並建立新的
     * （24 小時有效），接著發布 {@link UserRegisteredEvent} 至 RabbitMQ。
     * 若信箱不存在或帳號已啟用，靜默忽略（安全考量）。
     * 每封信箱每分鐘限呼叫 1 次、每日限 5 次，超過拋出 RATE_LIMIT_EXCEEDED。</p>
     *
     * @param email 用戶電子信箱
     */
    public void resendVerification(String email) {
        /** 速率限制檢查（Redis 操作，不需要在 DB transaction 內） */
        String minKey = RedisKeyConstant.getResendVerifyMinKey(email);
        String dayKey = RedisKeyConstant.getResendVerifyDayKey(email);

        Long minCount = redisTemplate.opsForValue().increment(minKey);
        if (minCount != null && minCount == 1L) {
            redisTemplate.expire(minKey, 60, TimeUnit.SECONDS);
        }
        if (minCount != null && minCount > 1L) {
            throw new BusinessException(UserErrorCode.RATE_LIMIT_EXCEEDED);
        }

        Long dayCount = redisTemplate.opsForValue().increment(dayKey);
        if (dayCount != null && dayCount == 1L) {
            redisTemplate.expire(dayKey, 86400, TimeUnit.SECONDS);
        }
        if (dayCount != null && dayCount > 5L) {
            throw new BusinessException(UserErrorCode.RATE_LIMIT_EXCEEDED);
        }

        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.getStatus() != UserStatus.PENDING_VERIFICATION) {
                return;
            }

            /** DB 操作（delete + save）在 transaction 內 */
            String tokenValue = UUID.randomUUID().toString();
            String verificationCode = generateVerificationCode();
            transactionTemplate.executeWithoutResult(status -> {
                verificationTokenRepository.deleteByUserIdAndType(user.getId(), "EMAIL_VERIFICATION");

                VerificationToken verificationToken = new VerificationToken();
                verificationToken.setUserId(user.getId());
                verificationToken.setToken(tokenValue);
                verificationToken.setType("EMAIL_VERIFICATION");
                verificationToken.setExpiresAt(LocalDateTime.now().plusHours(24));
                verificationToken.setCreatedAt(LocalDateTime.now());
                verificationTokenRepository.save(verificationToken);
            });

            /** DB 已 commit，best-effort 發 MQ（失敗不影響重發結果） */
            saveEmailVerificationCode(user.getEmail(), verificationCode);
            // 新碼發出，重置失敗計數讓使用者可從新碼開始驗證
            try {
                redisTemplate.delete(RedisKeyConstant.getEmailVerifyCodeFailKey(user.getEmail()));
            } catch (Exception e) {
                log.warn("Redis 清除驗證碼失敗計數失敗（best-effort）: {}", e.getMessage(), e);
            }
            try {
                rabbitTemplate.convertAndSend(
                        UserRabbitMqConfig.EXCHANGE,
                        UserRabbitMqConfig.ROUTING_KEY_REGISTERED,
                        new UserRegisteredEvent(user.getId(), user.getEmail(), user.getNickname(), tokenValue,
                                verificationCode));
            } catch (Exception e) {
                log.warn("MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
            }
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
        return TokenVersionUtils.incrementVersion(currentVersion);
    }

    /**
     * IP 層級限流計數與檢查
     *
     * <p>沿用既有「每分鐘/每日」限流寫法：以 {@code INCR} 遞增計數，首次（count == 1）
     * 設定窗口 TTL，計數超過 {@code max} 即拋出 {@link UserErrorCode#RATE_LIMIT_EXCEEDED}。
     * {@code key} 為 null 時（無法解析 client IP）直接跳過，不做任何 Redis 操作。</p>
     *
     * @param key        限流 Redis Key；為 null 表示跳過限流
     * @param max        窗口內允許的最大次數（count > max 即拒絕）
     * @param ttlMinutes 限流窗口（分鐘）
     */
    private void checkIpRateLimit(String key, int max, long ttlMinutes) {
        if (key == null) {
            return;
        }
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, ttlMinutes, TimeUnit.MINUTES);
        }
        if (count != null && count > max) {
            throw new BusinessException(UserErrorCode.RATE_LIMIT_EXCEEDED);
        }
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private String generateVerificationCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    private void saveEmailVerificationCode(String email, String code) {
        try {
            redisTemplate.opsForValue().set(
                    RedisKeyConstant.getEmailVerifyCodeKey(email),
                    code,
                    RedisKeyConstant.EMAIL_VERIFY_CODE_TTL_MINUTES,
                    TimeUnit.MINUTES
            );
        } catch (Exception e) {
            log.warn("Redis 寫入驗證碼失敗（best-effort）: {}", e.getMessage(), e);
        }
    }
}
