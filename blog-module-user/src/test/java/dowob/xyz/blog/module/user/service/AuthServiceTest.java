package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.VerificationToken;
import dowob.xyz.blog.module.user.model.dto.response.LoginResult;
import dowob.xyz.blog.module.user.config.UserRabbitMqConfig;
import dowob.xyz.blog.module.user.model.event.RegistrationAttemptOnExistingEmailEvent;
import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.model.event.UserRegisteredEvent;
import dowob.xyz.blog.module.user.repository.UserRepository;
import dowob.xyz.blog.module.user.repository.VerificationTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import dowob.xyz.blog.module.user.redis.UserRedisAtomicOps;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuthService 單元測試
 *
 * <p>
 * 使用 Mockito 隔離所有外部依賴（Repository、JwtService、PasswordEncoder、Redis、RabbitMQ），
 * 驗證 AuthService 的核心業務邏輯：註冊、登入、登出、電子信箱驗證、忘記密碼與重設密碼。
 * </p>
 *
 * @author Yuan
 * @version 2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    /** Mock：用戶資料存取 */
    @Mock
    private UserRepository userRepository;

    /** Mock：JWT 服務 */
    @Mock
    private JwtService jwtService;

    /** Mock：密碼加密器 */
    @Mock
    private PasswordEncoder passwordEncoder;

    /** Mock：Redis 操作模板 */
    @Mock
    private StringRedisTemplate redisTemplate;

    /** Mock：Redis Hash 操作 */
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    /** Mock：Redis Value 操作 */
    @Mock
    private ValueOperations<String, String> valueOperations;

    /** Mock：RabbitMQ 發送模板 */
    @Mock
    private RabbitTemplate rabbitTemplate;

    /** Mock：驗證 Token 資料存取 */
    @Mock
    private VerificationTokenRepository verificationTokenRepository;

    /** Mock：Spring 宣告式事務模板（TransactionTemplate） */
    @Mock
    private TransactionTemplate transactionTemplate;

    /** Mock：Session 撤銷服務 */
    @Mock
    private SessionRevoker sessionRevoker;

    /** Mock：Redis 原子操作（計數 + TTL、登入裝置數修剪；Lua 行為由 UserRedisAtomicOpsIntegrationTest 以真 Redis 驗證） */
    @Mock
    private UserRedisAtomicOps userRedisAtomicOps;

    /** 受測物件，由 Mockito 自動注入所有 @Mock */
    @InjectMocks
    private AuthService authService;

    /** 測試用電子信箱 */
    private static final String TEST_EMAIL = "test@example.com";

    /** 測試用使用者 uuid（JWT subject 與所有 Redis key 的鍵） */
    private static final UUID USER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** 測試用密碼 */
    private static final String TEST_PASSWORD = "password123";

    /** 測試用用戶名 */
    private static final String TEST_USERNAME = "testuser";

    /** 測試用暱稱 */
    private static final String TEST_NICKNAME = "testUser";

    /** 測試用 Access Token 字串 */
    private static final String MOCK_ACCESS_TOKEN = "mock.access.token";

    /** 測試用 Refresh Token 字串 */
    private static final String MOCK_REFRESH_TOKEN = "mock.refresh.token";

    @SuppressWarnings("unchecked")
    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        /** TransactionTemplate mock：直接執行回調 */
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        doAnswer(inv -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action = inv.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    /* =========================================================================
       register 測試
       ========================================================================= */

    /**
     * 驗證：使用全新 email 與 nickname 註冊，應成功儲存用戶並發布 RabbitMQ 事件。
     */
    @Test
    @DisplayName("register → 新 email 與 nickname → 應呼叫 save 儲存用戶")
    void register_withNewEmailAndNickname_shouldSaveUser() {
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(false);
        when(userRepository.existsByNickname(TEST_NICKNAME)).thenReturn(false);
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        User savedUser = buildActiveUser();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(verificationTokenRepository.save(any(VerificationToken.class))).thenReturn(new VerificationToken());

        authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME);

        verify(userRepository).save(any(User.class));
    }

    /**
     * 驗證（SEC-14／Q8）：使用已存在的 email 註冊，回應與新註冊相同——正常返回、不拋錯、不建立帳號。
     */
    @Test
    @DisplayName("register → 重複 email → 正常返回（與新註冊相同的成功），不建立帳號也不寫 token")
    void register_withDuplicateEmail_returnsNormallyWithoutCreatingAccount() {
        stubExistingEmailOwner();

        assertThatCode(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME))
                .doesNotThrowAnyException();

        verify(userRepository, never()).save(any());
        verify(transactionTemplate, never()).execute(any());
        verifyNoInteractions(verificationTokenRepository);
    }

    /**
     * 驗證：使用已存在的 username 註冊，應拋出 USERNAME_DUPLICATED BusinessException。
     */
    @Test
    @DisplayName("register → 重複 username → 應拋出 USERNAME_DUPLICATED BusinessException")
    void register_withDuplicateUsername_shouldThrow() {
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(false);
        when(userRepository.existsByUsername(TEST_USERNAME)).thenReturn(true);

        assertThatThrownBy(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.USERNAME_DUPLICATED.getCode()));

        verify(userRepository, never()).save(any());
    }

    /**
     * 驗證：使用已存在的 nickname 註冊，應拋出 BusinessException。
     */
    @Test
    @DisplayName("register → 重複 nickname → 應拋出 BusinessException")
    void register_withDuplicateNickname_shouldThrowBusinessException() {
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(false);
        when(userRepository.existsByNickname(TEST_NICKNAME)).thenReturn(true);

        assertThatThrownBy(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
    }

    /**
     * 驗證：暱稱以保留前綴「已刪除使用者」開頭（含全形空白與 NFKC 變體）→ 拒絕為參數驗證錯誤，
     * 且不進入雜湊、存在性檢查與寫入（防假冒 DATA-02 的已刪除帳號暱稱）。
     */
    @Test
    @DisplayName("register → 暱稱以「已刪除使用者」開頭（含全形空白變體）→ PARAM_VALID_ERROR，不查 DB 不建帳號")
    void register_withReservedDeletedNickname_throwsParamValidError() {
        for (String nickname : new String[]{"已刪除使用者#1a2b3c4d", "已刪除使用者　#1a2b3c4d", "  已刪除使用者"}) {
            assertThatThrownBy(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, nickname))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                            .isEqualTo(dowob.xyz.blog.common.api.errorcode.CommonErrorCode.PARAM_VALID_ERROR.getCode()));
        }

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).existsByNickname(anyString());
    }

    /* ---- RACE-02／RACE-03：併發註冊撞到唯一約束，轉成與依序第二次註冊相同的業務錯誤碼 ---- */

    /**
     * 建立與 PostgreSQL JDBC 驅動訊息格式相同的唯一約束違反例外（Spring 翻譯後的 DuplicateKeyException）。
     *
     * @param constraint 約束名稱
     * @return 模擬寫入 users 時撞到唯一約束的例外
     */
    private static DuplicateKeyException uniqueViolationOn(String constraint) {
        return new DuplicateKeyException("insert users failed", new java.sql.SQLException(
                "ERROR: duplicate key value violates unique constraint \"" + constraint + "\"\n"
                        + "  Detail: Key already exists.", "23505"));
    }

    /**
     * 讓寫入交易拋出指定例外（模擬 users INSERT 在交易內撞到約束）。
     *
     * @param failure 要拋出的例外
     */
    private void failTransactionWith(RuntimeException failure) {
        doThrow(failure).when(transactionTemplate).execute(any());
    }

    /**
     * 驗證（RACE-03 + Q8）：先查通過、寫入時撞到 email 唯一約束（併發落敗）→ 與依序第二次註冊一致：
     * 靜默成功，並通知原信箱主人（贏的那一方）；不發驗證信事件。
     */
    @Test
    @DisplayName("register → 寫入撞 users_email_key（併發落敗）→ 靜默成功並通知原信箱主人，不發驗證信事件")
    void register_emailUniqueViolationOnInsert_returnsNormallyAndNotifiesOwner() {
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.empty(), Optional.of(buildEmailOwner()));
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        failTransactionWith(uniqueViolationOn("users_email_key"));

        assertThatCode(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME))
                .doesNotThrowAnyException();

        verify(rabbitTemplate, never()).convertAndSend(anyString(), eq(UserRabbitMqConfig.ROUTING_KEY_REGISTERED),
                any(UserRegisteredEvent.class));
        RegistrationAttemptOnExistingEmailEvent event = captureSingleRegistrationAttemptEvent();
        assertThat(event.userUuid()).isEqualTo(OWNER_UUID);
    }

    /**
     * 驗證：併發落敗於 email 約束時，若 username／nickname 在這段期間也已被佔用，
     * 結果應與依序註冊（先查 username、nickname）一致——回明確的重複錯誤，而不是靜默成功。
     */
    @Test
    @DisplayName("register → 撞 users_email_key 且 username 同時已被佔用 → 回 USERNAME_DUPLICATED（與依序註冊的檢查順序一致）")
    void register_emailUniqueViolationAndUsernameNowTaken_throwsUsernameDuplicated() {
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        when(userRepository.existsByUsername(TEST_USERNAME)).thenReturn(false, true);
        failTransactionWith(uniqueViolationOn("users_email_key"));

        assertThatThrownBy(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UserErrorCode.USERNAME_DUPLICATED.getCode()));

        verifyNoInteractions(rabbitTemplate);
    }

    /**
     * 驗證：撞 email 約束後查不到原帳號（極端情況：剛好被硬刪除）→ 仍靜默成功，不發任何事件、不拋錯。
     */
    @Test
    @DisplayName("register → 撞 users_email_key 但查不到原帳號 → 仍正常返回，不發任何事件")
    void register_emailUniqueViolationButOwnerGone_returnsNormallyWithoutEvent() {
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.empty());
        failTransactionWith(uniqueViolationOn("users_email_key"));

        assertThatCode(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME))
                .doesNotThrowAnyException();

        verifyNoInteractions(rabbitTemplate);
    }

    /**
     * 驗證：寫入時撞到 username 唯一約束 → USERNAME_DUPLICATED。
     */
    @Test
    @DisplayName("register → 寫入撞 users_username_key（併發落敗）→ 拋 USERNAME_DUPLICATED")
    void register_usernameUniqueViolationOnInsert_throwsUsernameDuplicated() {
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        failTransactionWith(uniqueViolationOn("users_username_key"));

        assertThatThrownBy(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UserErrorCode.USERNAME_DUPLICATED.getCode()));

        verifyNoInteractions(rabbitTemplate);
    }

    /**
     * 驗證：寫入時撞到 nickname 唯一約束 → NICKNAME_DUPLICATED。
     */
    @Test
    @DisplayName("register → 寫入撞 uq_users_nickname（併發落敗）→ 拋 NICKNAME_DUPLICATED")
    void register_nicknameUniqueViolationOnInsert_throwsNicknameDuplicated() {
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        failTransactionWith(uniqueViolationOn("uq_users_nickname"));

        assertThatThrownBy(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UserErrorCode.NICKNAME_DUPLICATED.getCode()));

        verifyNoInteractions(rabbitTemplate);
    }

    /**
     * 驗證：被包在其他例外內（Spring Data JDBC 的 DbActionExecutionException 形式）也能判讀。
     */
    @Test
    @DisplayName("register → 唯一約束例外被包在一般 RuntimeException 內也能轉成業務錯誤碼")
    void register_wrappedUniqueViolation_stillTranslated() {
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        failTransactionWith(new RuntimeException("Failed to execute DbAction.InsertRoot",
                uniqueViolationOn("users_username_key")));

        assertThatThrownBy(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UserErrorCode.USERNAME_DUPLICATED.getCode()));
    }

    /**
     * 驗證：email 約束例外被包在其他例外內也能判讀為「email 重複」→ 靜默成功（Q8）。
     */
    @Test
    @DisplayName("register → email 唯一約束例外被包在一般 RuntimeException 內也能判讀為重複 email（靜默成功）")
    void register_wrappedEmailUniqueViolation_stillTreatedAsDuplicateEmail() {
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.empty(), Optional.of(buildEmailOwner()));
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        failTransactionWith(new RuntimeException("Failed to execute DbAction.InsertRoot",
                uniqueViolationOn("users_email_key")));

        assertThatCode(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME))
                .doesNotThrowAnyException();

        assertThat(captureSingleRegistrationAttemptEvent().userUuid()).isEqualTo(OWNER_UUID);
    }

    /**
     * 驗證：不認得的約束（或非約束錯誤）不被吞掉，原例外照常往外拋（維持 500，不錯誤標示成使用者可修正的重複）。
     */
    @Test
    @DisplayName("register → 撞到不認得的約束，或非唯一約束錯誤 → 原例外照常拋出，不轉成業務錯誤")
    void register_unknownConstraintOrOtherFailure_rethrowsOriginal() {
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        DuplicateKeyException unknown = uniqueViolationOn("verification_tokens_token_key");
        failTransactionWith(unknown);

        assertThatThrownBy(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME))
                .isSameAs(unknown);

        IllegalStateException other = new IllegalStateException("db down");
        failTransactionWith(other);

        assertThatThrownBy(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME))
                .isSameAs(other);
    }

    /* ---- SEC-14／Q8：email 重複一律回成功並通知原信箱主人 ---- */

    /** 原信箱主人（既有帳號）的 uuid，刻意與 USER_UUID 不同，也與任何 id 不同構 */
    private static final UUID OWNER_UUID = UUID.fromString("aaaaaaaa-1111-4222-8333-444444444444");

    /** 原信箱主人在資料庫中儲存的 email（與請求輸入的字面值不同，驗證通知用的是儲存值） */
    private static final String OWNER_EMAIL = "Stored.Owner@example.com";

    private User buildEmailOwner() {
        User owner = new User();
        owner.setId(900L);
        owner.setUuid(OWNER_UUID);
        owner.setEmail(OWNER_EMAIL);
        owner.setUsername("owneruser");
        owner.setNickname("OwnerNick");
        owner.setStatus(UserStatus.ACTIVE);
        return owner;
    }

    /** 讓 TEST_EMAIL 已存在（先查階段就命中），且通知限流可取得名額。 */
    private void stubExistingEmailOwner() {
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(buildEmailOwner()));
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
    }

    /** 斷言只發出一個「重複信箱註冊」事件並回傳之；路由必須是專屬 routing key。 */
    private RegistrationAttemptOnExistingEmailEvent captureSingleRegistrationAttemptEvent() {
        ArgumentCaptor<RegistrationAttemptOnExistingEmailEvent> captor =
                ArgumentCaptor.forClass(RegistrationAttemptOnExistingEmailEvent.class);
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(UserRabbitMqConfig.EXCHANGE), eq(UserRabbitMqConfig.ROUTING_KEY_REGISTRATION_ATTEMPT),
                captor.capture());
        return captor.getValue();
    }

    /**
     * 驗證：email 重複 → 發布 RegistrationAttemptOnExistingEmailEvent，內容是原信箱主人的資料
     * （儲存的 email／nickname／uuid），不含嘗試者提交的 username、nickname、密碼。
     */
    @Test
    @DisplayName("register → 重複 email → 發布通知事件，內容為原信箱主人資料（uuid／儲存的 email／暱稱），eventId 非 null")
    void register_withDuplicateEmail_publishesNoticeEventAboutOwner() {
        stubExistingEmailOwner();

        authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME);

        RegistrationAttemptOnExistingEmailEvent event = captureSingleRegistrationAttemptEvent();
        assertThat(event.eventId()).isNotNull();
        assertThat(event.userUuid()).isEqualTo(OWNER_UUID);
        assertThat(event.toString()).as("事件不含 email／暱稱（ARCH-24），也不含嘗試者提交的資料")
                .doesNotContain(OWNER_EMAIL).doesNotContain("OwnerNick")
                .doesNotContain(TEST_USERNAME).doesNotContain(TEST_NICKNAME).doesNotContain(TEST_PASSWORD);
        verify(rabbitTemplate, never()).convertAndSend(anyString(), eq(UserRabbitMqConfig.ROUTING_KEY_REGISTERED),
                any(UserRegisteredEvent.class));
    }

    /**
     * 驗證：通知限流——以「原信箱主人儲存的 email」為鍵，24 小時窗口，原子地 SET NX EX。
     */
    @Test
    @DisplayName("register → 重複 email → 以 setIfAbsent(rate:register-dup-notice:{儲存的email}, 1440 分鐘) 取得通知名額")
    void register_withDuplicateEmail_acquiresPerEmailNoticeSlot() {
        stubExistingEmailOwner();

        authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME);

        verify(valueOperations).setIfAbsent(
                eq(RedisKeyConstant.getRegisterDuplicateNoticeKey(OWNER_EMAIL)), anyString(),
                eq(RedisKeyConstant.REGISTER_DUP_NOTICE_TTL_MINUTES), eq(TimeUnit.MINUTES));
    }

    /**
     * 驗證：窗口內已寄過（名額被占）→ 仍回成功（不洩漏限流狀態），但不再發事件。
     */
    @Test
    @DisplayName("register → 重複 email 且窗口內已通知過 → 仍正常返回但不再發通知事件（防灌信）")
    void register_withDuplicateEmail_noticeAlreadySentInWindow_returnsNormallyWithoutEvent() {
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(buildEmailOwner()));
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        assertThatCode(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME))
                .doesNotThrowAnyException();

        verifyNoInteractions(rabbitTemplate);
    }

    /**
     * 驗證：限流用的 Redis 失效 → 保守起見不寄（fail closed，避免 Redis 故障時變成灌信管道），
     * 但註冊回應仍與成功一致。
     */
    @Test
    @DisplayName("register → 重複 email 且 Redis 限流故障 → 不發通知事件（fail closed），仍正常返回")
    void register_withDuplicateEmail_redisFailure_failsClosedWithoutEvent() {
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(buildEmailOwner()));
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("redis down"));

        assertThatCode(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME))
                .doesNotThrowAnyException();

        verifyNoInteractions(rabbitTemplate);
    }

    /**
     * 驗證：MQ 發送失敗是 best-effort，不影響註冊回應；並歸還通知名額，讓下一次嘗試仍有機會通知。
     */
    @Test
    @DisplayName("register → 重複 email 且 MQ 發送失敗 → 仍正常返回，並釋放通知名額")
    void register_withDuplicateEmail_mqFailure_returnsNormallyAndReleasesSlot() {
        stubExistingEmailOwner();
        doThrow(new RuntimeException("mq down")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(RegistrationAttemptOnExistingEmailEvent.class));

        assertThatCode(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME))
                .doesNotThrowAnyException();

        verify(redisTemplate).delete(RedisKeyConstant.getRegisterDuplicateNoticeKey(OWNER_EMAIL));
    }

    /**
     * 驗證（時間側通道）：新註冊與重複 email 兩條路徑都呼叫 passwordEncoder.encode，
     * 避免「重複路徑省掉 bcrypt」讓回應時間洩漏 email 是否存在。
     */
    @Test
    @DisplayName("register → 新註冊與重複 email 兩條路徑都恰好呼叫一次 passwordEncoder.encode（等時，防時間側通道列舉）")
    void register_bothPathsEncodePassword_toEqualizeTiming() {
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        when(userRepository.save(any(User.class))).thenReturn(buildActiveUser());
        when(verificationTokenRepository.save(any(VerificationToken.class))).thenReturn(new VerificationToken());

        authService.register("brand-new@example.com", TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME);
        verify(passwordEncoder, times(1)).encode(TEST_PASSWORD);

        reset(passwordEncoder);
        stubExistingEmailOwner();
        authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME);
        verify(passwordEncoder, times(1)).encode(TEST_PASSWORD);
    }

    /**
     * 驗證：hash 在任何「是否存在」判斷之前完成——所以重複 username／nickname 的拒絕路徑也不會比其他路徑快，
     * 不會因為提早返回而形成另一個可量測的時間差。
     */
    @Test
    @DisplayName("register → passwordEncoder.encode 發生在 email／username／nickname 存在性檢查之前")
    void register_encodesPasswordBeforeAnyExistenceCheck() {
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        stubExistingEmailOwner();

        authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME);

        org.mockito.InOrder order = inOrder(passwordEncoder, userRepository);
        order.verify(passwordEncoder).encode(TEST_PASSWORD);
        order.verify(userRepository).existsByUsername(TEST_USERNAME);
        order.verify(userRepository).findByEmail(TEST_EMAIL);
    }

    /**
     * 驗證檢查順序（防洩漏 email 存在性）：email 重複且 username 也重複 → 回 USERNAME_DUPLICATED，
     * 不發任何通知。
     *
     * <p>判定：若先查 email，攻擊者拿一個已佔用的 username 搭配目標 email——email 存在回「成功」、
     * 不存在回 USERNAME_DUPLICATED，回應差異就成了 email 存在性的 oracle。先查 username／nickname，
     * 帶著已佔用 username 的請求結果與 email 是否存在無關，不直接洩漏。</p>
     */
    @Test
    @DisplayName("register → email 重複且 username 也重複 → 回 USERNAME_DUPLICATED（username 先查，避免直接洩漏 email 存在性），不通知")
    void register_duplicateEmailAndDuplicateUsername_throwsUsernameDuplicatedWithoutNotice() {
        stubExistingEmailOwner();
        when(userRepository.existsByUsername(TEST_USERNAME)).thenReturn(true);

        assertThatThrownBy(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UserErrorCode.USERNAME_DUPLICATED.getCode()));

        verifyNoInteractions(rabbitTemplate);
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    /**
     * 驗證檢查順序：email 重複且 nickname 也重複 → NICKNAME_DUPLICATED，不通知（理由同上）。
     */
    @Test
    @DisplayName("register → email 重複且 nickname 也重複 → 回 NICKNAME_DUPLICATED，不通知")
    void register_duplicateEmailAndDuplicateNickname_throwsNicknameDuplicatedWithoutNotice() {
        stubExistingEmailOwner();
        when(userRepository.existsByNickname(TEST_NICKNAME)).thenReturn(true);

        assertThatThrownBy(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UserErrorCode.NICKNAME_DUPLICATED.getCode()));

        verifyNoInteractions(rabbitTemplate);
    }

    /**
     * 驗證：IP 限流仍在最前面生效——超過上限時，重複 email 與新 email 同樣被 RATE_LIMIT_EXCEEDED 擋下，
     * 不會因為 email 是否存在而不同。
     */
    @Test
    @DisplayName("register → 重複 email 仍受 IP 限流約束（超限拋 RATE_LIMIT_EXCEEDED，不查 email）")
    void register_withDuplicateEmail_stillSubjectToIpRateLimit() {
        stubExistingEmailOwner();
        when(userRedisAtomicOps.incrementWithTtl(eq(RedisKeyConstant.getRegisterIpKey("203.0.113.9")), any(Duration.class)))
                .thenReturn((long) RedisKeyConstant.REGISTER_IP_MAX + 1);

        assertThatThrownBy(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME,
                "203.0.113.9"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UserErrorCode.RATE_LIMIT_EXCEEDED.getCode()));

        verify(userRepository, never()).findByEmail(anyString());
        verifyNoInteractions(rabbitTemplate);
    }

    /**
     * 驗證：成功註冊後應發布 UserRegisteredEvent 至 RabbitMQ。
     */
    @Test
    @DisplayName("register → 成功後應發布 UserRegisteredEvent 至 RabbitMQ")
    void register_shouldPublishUserRegisteredEvent() {
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(false);
        when(userRepository.existsByNickname(TEST_NICKNAME)).thenReturn(false);
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        User savedUser = buildActiveUser();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(verificationTokenRepository.save(any(VerificationToken.class))).thenReturn(new VerificationToken());

        authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME);

        ArgumentCaptor<UserRegisteredEvent> eventCaptor = ArgumentCaptor.forClass(UserRegisteredEvent.class);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), eventCaptor.capture());
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq(RedisKeyConstant.getEmailVerifyCodeKey(TEST_EMAIL)),
                codeCaptor.capture(),
                eq(RedisKeyConstant.EMAIL_VERIFY_CODE_TTL_MINUTES),
                eq(TimeUnit.MINUTES)
        );
        assertThat(codeCaptor.getValue()).as("驗證碼只存 Redis，事件不帶（ARCH-24）").matches("\\d{6}");
        assertThat(eventCaptor.getValue().toString()).doesNotContain(codeCaptor.getValue());
    }

    /**
     * 驗證：註冊事件帶儲存後使用者的 uuid 與發送端產生的 eventId（不再帶 Long 主鍵）。
     */
    @Test
    @DisplayName("register → 事件帶 userUuid（取自儲存後的使用者）與非 null 的 eventId")
    void register_event_carriesUserUuidAndEventId() {
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(false);
        when(userRepository.existsByNickname(TEST_NICKNAME)).thenReturn(false);
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        when(userRepository.save(any(User.class))).thenReturn(buildActiveUser());
        when(verificationTokenRepository.save(any(VerificationToken.class))).thenReturn(new VerificationToken());

        authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME);

        ArgumentCaptor<UserRegisteredEvent> eventCaptor = ArgumentCaptor.forClass(UserRegisteredEvent.class);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), eventCaptor.capture());
        assertThat(eventCaptor.getValue().userUuid()).isEqualTo(USER_UUID);
        assertThat(eventCaptor.getValue().eventId()).isNotNull();
    }

    /**
     * 驗證：register 儲存的 User 應具有正確的初始欄位：
     * status=PENDING_VERIFICATION、emailVerified=false、tokenVersion="v1"。
     */
    @Test
    @DisplayName("register → 儲存的 User 應為 PENDING_VERIFICATION 狀態，emailVerified=false，tokenVersion=v1")
    void register_savedUser_shouldHaveCorrectInitialFields() {
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(false);
        when(userRepository.existsByNickname(TEST_NICKNAME)).thenReturn(false);
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        User savedUser = buildActiveUser();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(verificationTokenRepository.save(any(VerificationToken.class))).thenReturn(new VerificationToken());

        authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User captured = captor.getValue();
        assertThat(captured.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
        assertThat(captured.isEmailVerified()).isFalse();
        assertThat(captured.getTokenVersion()).isEqualTo("v1");
    }

    /* =========================================================================
       login 測試
       ========================================================================= */

    /**
     * 驗證：使用正確帳密登入，應回傳包含 accessToken 與 refreshToken 的 LoginResult，
     * 並將版本資訊寫入 Redis Hash 以及 Refresh Token 寫入 Redis String。
     */
    @Test
    @DisplayName("login → 正確帳密 → 應回傳 LoginResult 且 Redis 有版本與 Refresh Token 快取（ZSet）")
    void login_withValidCredentials_shouldReturnLoginResult_andCacheInRedis() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard(anyString())).thenReturn(1L);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateAccessToken(any(UUID.class), anyString(), anyString())).thenReturn(MOCK_ACCESS_TOKEN);
        when(jwtService.generateRefreshToken(any(UUID.class))).thenReturn(MOCK_REFRESH_TOKEN);

        LoginResult result = authService.login(TEST_EMAIL, TEST_PASSWORD);

        assertThat(result.accessToken()).isEqualTo(MOCK_ACCESS_TOKEN);
        assertThat(result.refreshToken()).isEqualTo(MOCK_REFRESH_TOKEN);
        verify(hashOperations).putAll(eq("user:auth:" + USER_UUID), anyMap());
        verify(hashOperations, never()).put(anyString(), anyString(), anyString());
        verify(userRedisAtomicOps).registerRefreshToken(anyString(), eq(MOCK_REFRESH_TOKEN), anyLong(), eq(3),
                eq(Duration.ofDays(7)));
    }

    /**
     * 驗證：login 應回傳包含 accessToken 與 refreshToken 的 LoginResult。
     */
    @Test
    @DisplayName("login → 應回傳包含 accessToken 與 refreshToken 的 LoginResult")
    void login_shouldReturnLoginResultWithBothTokens() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard(anyString())).thenReturn(1L);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateAccessToken(any(UUID.class), anyString(), anyString())).thenReturn(MOCK_ACCESS_TOKEN);
        when(jwtService.generateRefreshToken(any(UUID.class))).thenReturn(MOCK_REFRESH_TOKEN);

        LoginResult result = authService.login(TEST_EMAIL, TEST_PASSWORD);

        assertThat(result).isNotNull();
        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();
    }

    /**
     * 驗證：密碼錯誤時登入，應拋出 BusinessException。
     */
    @Test
    @DisplayName("login → 密碼錯誤 → 應拋出 BusinessException")
    void login_withWrongPassword_shouldThrowBusinessException() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(TEST_EMAIL, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 驗證：帳號已封禁時登入，應拋出 BusinessException。
     */
    @Test
    @DisplayName("login → 帳號封禁 → 應拋出 BusinessException")
    void login_withBannedAccount_shouldThrowBusinessException() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        User mockUser = buildActiveUser();
        mockUser.setStatus(UserStatus.BANNED);
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(TEST_EMAIL, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 驗證：帳號為 PENDING_VERIFICATION 時登入，應拋出 EMAIL_NOT_VERIFIED BusinessException。
     */
    @Test
    @DisplayName("login → PENDING_VERIFICATION 帳號 → 應拋出 EMAIL_NOT_VERIFIED BusinessException")
    void login_withPendingVerificationAccount_shouldThrowEmailNotVerified() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        User mockUser = buildActiveUser();
        mockUser.setStatus(UserStatus.PENDING_VERIFICATION);
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(TEST_EMAIL, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(UserErrorCode.EMAIL_NOT_VERIFIED.getCode());
                });
    }

    /**
     * 驗證：DELETED 帳號登入，應拋出 ACCOUNT_SUSPENDED BusinessException。
     */
    @Test
    @DisplayName("login → DELETED 帳號 → 應拋出 ACCOUNT_SUSPENDED BusinessException")
    void login_withDeletedAccount_shouldThrowAccountSuspended() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        User mockUser = buildActiveUser();
        mockUser.setStatus(UserStatus.DELETED);
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(TEST_EMAIL, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.ACCOUNT_SUSPENDED.getCode()));
    }

    /**
     * 驗證：email 與 username 均不存在時登入，應拋出 USER_PASSWORD_ERROR BusinessException。
     */
    @Test
    @DisplayName("login → email 與 username 均不存在 → 應拋出 USER_PASSWORD_ERROR BusinessException")
    void login_withNonExistentUser_shouldThrowUserPasswordError() {
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.empty());
        when(userRepository.findByUsername(TEST_EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(TEST_EMAIL, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.USER_PASSWORD_ERROR.getCode()));
    }

    /**
     * 驗證：使用用戶名登入，應能找到用戶並成功回傳 Token。
     */
    @Test
    @DisplayName("login → 使用 username 登入 → 應成功回傳 Token")
    void login_withUsername_shouldReturnToken() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard(anyString())).thenReturn(1L);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_USERNAME)).thenReturn(Optional.empty());
        when(userRepository.findByUsername(TEST_USERNAME)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateAccessToken(any(UUID.class), anyString(), anyString())).thenReturn(MOCK_ACCESS_TOKEN);
        when(jwtService.generateRefreshToken(any(UUID.class))).thenReturn(MOCK_REFRESH_TOKEN);

        LoginResult result = authService.login(TEST_USERNAME, TEST_PASSWORD);

        assertThat(result.accessToken()).isEqualTo(MOCK_ACCESS_TOKEN);
    }

    /* =========================================================================
       logout 測試
       ========================================================================= */

    /**
     * 驗證：logout 應刪除 Redis 中的 Refresh Token。
     */
    @Test
    @DisplayName("logout → 指定 refreshToken → 應從 ZSet 移除指定 Token")
    void logout_shouldClearRefreshTokenInRedis() {
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);

        authService.logout(USER_UUID, MOCK_REFRESH_TOKEN);

        verify(zSetOps).remove(anyString(), eq((Object) MOCK_REFRESH_TOKEN));
    }

    /* =========================================================================
       verifyEmail 測試
       ========================================================================= */

    /**
     * 驗證：使用有效 Token 驗證信箱，應將用戶狀態設為 ACTIVE、emailVerified=true 並刪除 Token。
     */
    @Test
    @DisplayName("verifyEmail → 有效 Token → 應啟用帳號並刪除 Token")
    void verifyEmail_withValidToken_shouldActivateUserAndDeleteToken() {
        String tokenStr = "valid-email-token";
        VerificationToken emailToken = buildEmailVerificationToken(tokenStr, LocalDateTime.now().plusHours(24));
        User mockUser = buildActiveUser();
        mockUser.setStatus(UserStatus.PENDING_VERIFICATION);
        mockUser.setEmailVerified(false);

        when(verificationTokenRepository.findByTokenAndType(tokenStr, "EMAIL_VERIFICATION"))
                .thenReturn(Optional.of(emailToken));
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockUser));

        authService.verifyEmail(tokenStr);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(captor.getValue().isEmailVerified()).isTrue();
        verify(verificationTokenRepository).delete(emailToken);
    }

    /**
     * 驗證：使用不存在的 Token 驗證信箱，應拋出 TOKEN_INVALID BusinessException。
     */
    @Test
    @DisplayName("verifyEmail → Token 不存在 → 應拋出 TOKEN_INVALID BusinessException")
    void verifyEmail_withNonExistentToken_shouldThrowTokenInvalid() {
        String tokenStr = "non-existent-token";
        when(verificationTokenRepository.findByTokenAndType(tokenStr, "EMAIL_VERIFICATION"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verifyEmail(tokenStr))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.TOKEN_INVALID.getCode()));
    }

    /**
     * 驗證：使用已過期的 Token 驗證信箱，應拋出 TOKEN_INVALID BusinessException。
     */
    @Test
    @DisplayName("verifyEmail → Token 已過期 → 應拋出 TOKEN_INVALID BusinessException")
    void verifyEmail_withExpiredToken_shouldThrowTokenInvalid() {
        String tokenStr = "expired-email-token";
        VerificationToken expiredToken = buildEmailVerificationToken(tokenStr, LocalDateTime.now().minusHours(1));
        when(verificationTokenRepository.findByTokenAndType(tokenStr, "EMAIL_VERIFICATION"))
                .thenReturn(Optional.of(expiredToken));

        assertThatThrownBy(() -> authService.verifyEmail(tokenStr))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.TOKEN_INVALID.getCode()));
    }

    /**
     * 驗證：使用正確 6 位驗證碼應啟用 PENDING_VERIFICATION 帳號，並清除舊 URL token、Redis code 與失敗計數。
     */
    @Test
    @DisplayName("verifyEmailCode → 正確驗證碼 → 應啟用帳號並清除驗證資料")
    void verifyEmailCode_withValidCode_shouldActivateUserAndClearVerificationState() {
        User mockUser = buildActiveUser();
        mockUser.setStatus(UserStatus.PENDING_VERIFICATION);
        mockUser.setEmailVerified(false);
        String failKey = RedisKeyConstant.getEmailVerifyCodeFailKey(TEST_EMAIL);
        when(valueOperations.get(failKey)).thenReturn(null);
        when(valueOperations.get(RedisKeyConstant.getEmailVerifyCodeKey(TEST_EMAIL))).thenReturn("123456");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));

        authService.verifyEmailCode(TEST_EMAIL, "123456");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(captor.getValue().isEmailVerified()).isTrue();
        verify(verificationTokenRepository).deleteByUserIdAndType(mockUser.getId(), "EMAIL_VERIFICATION");
        verify(redisTemplate).delete(RedisKeyConstant.getEmailVerifyCodeKey(TEST_EMAIL));
        verify(redisTemplate).delete(failKey);
    }

    /**
     * 驗證：驗證碼不存在或不相符時，應拋出 TOKEN_INVALID 且不可啟用帳號。
     */
    @Test
    @DisplayName("verifyEmailCode → 驗證碼不符 → 應拋出 TOKEN_INVALID")
    void verifyEmailCode_withInvalidCode_shouldThrowTokenInvalid() {
        when(valueOperations.get(RedisKeyConstant.getEmailVerifyCodeKey(TEST_EMAIL))).thenReturn("654321");
        when(userRedisAtomicOps.incrementWithTtl(anyString(), any(Duration.class))).thenReturn(1L);

        assertThatThrownBy(() -> authService.verifyEmailCode(TEST_EMAIL, "123456"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.TOKEN_INVALID.getCode()));

        verify(userRepository, never()).save(any());
        verify(verificationTokenRepository, never()).deleteByUserIdAndType(anyLong(), anyString());
    }

    /**
     * 驗證：失敗次數達上限（5 次）後，應拋出 RATE_LIMIT_EXCEEDED 且不再檢查驗證碼。
     */
    @Test
    @DisplayName("verifyEmailCode → 失敗次數達上限 → 應拋出 RATE_LIMIT_EXCEEDED")
    void verifyEmailCode_lockedAfterMaxAttempts_shouldThrowRateLimitExceeded() {
        String failKey = RedisKeyConstant.getEmailVerifyCodeFailKey(TEST_EMAIL);
        when(valueOperations.get(failKey)).thenReturn("5");

        assertThatThrownBy(() -> authService.verifyEmailCode(TEST_EMAIL, "000000"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.RATE_LIMIT_EXCEEDED.getCode()));

        verify(userRepository, never()).findByEmail(any());
    }

    /**
     * 驗證：驗證碼錯誤時應遞增失敗計數器。
     */
    @Test
    @DisplayName("verifyEmailCode → 驗證碼錯誤 → 應遞增失敗計數器")
    void verifyEmailCode_wrongCode_shouldIncrementFailCounter() {
        String failKey = RedisKeyConstant.getEmailVerifyCodeFailKey(TEST_EMAIL);
        when(valueOperations.get(failKey)).thenReturn(null);
        when(valueOperations.get(RedisKeyConstant.getEmailVerifyCodeKey(TEST_EMAIL))).thenReturn("654321");
        when(userRedisAtomicOps.incrementWithTtl(eq(failKey), any(Duration.class))).thenReturn(2L);

        assertThatThrownBy(() -> authService.verifyEmailCode(TEST_EMAIL, "123456"))
                .isInstanceOf(BusinessException.class);

        verify(userRedisAtomicOps).incrementWithTtl(eq(failKey), any(Duration.class));
    }

    /**
     * 驗證：首次驗證碼錯誤（count == 1）應設定失敗計數器 TTL。
     */
    @Test
    @DisplayName("verifyEmailCode → 首次驗證碼錯誤 → 應設定失敗計數器 TTL")
    void verifyEmailCode_firstWrongCode_shouldSetFailKeyTtl() {
        String failKey = RedisKeyConstant.getEmailVerifyCodeFailKey(TEST_EMAIL);
        when(valueOperations.get(failKey)).thenReturn(null);
        when(valueOperations.get(RedisKeyConstant.getEmailVerifyCodeKey(TEST_EMAIL))).thenReturn("654321");
        when(userRedisAtomicOps.incrementWithTtl(eq(failKey), any(Duration.class))).thenReturn(1L);

        assertThatThrownBy(() -> authService.verifyEmailCode(TEST_EMAIL, "123456"))
                .isInstanceOf(BusinessException.class);

        verify(userRedisAtomicOps).incrementWithTtl(eq(failKey),
                eq(Duration.ofMinutes(RedisKeyConstant.EMAIL_VERIFY_CODE_TTL_MINUTES)));
    }

    /**
     * 驗證：驗證碼正確但用戶不存在時，應拋出 TOKEN_INVALID。
     */
    @Test
    @DisplayName("verifyEmailCode → 驗證碼正確但用戶不存在 → 應拋出 TOKEN_INVALID")
    void verifyEmailCode_codeExistsButUserNotFound_shouldThrowTokenInvalid() {
        String failKey = RedisKeyConstant.getEmailVerifyCodeFailKey(TEST_EMAIL);
        when(valueOperations.get(failKey)).thenReturn(null);
        when(valueOperations.get(RedisKeyConstant.getEmailVerifyCodeKey(TEST_EMAIL))).thenReturn("123456");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verifyEmailCode(TEST_EMAIL, "123456"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.TOKEN_INVALID.getCode()));

        verify(userRepository, never()).save(any());
    }

    /**
     * 驗證：驗證碼正確但用戶狀態非 PENDING_VERIFICATION，應拋出 TOKEN_INVALID（安全設計，避免洩漏帳號狀態）。
     */
    @Test
    @DisplayName("verifyEmailCode → 用戶狀態非 PENDING_VERIFICATION → 應拋出 TOKEN_INVALID")
    void verifyEmailCode_userNotPending_shouldThrowTokenInvalid() {
        String failKey = RedisKeyConstant.getEmailVerifyCodeFailKey(TEST_EMAIL);
        when(valueOperations.get(failKey)).thenReturn(null);
        when(valueOperations.get(RedisKeyConstant.getEmailVerifyCodeKey(TEST_EMAIL))).thenReturn("123456");
        User mockUser = buildActiveUser(); // status = ACTIVE
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));

        assertThatThrownBy(() -> authService.verifyEmailCode(TEST_EMAIL, "123456"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.TOKEN_INVALID.getCode()));

        verify(userRepository, never()).save(any());
    }

    /* =========================================================================
       forgotPassword 測試
       ========================================================================= */

    /**
     * 驗證：forgotPassword 應儲存驗證 Token 並發布事件至 RabbitMQ。
     */
    @Test
    @DisplayName("forgotPassword → 信箱存在 → 應儲存驗證 Token 並發布事件")
    void forgotPassword_shouldSaveVerificationTokenAndPublishEvent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRedisAtomicOps.incrementWithTtl(anyString(), any(Duration.class))).thenReturn(1L);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(verificationTokenRepository.save(any(VerificationToken.class))).thenReturn(new VerificationToken());

        authService.forgotPassword(TEST_EMAIL);

        verify(verificationTokenRepository).save(any(VerificationToken.class));
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), (Object) any());
    }

    /**
     * 驗證：密碼重設申請事件帶使用者 uuid 與發送端產生的 eventId（不再帶 Long 主鍵）。
     */
    @Test
    @DisplayName("forgotPassword → 事件帶 userUuid 與非 null 的 eventId")
    void forgotPassword_event_carriesUserUuidAndEventId() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRedisAtomicOps.incrementWithTtl(anyString(), any(Duration.class))).thenReturn(1L);
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(buildActiveUser()));
        when(verificationTokenRepository.save(any(VerificationToken.class))).thenReturn(new VerificationToken());

        authService.forgotPassword(TEST_EMAIL);

        ArgumentCaptor<UserPasswordResetRequestedEvent> eventCaptor =
                ArgumentCaptor.forClass(UserPasswordResetRequestedEvent.class);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), eventCaptor.capture());
        assertThat(eventCaptor.getValue().userUuid()).isEqualTo(USER_UUID);
        assertThat(eventCaptor.getValue().eventId()).isNotNull();
        assertThat(eventCaptor.getValue().toString()).as("事件不含 email 與 token（ARCH-24）")
                .doesNotContain(TEST_EMAIL);
    }

    /**
     * 驗證：forgotPassword 對不存在的信箱應靜默忽略，不拋出例外。
     */
    @Test
    @DisplayName("forgotPassword → 信箱不存在 → 應靜默忽略，不發布任何事件")
    void forgotPassword_withUnknownEmail_shouldDoNothing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRedisAtomicOps.incrementWithTtl(anyString(), any(Duration.class))).thenReturn(1L);
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.empty());

        authService.forgotPassword(TEST_EMAIL);

        verify(verificationTokenRepository, never()).save(any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), (Object) any());
    }

    /**
     * 驗證：同一信箱在一分鐘內發送超過 1 次，應拋出 RATE_LIMIT_EXCEEDED BusinessException。
     */
    @Test
    @DisplayName("forgotPassword → 每分鐘超過限制 → 應拋出 RATE_LIMIT_EXCEEDED")
    void forgotPassword_exceedMinuteLimit_shouldThrow() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRedisAtomicOps.incrementWithTtl(anyString(), any(Duration.class))).thenReturn(2L);

        assertThatThrownBy(() -> authService.forgotPassword(TEST_EMAIL))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.RATE_LIMIT_EXCEEDED.getCode()));

        verify(verificationTokenRepository, never()).save(any());
    }

    /**
     * 驗證：同一信箱當日發送超過 5 次，應拋出 RATE_LIMIT_EXCEEDED BusinessException。
     */
    @Test
    @DisplayName("forgotPassword → 每日超過限制 → 應拋出 RATE_LIMIT_EXCEEDED")
    void forgotPassword_exceedDailyLimit_shouldThrow() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRedisAtomicOps.incrementWithTtl(anyString(), any(Duration.class)))
                .thenReturn(1L)
                .thenReturn(6L);

        assertThatThrownBy(() -> authService.forgotPassword(TEST_EMAIL))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.RATE_LIMIT_EXCEEDED.getCode()));

        verify(verificationTokenRepository, never()).save(any());
    }

    /**
     * 驗證：未超過限制時 forgotPassword 應正常執行並儲存 Token、發布事件。
     */
    @Test
    @DisplayName("forgotPassword → 未超過限制 → 應正常儲存 Token 並發布事件")
    void forgotPassword_withinLimit_shouldProceedNormally() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRedisAtomicOps.incrementWithTtl(anyString(), any(Duration.class)))
                .thenReturn(1L)
                .thenReturn(1L);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(verificationTokenRepository.save(any(VerificationToken.class))).thenReturn(new VerificationToken());

        authService.forgotPassword(TEST_EMAIL);

        verify(verificationTokenRepository).save(any(VerificationToken.class));
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), (Object) any());
    }

    /* =========================================================================
       resetPassword 測試
       ========================================================================= */

    /**
     * 驗證：resetPassword 使用有效 Token 應更新密碼、遞增 tokenVersion 並撤銷所有 session。
     */
    @Test
    @DisplayName("resetPassword → 有效 Token → 應更新密碼、遞增 tokenVersion 並撤銷所有 session")
    void resetPassword_withValidToken_shouldUpdatePassword() {
        String tokenStr = "valid-reset-token";
        VerificationToken resetToken = buildResetToken(tokenStr, LocalDateTime.now().plusMinutes(10));
        User mockUser = buildActiveUser();

        when(verificationTokenRepository.findByTokenAndType(tokenStr, "PASSWORD_RESET"))
                .thenReturn(Optional.of(resetToken));
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.encode(anyString())).thenReturn("newEncodedPassword");

        authService.resetPassword(tokenStr, "newPassword123");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getTokenVersion()).isEqualTo("v2");
        verify(sessionRevoker).revokeAllSessions(mockUser.getUuid());
        verify(verificationTokenRepository).deleteByUserIdAndType(mockUser.getId(), "PASSWORD_RESET");
    }

    /**
     * 驗證：resetPassword 使用已過期的 Token 應拋出 BusinessException。
     */
    @Test
    @DisplayName("resetPassword → 過期 Token → 應拋出 BusinessException")
    void resetPassword_withExpiredToken_shouldThrowBusinessException() {
        String tokenStr = "expired-token";
        VerificationToken expiredToken = buildResetToken(tokenStr, LocalDateTime.now().minusMinutes(1));

        when(verificationTokenRepository.findByTokenAndType(tokenStr, "PASSWORD_RESET"))
                .thenReturn(Optional.of(expiredToken));

        assertThatThrownBy(() -> authService.resetPassword(tokenStr, "newPassword"))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 驗證：resetPassword 使用不存在的 Token 應拋出 TOKEN_INVALID BusinessException。
     */
    @Test
    @DisplayName("resetPassword → Token 不存在 → 應拋出 TOKEN_INVALID BusinessException")
    void resetPassword_withNonExistentToken_shouldThrowTokenInvalid() {
        String tokenStr = "non-existent-reset-token";
        when(verificationTokenRepository.findByTokenAndType(tokenStr, "PASSWORD_RESET"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resetPassword(tokenStr, "newPassword"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.TOKEN_INVALID.getCode()));
    }

    /* =========================================================================
       login 鎖定測試
       ========================================================================= */

    /**
     * 驗證：密碼錯誤時應遞增失敗計數器。
     */
    @Test
    @DisplayName("login → 密碼錯誤 → 應遞增失敗計數器")
    void login_failedPassword_shouldIncrementFailCounter() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(TEST_EMAIL, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class);

        verify(userRedisAtomicOps).incrementWithTtl(contains("login:fail:"), any(Duration.class));
    }

    /**
     * 驗證：失敗 5 次後應拋出 ACCOUNT_LOCKED。
     */
    @Test
    @DisplayName("login → 失敗 5 次後 → 應拋出 ACCOUNT_LOCKED")
    void login_after5Failures_shouldThrowAccountLocked() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("5");
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));

        assertThatThrownBy(() -> authService.login(TEST_EMAIL, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.ACCOUNT_LOCKED.getCode()));
    }

    /**
     * 驗證：成功登入後應清除失敗計數器。
     */
    @Test
    @DisplayName("login → 成功登入 → 應清除失敗計數器")
    void login_success_shouldClearFailCounter() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard(anyString())).thenReturn(1L);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateAccessToken(any(UUID.class), anyString(), anyString())).thenReturn(MOCK_ACCESS_TOKEN);
        when(jwtService.generateRefreshToken(any(UUID.class))).thenReturn(MOCK_REFRESH_TOKEN);

        authService.login(TEST_EMAIL, TEST_PASSWORD);

        verify(redisTemplate).delete(contains("login:fail:"));
    }

    /* =========================================================================
       login ZSet 裝置限制測試
       ========================================================================= */

    /**
     * 驗證：login 應將 Refresh Token 加入 ZSet。
     */
    @Test
    @DisplayName("login → 應將 Refresh Token 加入 ZSet")
    void login_shouldAddRefreshTokenToZSet() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard(anyString())).thenReturn(1L);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateAccessToken(any(UUID.class), anyString(), anyString())).thenReturn(MOCK_ACCESS_TOKEN);
        when(jwtService.generateRefreshToken(any(UUID.class))).thenReturn(MOCK_REFRESH_TOKEN);

        authService.login(TEST_EMAIL, TEST_PASSWORD);

        verify(userRedisAtomicOps).registerRefreshToken(anyString(), eq(MOCK_REFRESH_TOKEN), anyLong(), eq(3),
                eq(Duration.ofDays(7)));
    }

    /**
     * 驗證：logout 指定 refreshToken 應從 ZSet 移除指定 Token。
     */
    @Test
    @DisplayName("logout → 指定 refreshToken → 應從 ZSet 移除指定 Token")
    void logout_shouldRemoveSpecificTokenFromZSet() {
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);

        authService.logout(USER_UUID, MOCK_REFRESH_TOKEN);

        verify(zSetOps).remove(anyString(), eq((Object) MOCK_REFRESH_TOKEN));
    }

    /* =========================================================================
       resendVerification 測試
       ========================================================================= */

    /**
     * 驗證：有效 PENDING 狀態用戶應成功發送驗證信。
     */
    @Test
    @DisplayName("resendVerification → 有效 PENDING 用戶 → 應發送驗證信")
    void resendVerification_validPendingUser_shouldSendEmail() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRedisAtomicOps.incrementWithTtl(anyString(), any(Duration.class))).thenReturn(1L);
        User mockUser = buildActiveUser();
        mockUser.setStatus(UserStatus.PENDING_VERIFICATION);
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(verificationTokenRepository.save(any(VerificationToken.class))).thenReturn(new VerificationToken());

        authService.resendVerification(TEST_EMAIL);

        verify(verificationTokenRepository).save(any(VerificationToken.class));
        ArgumentCaptor<UserRegisteredEvent> eventCaptor = ArgumentCaptor.forClass(UserRegisteredEvent.class);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), eventCaptor.capture());
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq(RedisKeyConstant.getEmailVerifyCodeKey(TEST_EMAIL)),
                codeCaptor.capture(),
                eq(RedisKeyConstant.EMAIL_VERIFY_CODE_TTL_MINUTES),
                eq(TimeUnit.MINUTES)
        );
        assertThat(codeCaptor.getValue()).as("驗證碼只存 Redis，事件不帶（ARCH-24）").matches("\\d{6}");
        assertThat(eventCaptor.getValue().toString()).doesNotContain(codeCaptor.getValue());
    }

    /**
     * 驗證：重發驗證信的事件帶 userUuid 與 eventId，且每次重發都是新的 eventId
     * （否則 consumer 依 eventId 去重會把使用者主動要求的重發誤判為重送而不寄）。
     */
    @Test
    @DisplayName("resendVerification → 事件帶 userUuid；連續兩次重發的 eventId 不同（不被 consumer 去重吃掉）")
    void resendVerification_event_carriesUserUuidAndFreshEventIdEachTime() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRedisAtomicOps.incrementWithTtl(anyString(), any(Duration.class))).thenReturn(1L);
        User mockUser = buildActiveUser();
        mockUser.setStatus(UserStatus.PENDING_VERIFICATION);
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(verificationTokenRepository.save(any(VerificationToken.class))).thenReturn(new VerificationToken());

        authService.resendVerification(TEST_EMAIL);
        authService.resendVerification(TEST_EMAIL);

        ArgumentCaptor<UserRegisteredEvent> eventCaptor = ArgumentCaptor.forClass(UserRegisteredEvent.class);
        verify(rabbitTemplate, times(2)).convertAndSend(anyString(), anyString(), eventCaptor.capture());
        List<UserRegisteredEvent> events = eventCaptor.getAllValues();
        assertThat(events).allSatisfy(e -> {
            assertThat(e.userUuid()).isEqualTo(USER_UUID);
            assertThat(e.eventId()).isNotNull();
        });
        assertThat(events.get(0).eventId()).isNotEqualTo(events.get(1).eventId());
    }

    /**
     * 驗證：已啟用帳號重發應靜默忽略。
     */
    @Test
    @DisplayName("resendVerification → 已啟用帳號 → 應靜默忽略")
    void resendVerification_alreadyActive_shouldSilentlyIgnore() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRedisAtomicOps.incrementWithTtl(anyString(), any(Duration.class))).thenReturn(1L);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));

        authService.resendVerification(TEST_EMAIL);

        verify(verificationTokenRepository, never()).save(any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), (Object) any());
    }

    /**
     * 驗證：不存在的信箱應靜默忽略。
     */
    @Test
    @DisplayName("resendVerification → 不存在的信箱 → 應靜默忽略")
    void resendVerification_unknownEmail_shouldSilentlyIgnore() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRedisAtomicOps.incrementWithTtl(anyString(), any(Duration.class))).thenReturn(1L);
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.empty());

        authService.resendVerification(TEST_EMAIL);

        verify(verificationTokenRepository, never()).save(any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), (Object) any());
    }

    /**
     * 驗證：每分鐘超過 1 次限制應拋出 RATE_LIMIT_EXCEEDED。
     */
    @Test
    @DisplayName("resendVerification → 每分鐘超過限制 → 應拋出 RATE_LIMIT_EXCEEDED")
    void resendVerification_exceedMinuteLimit_shouldThrow() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRedisAtomicOps.incrementWithTtl(anyString(), any(Duration.class))).thenReturn(2L);

        assertThatThrownBy(() -> authService.resendVerification(TEST_EMAIL))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.RATE_LIMIT_EXCEEDED.getCode()));
    }

    /* =========================================================================
       login 邊界：failCountStr 存在但未達上限（< 5）
       ========================================================================= */

    /**
     * 驗證：login 失敗計數器值存在但未達鎖定上限（例如 4），應繼續密碼驗證而非直接拋出 ACCOUNT_LOCKED。
     */
    @Test
    @DisplayName("login → failCountStr 存在但 < 5 → 應繼續密碼驗證，不拋出 ACCOUNT_LOCKED")
    void login_withFailCountBelowMax_shouldNotLock() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("4");
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(false);
        when(userRedisAtomicOps.incrementWithTtl(anyString(), any(Duration.class))).thenReturn(5L);

        assertThatThrownBy(() -> authService.login(TEST_EMAIL, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.USER_PASSWORD_ERROR.getCode()));
    }

    /* =========================================================================
       login 邊界：首次密碼錯誤應設定 TTL；非首次不設
       ========================================================================= */

    /**
     * 驗證：首次密碼錯誤（increment 回傳 1L）應呼叫 expire 設定鎖定 TTL。
     */
    @Test
    @DisplayName("login → 首次密碼錯誤（count == 1） → 應呼叫 expire 設定 TTL")
    void login_firstPasswordFailure_shouldSetExpire() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(false);
        when(userRedisAtomicOps.incrementWithTtl(anyString(), any(Duration.class))).thenReturn(1L);

        assertThatThrownBy(() -> authService.login(TEST_EMAIL, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class);

        verify(userRedisAtomicOps).incrementWithTtl(contains("login:fail:"),
                eq(Duration.ofMinutes(RedisKeyConstant.LOGIN_FAIL_TTL_MINUTES)));
    }

    /* =========================================================================
       login 邊界：deviceCount 為 null 時不移除最舊 Token
       ========================================================================= */

    /* =========================================================================
       logout 邊界：null 與 blank refreshToken 應刪除整個 ZSet Key
       ========================================================================= */

    /**
     * 驗證：logout 傳入 null refreshToken 應刪除整個 ZSet Key（登出所有裝置）。
     */
    @Test
    @DisplayName("logout → refreshToken 為 null → 應刪除整個 ZSet Key")
    void logout_withNullRefreshToken_shouldDeleteEntireKey() {
        authService.logout(USER_UUID, null);

        verify(redisTemplate).delete(contains("user:refresh:"));
        verify(redisTemplate, never()).opsForZSet();
    }

    /**
     * 驗證：logout 傳入空白字串 refreshToken 應刪除整個 ZSet Key（登出所有裝置）。
     */
    @Test
    @DisplayName("logout → refreshToken 為空白字串 → 應刪除整個 ZSet Key")
    void logout_withBlankRefreshToken_shouldDeleteEntireKey() {
        authService.logout(USER_UUID, "   ");

        verify(redisTemplate).delete(contains("user:refresh:"));
        verify(redisTemplate, never()).opsForZSet();
    }

    /* =========================================================================
       verifyEmail 邊界：token 有效但 user 已不存在
       ========================================================================= */

    /**
     * 驗證：verifyEmail Token 有效但對應用戶不存在，應拋出 USER_NOT_FOUND BusinessException。
     */
    @Test
    @DisplayName("verifyEmail → Token 有效但用戶不存在 → 應拋出 USER_NOT_FOUND BusinessException")
    void verifyEmail_withValidTokenButUserNotFound_shouldThrowUserNotFound() {
        String tokenStr = "orphan-email-token";
        VerificationToken emailToken = buildEmailVerificationToken(tokenStr, LocalDateTime.now().plusHours(24));

        when(verificationTokenRepository.findByTokenAndType(tokenStr, "EMAIL_VERIFICATION"))
                .thenReturn(Optional.of(emailToken));
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verifyEmail(tokenStr))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.USER_NOT_FOUND.getCode()));
    }

    /* =========================================================================
       forgotPassword 邊界：Redis increment 回傳 null（minCount / dayCount）
       ========================================================================= */

    /**
     * 驗證：forgotPassword 每日計數恰好達到上限（5 次）時，應正常執行不拋出 RATE_LIMIT_EXCEEDED。
     */
    @Test
    @DisplayName("forgotPassword → dayCount 恰好為 5 → 仍應正常執行，不拋出 RATE_LIMIT_EXCEEDED")
    void forgotPassword_dayCountExactlyAtLimit_shouldProceed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRedisAtomicOps.incrementWithTtl(anyString(), any(Duration.class)))
                .thenReturn(1L)  // minCount = 1
                .thenReturn(5L); // dayCount = 5 (not > 5)
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.empty());

        authService.forgotPassword(TEST_EMAIL);

        verify(verificationTokenRepository, never()).save(any());
    }

    /* =========================================================================
       resendVerification 邊界：Redis increment 回傳 null 及每日超限
       ========================================================================= */

    /**
     * 驗證：resendVerification 每日計數超過 5 次，應拋出 RATE_LIMIT_EXCEEDED。
     */
    @Test
    @DisplayName("resendVerification → 每日超過限制（dayCount > 5） → 應拋出 RATE_LIMIT_EXCEEDED")
    void resendVerification_exceedDailyLimit_shouldThrow() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRedisAtomicOps.incrementWithTtl(anyString(), any(Duration.class)))
                .thenReturn(1L)  // minCount = 1
                .thenReturn(6L); // dayCount = 6

        assertThatThrownBy(() -> authService.resendVerification(TEST_EMAIL))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.RATE_LIMIT_EXCEEDED.getCode()));

        verify(verificationTokenRepository, never()).save(any());
    }

    /**
     * 驗證：resendVerification 每日計數恰好達到上限（5 次）時，應正常執行不拋出 RATE_LIMIT_EXCEEDED。
     */
    @Test
    @DisplayName("resendVerification → dayCount 恰好為 5 → 仍應正常執行，不拋出 RATE_LIMIT_EXCEEDED")
    void resendVerification_dayCountExactlyAtLimit_shouldProceed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRedisAtomicOps.incrementWithTtl(anyString(), any(Duration.class)))
                .thenReturn(1L)  // minCount = 1
                .thenReturn(5L); // dayCount = 5 (not > 5)
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.empty());

        authService.resendVerification(TEST_EMAIL);

        verify(verificationTokenRepository, never()).save(any());
    }

    /* =========================================================================
       resetPassword 邊界：token 有效但 user 已不存在
       ========================================================================= */

    /**
     * 驗證：resetPassword Token 有效但對應用戶不存在，應拋出 USER_NOT_FOUND BusinessException。
     */
    @Test
    @DisplayName("resetPassword → Token 有效但用戶不存在 → 應拋出 USER_NOT_FOUND BusinessException")
    void resetPassword_withValidTokenButUserNotFound_shouldThrowUserNotFound() {
        String tokenStr = "orphan-reset-token";
        VerificationToken resetToken = buildResetToken(tokenStr, LocalDateTime.now().plusMinutes(10));

        when(verificationTokenRepository.findByTokenAndType(tokenStr, "PASSWORD_RESET"))
                .thenReturn(Optional.of(resetToken));
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resetPassword(tokenStr, "newPassword"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.USER_NOT_FOUND.getCode()));
    }

    /* =========================================================================
       IP 層級限流測試（Task 13）
       ========================================================================= */

    /** 測試用 client IP */
    private static final String TEST_IP = "203.0.113.10";

    /**
     * 驗證：login 帶 client IP 且 IP 計數未達上限時，應正常登入（IP 限流與帳號鎖定互不干擾）。
     */
    @Test
    @DisplayName("login(ip) → IP 計數未達上限 → 應正常回傳 LoginResult")
    void login_whenIpRateLimitNotExceeded_shouldSucceed() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        // IP 計數 increment 回傳低於上限
        when(userRedisAtomicOps.incrementWithTtl(eq(RedisKeyConstant.getLoginIpKey(TEST_IP)), any(Duration.class))).thenReturn(3L);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard(anyString())).thenReturn(1L);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateAccessToken(any(UUID.class), anyString(), anyString())).thenReturn(MOCK_ACCESS_TOKEN);
        when(jwtService.generateRefreshToken(any(UUID.class))).thenReturn(MOCK_REFRESH_TOKEN);

        LoginResult result = authService.login(TEST_EMAIL, TEST_PASSWORD, TEST_IP);

        assertThat(result.accessToken()).isEqualTo(MOCK_ACCESS_TOKEN);
        verify(userRedisAtomicOps).incrementWithTtl(eq(RedisKeyConstant.getLoginIpKey(TEST_IP)), any(Duration.class));
    }

    /**
     * 驗證：login 的 IP 計數超過上限（> LOGIN_IP_MAX）時，應拋出 RATE_LIMIT_EXCEEDED，
     * 且不再進行用戶查詢與密碼驗證。
     */
    @Test
    @DisplayName("login(ip) → IP 計數超過上限 → 應拋出 RATE_LIMIT_EXCEEDED 且不查用戶")
    void login_whenIpRateLimitExceeded_shouldThrowRateLimitExceeded() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRedisAtomicOps.incrementWithTtl(eq(RedisKeyConstant.getLoginIpKey(TEST_IP)), any(Duration.class)))
                .thenReturn((long) (RedisKeyConstant.LOGIN_IP_MAX + 1));

        assertThatThrownBy(() -> authService.login(TEST_EMAIL, TEST_PASSWORD, TEST_IP))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.RATE_LIMIT_EXCEEDED.getCode()));

        verify(userRepository, never()).findByEmail(anyString());
        verify(userRepository, never()).findByUsername(anyString());
    }

    /**
     * 驗證：login 的 IP 計數恰好等於上限（== LOGIN_IP_MAX）時，仍應通過（邊界：未超過）。
     */
    @Test
    @DisplayName("login(ip) → IP 計數恰好等於上限 → 仍應通過，不拋出 RATE_LIMIT_EXCEEDED")
    void login_whenIpCountExactlyAtMax_shouldProceed() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(userRedisAtomicOps.incrementWithTtl(eq(RedisKeyConstant.getLoginIpKey(TEST_IP)), any(Duration.class)))
                .thenReturn((long) RedisKeyConstant.LOGIN_IP_MAX);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard(anyString())).thenReturn(1L);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateAccessToken(any(UUID.class), anyString(), anyString())).thenReturn(MOCK_ACCESS_TOKEN);
        when(jwtService.generateRefreshToken(any(UUID.class))).thenReturn(MOCK_REFRESH_TOKEN);

        LoginResult result = authService.login(TEST_EMAIL, TEST_PASSWORD, TEST_IP);

        assertThat(result.accessToken()).isEqualTo(MOCK_ACCESS_TOKEN);
    }

    /**
     * 驗證：login 首次 IP 計數（increment == 1）應設定 IP 限流 key 的 TTL。
     */
    @Test
    @DisplayName("login(ip) → 首次 IP 計數（count == 1） → 應設定 IP 限流 TTL")
    void login_firstIpHit_shouldSetIpKeyTtl() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(userRedisAtomicOps.incrementWithTtl(eq(RedisKeyConstant.getLoginIpKey(TEST_IP)), any(Duration.class))).thenReturn(1L);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard(anyString())).thenReturn(1L);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateAccessToken(any(UUID.class), anyString(), anyString())).thenReturn(MOCK_ACCESS_TOKEN);
        when(jwtService.generateRefreshToken(any(UUID.class))).thenReturn(MOCK_REFRESH_TOKEN);

        authService.login(TEST_EMAIL, TEST_PASSWORD, TEST_IP);

        verify(userRedisAtomicOps).incrementWithTtl(eq(RedisKeyConstant.getLoginIpKey(TEST_IP)),
                eq(Duration.ofMinutes(RedisKeyConstant.LOGIN_IP_TTL_MINUTES)));
    }

    /**
     * 驗證：login 帶 null IP（無法解析 client IP）時，應跳過 IP 限流，沿用既有 2-arg 行為。
     */
    @Test
    @DisplayName("login(null ip) → 應跳過 IP 限流，正常登入")
    void login_withNullIp_shouldSkipIpRateLimit() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard(anyString())).thenReturn(1L);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateAccessToken(any(UUID.class), anyString(), anyString())).thenReturn(MOCK_ACCESS_TOKEN);
        when(jwtService.generateRefreshToken(any(UUID.class))).thenReturn(MOCK_REFRESH_TOKEN);

        LoginResult result = authService.login(TEST_EMAIL, TEST_PASSWORD, null);

        assertThat(result.accessToken()).isEqualTo(MOCK_ACCESS_TOKEN);
        verify(userRedisAtomicOps, never()).incrementWithTtl(startsWith(RedisKeyConstant.LOGIN_IP_PREFIX), any(Duration.class));
    }

    /**
     * 驗證：register 帶 client IP 且 IP 計數未達上限時，應正常註冊。
     */
    @Test
    @DisplayName("register(ip) → IP 計數未達上限 → 應正常儲存用戶")
    void register_whenIpRateLimitNotExceeded_shouldSaveUser() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRedisAtomicOps.incrementWithTtl(eq(RedisKeyConstant.getRegisterIpKey(TEST_IP)), any(Duration.class))).thenReturn(2L);
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(false);
        when(userRepository.existsByNickname(TEST_NICKNAME)).thenReturn(false);
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        User savedUser = buildActiveUser();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(verificationTokenRepository.save(any(VerificationToken.class))).thenReturn(new VerificationToken());

        authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME, TEST_IP);

        verify(userRepository).save(any(User.class));
        verify(userRedisAtomicOps).incrementWithTtl(eq(RedisKeyConstant.getRegisterIpKey(TEST_IP)), any(Duration.class));
    }

    /**
     * 驗證：register 的 IP 計數超過上限（> REGISTER_IP_MAX）時，應拋出 RATE_LIMIT_EXCEEDED，
     * 且不再進行唯一性檢查與儲存。
     */
    @Test
    @DisplayName("register(ip) → IP 計數超過上限 → 應拋出 RATE_LIMIT_EXCEEDED 且不儲存")
    void register_whenIpRateLimitExceeded_shouldThrowRateLimitExceeded() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRedisAtomicOps.incrementWithTtl(eq(RedisKeyConstant.getRegisterIpKey(TEST_IP)), any(Duration.class)))
                .thenReturn((long) (RedisKeyConstant.REGISTER_IP_MAX + 1));

        assertThatThrownBy(() -> authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME, TEST_IP))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(UserErrorCode.RATE_LIMIT_EXCEEDED.getCode()));

        verify(userRepository, never()).existsByEmail(anyString());
        verify(userRepository, never()).save(any());
    }

    /**
     * 驗證：register 首次 IP 計數（increment == 1）應設定 IP 限流 key 的 TTL。
     */
    @Test
    @DisplayName("register(ip) → 首次 IP 計數（count == 1） → 應設定 IP 限流 TTL")
    void register_firstIpHit_shouldSetIpKeyTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRedisAtomicOps.incrementWithTtl(eq(RedisKeyConstant.getRegisterIpKey(TEST_IP)), any(Duration.class))).thenReturn(1L);
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(false);
        when(userRepository.existsByNickname(TEST_NICKNAME)).thenReturn(false);
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        User savedUser = buildActiveUser();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(verificationTokenRepository.save(any(VerificationToken.class))).thenReturn(new VerificationToken());

        authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME, TEST_IP);

        verify(userRedisAtomicOps).incrementWithTtl(eq(RedisKeyConstant.getRegisterIpKey(TEST_IP)),
                eq(Duration.ofMinutes(RedisKeyConstant.REGISTER_IP_TTL_MINUTES)));
    }

    /**
     * 驗證：register 帶 null IP（無法解析 client IP）時，應跳過 IP 限流，沿用既有 4-arg 行為。
     */
    @Test
    @DisplayName("register(null ip) → 應跳過 IP 限流，正常註冊")
    void register_withNullIp_shouldSkipIpRateLimit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRepository.existsByEmail(TEST_EMAIL)).thenReturn(false);
        when(userRepository.existsByNickname(TEST_NICKNAME)).thenReturn(false);
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("encodedPassword");
        User savedUser = buildActiveUser();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(verificationTokenRepository.save(any(VerificationToken.class))).thenReturn(new VerificationToken());

        authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME, null);

        verify(userRepository).save(any(User.class));
        verify(userRedisAtomicOps, never()).incrementWithTtl(startsWith(RedisKeyConstant.REGISTER_IP_PREFIX), any(Duration.class));
    }

    /* =========================================================================
       incrementVersion 測試
       ========================================================================= */

    /**
     * 驗證：incrementVersion 應正確處理多位數進位（v9 → v10）。
     */
    @Test
    @DisplayName("incrementVersion → v9 → 應回傳 v10（多位數進位）")
    void incrementVersion_v9_shouldReturnV10() {
        assertThat(AuthService.incrementVersion("v9")).isEqualTo("v10");
    }

    /**
     * 驗證：incrementVersion 面對格式異常輸入時應防禦性地回傳 v1。
     */
    @Test
    @DisplayName("incrementVersion → 格式異常（null / 非v前綴 / 非數字） → 應回傳 v1（防禦性處理）")
    void incrementVersion_invalidFormat_shouldReturnV1() {
        assertThat(AuthService.incrementVersion(null)).isEqualTo("v1");
        assertThat(AuthService.incrementVersion("invalid")).isEqualTo("v1");
        assertThat(AuthService.incrementVersion("vX")).isEqualTo("v1");
    }

    /* =========================================================================
       測試輔助方法
       ========================================================================= */

    /**
     * 建立一個 ACTIVE 狀態的測試用 User 物件。
     *
     * @return 已設定基本欄位的 User 實體
     */
    private User buildActiveUser() {
        User user = new User();
        user.setId(1L);
        user.setUuid(USER_UUID);
        user.setEmail(TEST_EMAIL);
        user.setPasswordHash("encodedPassword");
        user.setNickname(TEST_NICKNAME);
        user.setRole(Role.USER);
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion("v1");
        return user;
    }

    /**
     * 建立一個密碼重設 Token 物件。
     *
     * @param tokenStr  Token 字串
     * @param expiresAt 過期時間
     * @return VerificationToken 實體
     */
    private VerificationToken buildResetToken(String tokenStr, LocalDateTime expiresAt) {
        VerificationToken token = new VerificationToken();
        token.setId(1L);
        token.setUserId(1L);
        token.setToken(tokenStr);
        token.setType("PASSWORD_RESET");
        token.setExpiresAt(expiresAt);
        return token;
    }

    /**
     * 建立一個電子信箱驗證 Token 物件。
     *
     * @param tokenStr  Token 字串
     * @param expiresAt 過期時間
     * @return VerificationToken 實體
     */
    // =========================================================================
    // uuid 化：JWT subject、auth hash、Redis key
    // =========================================================================

    /**
     * 驗證：login 寫入的 auth hash 以 uuid 為鍵，內容為 version／status／role 三個欄位（Z1 起不含 id）。
     */
    @Test
    @DisplayName("login → auth hash 以 uuid 為鍵，且以一次 putAll 寫入 version／status／role 三個欄位")
    @SuppressWarnings("unchecked")
    void login_shouldWriteThreeFieldsIntoAuthHashKeyedByUuid() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard(anyString())).thenReturn(1L);
        User mockUser = buildActiveUser();
        mockUser.setRole(Role.AUTHOR);
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateAccessToken(any(UUID.class), anyString(), anyString())).thenReturn(MOCK_ACCESS_TOKEN);
        when(jwtService.generateRefreshToken(any(UUID.class))).thenReturn(MOCK_REFRESH_TOKEN);

        authService.login(TEST_EMAIL, TEST_PASSWORD);

        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq("user:auth:" + USER_UUID), fields.capture());
        assertThat(fields.getValue()).containsExactlyInAnyOrderEntriesOf(Map.of(
                RedisKeyConstant.FIELD_VERSION, "v1",
                RedisKeyConstant.FIELD_STATUS, "ACTIVE",
                RedisKeyConstant.FIELD_ROLE, "AUTHOR"));
    }

    /**
     * 驗證（Z1）：login 寫入 auth hash 時不再帶 {@code id} 欄位，只寫 version／status／role。
     */
    @Test
    @DisplayName("Z1：login → auth hash 不含 id 欄位")
    @SuppressWarnings("unchecked")
    void login_shouldNotWriteIdIntoAuthHash() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard(anyString())).thenReturn(1L);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateAccessToken(any(UUID.class), anyString(), anyString())).thenReturn(MOCK_ACCESS_TOKEN);
        when(jwtService.generateRefreshToken(any(UUID.class))).thenReturn(MOCK_REFRESH_TOKEN);

        authService.login(TEST_EMAIL, TEST_PASSWORD);

        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq("user:auth:" + USER_UUID), fields.capture());
        assertThat(fields.getValue()).containsOnlyKeys(
                RedisKeyConstant.FIELD_VERSION, RedisKeyConstant.FIELD_STATUS, RedisKeyConstant.FIELD_ROLE);
    }

    /**
     * 驗證：login 簽發的 access／refresh token 以 uuid 為 subject（傳給 JwtService 的是 uuid）。
     */
    @Test
    @DisplayName("login → 以使用者 uuid 簽發 access 與 refresh token")
    void login_shouldIssueTokensWithUserUuid() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard(anyString())).thenReturn(1L);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateAccessToken(any(UUID.class), anyString(), anyString())).thenReturn(MOCK_ACCESS_TOKEN);
        when(jwtService.generateRefreshToken(any(UUID.class))).thenReturn(MOCK_REFRESH_TOKEN);

        authService.login(TEST_EMAIL, TEST_PASSWORD);

        verify(jwtService).generateAccessToken(USER_UUID, "USER", "v1");
        verify(jwtService).generateRefreshToken(USER_UUID);
        verify(userRedisAtomicOps).registerRefreshToken(eq("user:refresh:" + USER_UUID), eq(MOCK_REFRESH_TOKEN),
                anyLong(), eq(3), eq(Duration.ofDays(7)));
    }

    /**
     * 驗證：登入失敗計數與 logout 的 Redis key 皆以 uuid 為鍵。
     */
    @Test
    @DisplayName("login 密碼錯誤 → 失敗計數 key 以 uuid 為鍵")
    void login_wrongPassword_shouldCountFailuresByUuidKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(TEST_EMAIL, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class);

        verify(userRedisAtomicOps).incrementWithTtl(eq("login:fail:" + USER_UUID), any(Duration.class));
    }

    /**
     * 驗證：logout 登出所有裝置時刪除的是 uuid 版 refresh key。
     */
    @Test
    @DisplayName("logout → 刪除 uuid 版 refresh key")
    void logout_shouldDeleteUuidKeyedRefreshZSet() {
        authService.logout(USER_UUID, null);

        verify(redisTemplate).delete("user:refresh:" + USER_UUID);
    }

    // =========================================================================
    // user:auth TTL 驗證
    // =========================================================================

    /**
     * 驗證：login 成功後應對 user:auth key 設定 7 天 TTL。
     */
    @Test
    @DisplayName("login → 成功後應設定 user:auth TTL 為 7 天")
    void login_success_shouldSetAuthKeyTtl() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.zCard(anyString())).thenReturn(1L);
        User mockUser = buildActiveUser();
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateAccessToken(any(UUID.class), anyString(), anyString())).thenReturn(MOCK_ACCESS_TOKEN);
        when(jwtService.generateRefreshToken(any(UUID.class))).thenReturn(MOCK_REFRESH_TOKEN);

        authService.login(TEST_EMAIL, TEST_PASSWORD);

        String expectedAuthKey = RedisKeyConstant.getUserAuthKey(mockUser.getUuid());
        verify(redisTemplate).expire(eq(expectedAuthKey), eq(RedisKeyConstant.USER_AUTH_TTL_DAYS), eq(java.util.concurrent.TimeUnit.DAYS));
    }

    private VerificationToken buildEmailVerificationToken(String tokenStr, LocalDateTime expiresAt) {
        VerificationToken token = new VerificationToken();
        token.setId(2L);
        token.setUserId(1L);
        token.setToken(tokenStr);
        token.setType("EMAIL_VERIFICATION");
        token.setExpiresAt(expiresAt);
        return token;
    }
}
