package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.module.user.util.TokenHasher;
import dowob.xyz.blog.module.user.util.TokenVersionUtils;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.module.user.config.UserRabbitMqConfig;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.VerificationToken;
import dowob.xyz.blog.module.user.model.dto.response.LoginResult;
import dowob.xyz.blog.module.user.model.event.RegistrationAttemptOnExistingEmailEvent;
import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.model.event.UserRegisteredEvent;
import dowob.xyz.blog.module.user.redis.UserRedisAtomicOps;
import dowob.xyz.blog.module.user.repository.UserRepository;
import dowob.xyz.blog.module.user.repository.VerificationTokenRepository;
import dowob.xyz.blog.module.user.validation.UserNicknamePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
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

    /** Session 撤銷服務（重設密碼後撤銷所有既有 session，使被盜 Token 立即失效） */
    private final SessionRevoker sessionRevoker;

    /** Redis 原子操作（計數 + TTL、登入裝置數修剪） */
    private final UserRedisAtomicOps userRedisAtomicOps;

    /** 每位使用者最多同時保留的登入裝置（refresh token）數，超過時踢掉最舊的 */
    private static final int MAX_LOGIN_DEVICES = 3;

    /**
     * 用戶註冊
     *
     * <p>驗證用戶名與暱稱唯一性後建立帳號（PENDING_VERIFICATION 狀態），
     * 並發布 {@link UserRegisteredEvent} 至 RabbitMQ 以觸發驗證信發送。
     * 信箱已被註冊時一律回成功並通知原信箱主人，詳見
     * {@link #register(String, String, String, String, String)}。</p>
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
     * <h4>email 重複一律回成功（SEC-14／Q8，Yuan 2026-10-24 定案 (a)）</h4>
     * <p>email 已被註冊時，<b>不建立帳號、不回錯誤</b>，回應與新註冊相同的成功（controller 回
     * {@code ApiResponse<Void>}），消除「直接回「信箱已被註冊」（已停用的 A0106）」這種零成本的帳號列舉；並在回應之前（無交易）發布
     * {@link RegistrationAttemptOnExistingEmailEvent}，由 consumer 寄通知信給<b>原信箱主人</b>
     * （「有人嘗試用你的信箱註冊；若是你本人，請直接登入或使用忘記密碼」）。通知有 per-email 限流：
     * 同一信箱 {@link RedisKeyConstant#REGISTER_DUP_NOTICE_TTL_MINUTES} 分鐘（24 小時）內最多一封，
     * 名額以 Redis {@code SET NX EX} 原子取得，避免有人拿註冊端點對受害者灌信；限流狀態與 Redis 故障
     * 都不會影響回應（fail closed：取不到名額就不寄）。</p>
     *
     * <p>username／nickname 重複<b>維持明確錯誤碼</b>（{@link UserErrorCode#USERNAME_DUPLICATED}、
     * {@link UserErrorCode#NICKNAME_DUPLICATED}），產品上需要讓使用者換一個名稱。</p>
     *
     * <h4>殘餘風險（SEC-14 殘餘，Yuan 2026-11-04 決定接受）</h4>
     * <p>本機制<b>並未</b>讓 email 存在性完全無法被探測，只是把零成本的直接列舉降為需 2 次請求的副作用列舉：
     * email 為新時會建立 PENDING_VERIFICATION 帳號並佔用請求帶入的 username，email 已存在時不建帳號。
     * 攻擊者可 (a) {@code register(E, U1)} 後 {@code login(U1, P)}——已存在回 {@code USER_PASSWORD_ERROR}、
     * 新的回 {@code EMAIL_NOT_VERIFIED}；或 (b) 另用其他 email 再以 U1 註冊——已存在成功、新的回
     * {@code USERNAME_DUPLICATED}。每探測一個 email 需 2 次請求，受每 IP 每小時
     * {@link RedisKeyConstant#REGISTER_IP_MAX} 次的註冊限流減速。詳見 {@code ai-docs/findings.md} SEC-14。</p>
     *
     * <h4>檢查順序（降低直接洩漏 email 存在性）</h4>
     * <p>固定為 IP 限流 → 密碼雜湊 → username → nickname → email。username／nickname <b>必須先於</b> email：
     * 若先查 email，攻擊者帶一個已被佔用的 username 搭配目標 email——email 存在回「成功」、不存在回
     * {@code USERNAME_DUPLICATED}，回應差異就是 email 存在性的 oracle。先查 username／nickname 時，
     * 帶已佔用名稱的請求，結果與 email 是否存在無關。因此「email 與 username 都重複」回
     * {@code USERNAME_DUPLICATED} 且<b>不</b>寄通知信。</p>
     *
     * <h4>防時間側通道列舉</h4>
     * <p>新註冊會做 bcrypt 雜湊（約數十到數百毫秒），若 email 已存在的路徑省掉雜湊，回應時間就會洩漏
     * email 是否存在。因此密碼雜湊（{@link PasswordEncoder#encode}）放在所有存在性檢查<b>之前</b>：新註冊、
     * email 重複、username／nickname 重複三條路徑都付出同量的雜湊成本，bcrypt 主導耗時。
     * <b>已知殘餘差異</b>：新註冊路徑多一次 DB 寫入交易（毫秒級），重複路徑多一次 Redis 與 MQ 呼叫；
     * 這些相對 bcrypt 都是次要項，不另做人工延遲。</p>
     *
     * <h4>併發註冊（RACE-02／RACE-03）</h4>
     * <p>{@code exists*}／{@code findByEmail} 先查只是快速路徑；兩個併發請求可能都先通過先查，
     * 此時由 DB 唯一約束兜底。寫入撞到 {@code uq_users_nickname}／{@code users_username_key}
     * 時依約束名稱轉成 {@link UserErrorCode#NICKNAME_DUPLICATED}／{@link UserErrorCode#USERNAME_DUPLICATED}；
     * 撞到 {@code users_email_key}（併發落敗）時與「依序第二次註冊」一致——靜默成功並通知贏家
     * （先重查 username／nickname，維持與依序註冊相同的檢查順序）。不認得的約束或非約束錯誤原樣往外拋，
     * 不會把真正的系統錯誤誤標成使用者可修正的重複。
     * <b>已知限制</b>：若多條約束同時違反（例如 email 與暱稱同時撞到），PostgreSQL 只回報其中一條，
     * 順序不是契約（通常依索引建立先後，{@code pg_dump} 還原等操作後可能改變），因此併發下同時違反多條約束時的
     * 結果不保證與依序註冊相同；只保證「單一約束違反」時一致，且任何組合都不會回 500。</p>
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

        requireNicknameNotReserved(nickname);

        /** 雜湊先於任何存在性檢查：所有路徑付出同量成本，防時間側通道列舉（見方法 JavaDoc） */
        String passwordHash = passwordEncoder.encode(password);

        /** username／nickname 必須先於 email 檢查，否則回應差異會洩漏 email 存在性（見方法 JavaDoc） */
        requireUsernameAndNicknameAvailable(username, nickname);

        Optional<User> existingOwner = userRepository.findByEmail(email);
        if (existingOwner.isPresent()) {
            notifyOwnerOfRegistrationAttempt(existingOwner.get());
            return;
        }

        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setEmail(email);
        user.setUsername(username);
        user.setNickname(nickname);
        user.setPasswordHash(passwordHash);
        user.setRole(Role.USER);
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.setTokenVersion("v1");

        /**
         * DB 操作（save user）在 transaction 內。驗證連結 token 不在此建立（A1）：由寄信 consumer 於寄信當下
         * 產生並只存 SHA-256，這裡只發事件。
         * users 的 INSERT 是交易內第一個寫入：撞到唯一約束時例外立即中止交易（之後不再執行任何 SQL），
         * 在交易之外轉成業務結果（RACE-02／RACE-03／Q8）。
         */
        String verificationCode = generateVerificationCode();
        User savedUser;
        try {
            savedUser = transactionTemplate.execute(status -> userRepository.save(user));
        } catch (RuntimeException e) {
            if (isEmailConflict(e)) {
                log.info("註冊併發落敗：寫入撞到 {}，改走重複 email 路徑（靜默成功並通知原信箱主人）",
                        UniqueConstraintViolations.USERS_EMAIL);
                requireUsernameAndNicknameAvailable(username, nickname);
                userRepository.findByEmail(email).ifPresentOrElse(
                        this::notifyOwnerOfRegistrationAttempt,
                        () -> log.warn("註冊撞 email 約束但查無原帳號（可能剛被刪除），不發通知"));
                return;
            }
            throw translateRegistrationConflict(e);
        }

        /** DB 已 commit，best-effort 發 MQ（失敗不影響註冊結果） */
        saveEmailVerificationCode(savedUser.getEmail(), verificationCode);
        try {
            rabbitTemplate.convertAndSend(
                    UserRabbitMqConfig.EXCHANGE,
                    UserRabbitMqConfig.ROUTING_KEY_REGISTERED,
                    new UserRegisteredEvent(UUID.randomUUID(), savedUser.getUuid()));
        } catch (Exception e) {
            log.warn("MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
        }
    }

    /**
     * 拒絕保留暱稱（以「已刪除使用者」開頭，防假冒 DATA-02 的已刪除帳號），與 email 是否存在無關。
     *
     * @param nickname 暱稱
     * @throws BusinessException 暱稱為保留暱稱（{@link CommonErrorCode#PARAM_VALID_ERROR}）
     */
    private void requireNicknameNotReserved(String nickname) {
        if (UserNicknamePolicy.isReserved(nickname)) {
            throw new BusinessException(CommonErrorCode.PARAM_VALID_ERROR);
        }
    }

    /**
     * 確認 username 與 nickname 皆未被使用，否則拋出明確的重複錯誤（先 username 後 nickname）。
     *
     * @param username 用戶名
     * @param nickname 暱稱
     * @throws BusinessException username 或 nickname 已被使用
     */
    private void requireUsernameAndNicknameAvailable(String username, String nickname) {
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(UserErrorCode.USERNAME_DUPLICATED);
        }
        if (userRepository.existsByNickname(nickname)) {
            throw new BusinessException(UserErrorCode.NICKNAME_DUPLICATED);
        }
    }

    /**
     * 通知原信箱主人「有人嘗試用你的信箱註冊」（best-effort，絕不影響註冊回應）。
     *
     * <p>先以 Redis {@code SET NX EX} 取得該信箱的通知名額（{@link RedisKeyConstant#REGISTER_DUP_NOTICE_TTL_MINUTES}
     * 分鐘內一次）；取不到名額（窗口內已通知過）或 Redis 故障時不寄（fail closed，避免變成灌信管道）。
     * 取得名額後發布 {@link RegistrationAttemptOnExistingEmailEvent}；MQ 發送失敗則釋放名額，讓之後的嘗試仍有機會通知。
     * 限流以<b>資料庫儲存的 email</b>為鍵，不受請求輸入的大小寫差異影響。事件只帶原帳號資料，
     * 不帶嘗試者提交的任何欄位。日誌不記錄 email，只記 userUuid。</p>
     *
     * @param owner 已佔用該 email 的既有帳號
     */
    private void notifyOwnerOfRegistrationAttempt(User owner) {
        String noticeKey = RedisKeyConstant.getRegisterDuplicateNoticeKey(owner.getEmail());
        boolean acquired;
        try {
            acquired = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                    noticeKey, "1", RedisKeyConstant.REGISTER_DUP_NOTICE_TTL_MINUTES, TimeUnit.MINUTES));
        } catch (Exception e) {
            log.warn("重複 email 註冊通知限流檢查失敗，為避免灌信不寄通知（fail closed）: {}", e.getMessage(), e);
            return;
        }
        if (!acquired) {
            log.info("重複 email 註冊通知已在窗口內寄過，略過 - userUuid={}", owner.getUuid());
            return;
        }
        try {
            rabbitTemplate.convertAndSend(
                    UserRabbitMqConfig.EXCHANGE,
                    UserRabbitMqConfig.ROUTING_KEY_REGISTRATION_ATTEMPT,
                    new RegistrationAttemptOnExistingEmailEvent(UUID.randomUUID(), owner.getUuid()));
        } catch (Exception e) {
            log.warn("MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
            try {
                redisTemplate.delete(noticeKey);
            } catch (Exception releaseFailure) {
                log.warn("釋放重複 email 註冊通知名額失敗（best-effort）: {}", releaseFailure.getMessage(), releaseFailure);
            }
        }
    }

    /**
     * 判斷寫入失敗是否為 users 的 email 唯一約束違反（併發註冊落敗）。
     *
     * @param failure 交易內拋出的例外
     * @return 撞到 {@code users_email_key} 為 true
     */
    private boolean isEmailConflict(RuntimeException failure) {
        return UniqueConstraintViolations.constraintNameOf(failure)
                .filter(UniqueConstraintViolations.USERS_EMAIL::equals)
                .isPresent();
    }

    /**
     * 將註冊寫入時的例外轉成業務例外。
     *
     * <p>只轉換「users 的 username／nickname 唯一約束」；email 唯一約束由 {@code register} 另走靜默成功路徑
     * （Q8），不經此處。撞到其他約束（例如 {@code verification_tokens} 的 token 唯一約束）或非約束錯誤時
     * 原樣回傳，維持原本的錯誤處理（500），不把真正的系統錯誤誤標成使用者可修正的重複。多條約束同時違反時
     * 只會看到 PostgreSQL 回報的那一條，順序見 {@link #register(String, String, String, String, String)} 的「已知限制」。</p>
     *
     * @param failure 交易內拋出的例外
     * @return 對應的 {@link BusinessException}；不認得的例外則原樣回傳
     */
    private RuntimeException translateRegistrationConflict(RuntimeException failure) {
        String constraint = UniqueConstraintViolations.constraintNameOf(failure).orElse("");
        UserErrorCode errorCode = switch (constraint) {
            case UniqueConstraintViolations.USERS_USERNAME -> UserErrorCode.USERNAME_DUPLICATED;
            case UniqueConstraintViolations.USERS_NICKNAME -> UserErrorCode.NICKNAME_DUPLICATED;
            default -> null;
        };
        if (errorCode == null) {
            return failure;
        }
        log.info("註冊併發落敗：寫入撞到唯一約束 {}，回 {}", constraint, errorCode.getCode());
        return new BusinessException(errorCode);
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

        String loginFailKey = RedisKeyConstant.getLoginFailKey(user.getUuid());
        String failCountStr = redisTemplate.opsForValue().get(loginFailKey);
        if (failCountStr != null && Integer.parseInt(failCountStr) >= RedisKeyConstant.LOGIN_FAIL_MAX) {
            throw new BusinessException(UserErrorCode.ACCOUNT_LOCKED);
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            userRedisAtomicOps.incrementWithTtl(loginFailKey,
                    Duration.ofMinutes(RedisKeyConstant.LOGIN_FAIL_TTL_MINUTES));
            throw new BusinessException(UserErrorCode.USER_PASSWORD_ERROR);
        }

        redisTemplate.delete(loginFailKey);

        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            throw new BusinessException(UserErrorCode.EMAIL_NOT_VERIFIED);
        }

        if (user.getStatus() == UserStatus.BANNED || user.getStatus() == UserStatus.DELETED) {
            throw new BusinessException(UserErrorCode.ACCOUNT_SUSPENDED);
        }

        String accessToken = jwtService.generateAccessToken(user.getUuid(), user.getRole().name(), user.getTokenVersion());
        String refreshToken = jwtService.generateRefreshToken(user.getUuid());

        /** auth hash 以 uuid 為鍵；身分只以 uuid 識別，不再寫入使用者主鍵 */
        String authKey = RedisKeyConstant.getUserAuthKey(user.getUuid());
        /** 一次 putAll 寫入三個欄位（與 AuthStateLoader 回填一致，避免寫到一半留下半殘 hash） */
        redisTemplate.opsForHash().putAll(authKey, Map.of(
                RedisKeyConstant.FIELD_VERSION, user.getTokenVersion(),
                RedisKeyConstant.FIELD_STATUS, user.getStatus().name(),
                RedisKeyConstant.FIELD_ROLE, user.getRole().name()));
        redisTemplate.expire(authKey, RedisKeyConstant.USER_AUTH_TTL_DAYS, TimeUnit.DAYS);

        String refreshKey = RedisKeyConstant.getUserRefreshKey(user.getUuid());
        userRedisAtomicOps.registerRefreshToken(refreshKey, refreshToken, System.currentTimeMillis(),
                MAX_LOGIN_DEVICES, Duration.ofDays(7));

        return new LoginResult(accessToken, refreshToken);
    }

    /**
     * 用戶登出
     *
     * <p>從 ZSet 中移除指定的 Refresh Token，使該裝置立即失效。
     * 若 refreshToken 為空，刪除整個 ZSet 以登出所有裝置。</p>
     *
     * @param userUuid     用戶 UUID
     * @param refreshToken 要撤銷的 Refresh Token（可為 null，表示登出所有裝置）
     */
    public void logout(UUID userUuid, String refreshToken) {
        String refreshKey = RedisKeyConstant.getUserRefreshKey(userUuid);
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
                .findByTokenHashAndType(TokenHasher.sha256Hex(token), "EMAIL_VERIFICATION")
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
            userRedisAtomicOps.incrementWithTtl(failKey,
                    Duration.ofMinutes(RedisKeyConstant.EMAIL_VERIFY_CODE_TTL_MINUTES));
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

        long minCount = userRedisAtomicOps.incrementWithTtl(minKey, Duration.ofSeconds(60));
        if (minCount > 1L) {
            throw new BusinessException(UserErrorCode.RATE_LIMIT_EXCEEDED);
        }

        long dayCount = userRedisAtomicOps.incrementWithTtl(dayKey, Duration.ofSeconds(86400));
        if (dayCount > 5L) {
            throw new BusinessException(UserErrorCode.RATE_LIMIT_EXCEEDED);
        }

        userRepository.findByEmail(email).ifPresent(user -> {
            /** token 由寄信 consumer 於寄信當下產生並只存雜湊（A1），這裡不碰 verification_tokens，只發事件（best-effort） */
            try {
                rabbitTemplate.convertAndSend(
                        UserRabbitMqConfig.EXCHANGE,
                        UserRabbitMqConfig.ROUTING_KEY_PASSWORD_RESET,
                        new UserPasswordResetRequestedEvent(UUID.randomUUID(), user.getUuid()));
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

        long minCount = userRedisAtomicOps.incrementWithTtl(minKey, Duration.ofSeconds(60));
        if (minCount > 1L) {
            throw new BusinessException(UserErrorCode.RATE_LIMIT_EXCEEDED);
        }

        long dayCount = userRedisAtomicOps.incrementWithTtl(dayKey, Duration.ofSeconds(86400));
        if (dayCount > 5L) {
            throw new BusinessException(UserErrorCode.RATE_LIMIT_EXCEEDED);
        }

        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.getStatus() != UserStatus.PENDING_VERIFICATION) {
                return;
            }

            /** 舊 token 的刪除與新 token 的產生都由寄信 consumer 在寄信當下於同一交易完成（A1），這裡只備好 Redis 驗證碼並發事件 */
            String verificationCode = generateVerificationCode();
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
                        new UserRegisteredEvent(UUID.randomUUID(), user.getUuid()));
            } catch (Exception e) {
                log.warn("MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * 重設密碼
     *
     * <p>驗證密碼重設 Token 有效後，更新密碼雜湊並遞增 tokenVersion，隨即撤銷該用戶
     * 所有既有 session（清除 Redis auth hash 與 refresh ZSet），使被盜的 Access Token
     * 與 Refresh Token 立即失效——這是帳號救援的核心安全保證。最後刪除已使用的重設 Token。</p>
     *
     * <p>注意：僅遞增 DB tokenVersion 不足以立即失效，因 {@code JwtAuthenticationFilter}
     * 命中 Redis 快取時讀到的仍是舊版本；故必須主動清除 Redis 快取。</p>
     *
     * @param token       密碼重設 Token 字串
     * @param newPassword 新的明文密碼
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        VerificationToken resetToken = verificationTokenRepository
                .findByTokenHashAndType(TokenHasher.sha256Hex(token), "PASSWORD_RESET")
                .orElseThrow(() -> new BusinessException(UserErrorCode.TOKEN_INVALID));

        if (resetToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(UserErrorCode.TOKEN_INVALID);
        }

        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion(incrementVersion(user.getTokenVersion()));
        userRepository.save(user);

        sessionRevoker.revokeAllSessions(user.getUuid());

        /** 刪除該使用者所有重設 token（不只用掉的這筆），避免晚到的舊寄信事件寄出仍有效的舊 token（ARCH-24） */
        verificationTokenRepository.deleteByUserIdAndType(user.getId(), "PASSWORD_RESET");
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
        long count = userRedisAtomicOps.incrementWithTtl(key, Duration.ofMinutes(ttlMinutes));
        if (count > max) {
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
