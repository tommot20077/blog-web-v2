package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.VerificationToken;
import dowob.xyz.blog.module.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 整合測試
 *
 * <p>
 * 使用 Testcontainers 啟動真實的 PostgreSQL 與 Redis 容器，
 * 驗證 AuthService 與 UserService 的完整業務流程（含資料庫持久化與 Redis 快取互動）。
 * VerificationTokenRepository 為 MockBean，因整合測試著重於 User 狀態與 Redis 驗證。
 * </p>
 *
 * @author Yuan
 * @version 2.0
 */
class AuthServiceIntegrationTest extends AbstractIntegrationTest {

    /** 受測服務 */
    @Autowired
    private AuthService authService;

    /** 用戶自助服務（用於 changePassword / deleteAccount 整合測試） */
    @Autowired
    private UserService userService;

    /** 用於驗證資料庫狀態 */
    @Autowired
    private UserRepository userRepository;

    /** 用於驗證 Redis 狀態 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 用於建立測試帳號的密碼加密器 */
    @Autowired
    private PasswordEncoder passwordEncoder;

    /** 測試用電子信箱 */
    private static final String TEST_EMAIL = "integration@example.com";

    /** 測試用密碼 */
    private static final String TEST_PASSWORD = "password123";

    /** 測試用用戶名 */
    private static final String TEST_USERNAME = "integrationuser";

    /** 測試用暱稱 */
    private static final String TEST_NICKNAME = "integrationUser";

    /**
     * 每個測試後清理資料庫與 Redis，確保測試隔離。
     */
    @AfterEach
    void cleanUp() {
        userRepository.findByEmail(TEST_EMAIL).ifPresent(user -> {
            userRepository.delete(user);
            redisTemplate.delete(RedisKeyConstant.getUserAuthKey(user.getId()));
            redisTemplate.delete(RedisKeyConstant.getUserRefreshKey(user.getId()));
        });
    }

    // =========================================================================
    // AuthService 整合測試
    // =========================================================================

    /**
     * 驗證：呼叫 register() 後，用戶應真實存入 PostgreSQL 資料庫。
     */
    @Test
    @DisplayName("register → 應將用戶持久化至資料庫")
    void register_shouldPersistUserToDatabase() {
        authService.register(TEST_EMAIL, TEST_PASSWORD, TEST_USERNAME, TEST_NICKNAME);

        Optional<User> saved = userRepository.findByEmail(TEST_EMAIL);

        assertThat(saved).isPresent();
        assertThat(saved.get().getEmail()).isEqualTo(TEST_EMAIL);
        assertThat(saved.get().getNickname()).isEqualTo(TEST_NICKNAME);
        assertThat(saved.get().getRole()).isEqualTo(Role.USER);
        assertThat(saved.get().getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
    }

    /**
     * 驗證：呼叫 login() 後，Redis Hash {@code user:auth:{id}} 應包含
     * {@code version} 與 {@code status} 兩個欄位，且 status 應為 ACTIVE。
     */
    @Test
    @DisplayName("login → 應將 version 與 status 寫入 Redis Hash")
    void login_shouldSetVersionInRedis() {
        User user = buildAndSaveUser(UserStatus.ACTIVE);
        String redisKey = RedisKeyConstant.getUserAuthKey(user.getId());

        authService.login(TEST_EMAIL, TEST_PASSWORD);

        Map<Object, Object> entries = redisTemplate.opsForHash().entries(redisKey);
        assertThat(entries).containsKey(RedisKeyConstant.FIELD_VERSION);
        assertThat(entries).containsKey(RedisKeyConstant.FIELD_STATUS);
        assertThat(entries.get(RedisKeyConstant.FIELD_STATUS)).isEqualTo(UserStatus.ACTIVE.name());
    }

    /**
     * 驗證：呼叫 verifyEmail() 後，用戶狀態應更新為 ACTIVE、emailVerified=true，
     * 且 verificationTokenRepository.delete() 應被呼叫（整合驗證 Token 刪除行為）。
     */
    @Test
    @DisplayName("verifyEmail → 整合測試：應將用戶狀態設為 ACTIVE 並標記信箱已驗證")
    void verifyEmail_shouldActivateUserInDatabase() {
        User user = buildAndSaveUser(UserStatus.PENDING_VERIFICATION);
        String tokenValue = "integration-email-token";

        VerificationToken mockToken = new VerificationToken();
        mockToken.setId(99L);
        mockToken.setUserId(user.getId());
        mockToken.setToken(tokenValue);
        mockToken.setType("EMAIL_VERIFICATION");
        mockToken.setExpiresAt(LocalDateTime.now().plusHours(24));

        when(verificationTokenRepository.findByTokenAndType(tokenValue, "EMAIL_VERIFICATION"))
                .thenReturn(Optional.of(mockToken));

        authService.verifyEmail(tokenValue);

        User updated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(updated.isEmailVerified()).isTrue();
        verify(verificationTokenRepository).delete(any(VerificationToken.class));
    }

    /**
     * 驗證：DELETED 帳號呼叫 login() 應拋出 BusinessException（整合驗證帳號封鎖邏輯）。
     */
    @Test
    @DisplayName("login → DELETED 帳號 → 整合測試：應拋出 BusinessException 拒絕登入")
    void login_withDeletedAccount_shouldRejectLogin() {
        buildAndSaveUser(UserStatus.DELETED);

        assertThatThrownBy(() -> authService.login(TEST_EMAIL, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class);
    }

    // =========================================================================
    // UserService 整合測試
    // =========================================================================

    /**
     * 驗證：changePassword() 後，Redis {@code user:auth:{id}} 中的版本號應同步更新。
     */
    @Test
    @DisplayName("changePassword → 整合測試：Redis user:auth:{id} 版本應由 v1 更新為 v2")
    void changePassword_shouldSyncVersionInRedis() {
        User user = buildAndSaveUser(UserStatus.ACTIVE);
        String authKey = RedisKeyConstant.getUserAuthKey(user.getId());

        redisTemplate.opsForHash().put(authKey, RedisKeyConstant.FIELD_VERSION, "v1");
        redisTemplate.opsForHash().put(authKey, RedisKeyConstant.FIELD_STATUS, UserStatus.ACTIVE.name());

        userService.changePassword(user.getId(), TEST_PASSWORD, "newPassword456");

        String version = (String) redisTemplate.opsForHash().get(authKey, RedisKeyConstant.FIELD_VERSION);
        assertThat(version).isEqualTo("v2");
    }

    /**
     * 驗證：deleteAccount() 後，Redis 中 user:auth:{id} 與 refresh:token:{id} 兩個鍵應被清除。
     */
    @Test
    @DisplayName("deleteAccount → 整合測試：Redis user:auth:{id} 與 refresh:token:{id} 應被清除")
    void deleteAccount_shouldClearRedisCache() {
        User user = buildAndSaveUser(UserStatus.ACTIVE);
        String authKey = RedisKeyConstant.getUserAuthKey(user.getId());
        String refreshKey = RedisKeyConstant.getUserRefreshKey(user.getId());

        redisTemplate.opsForHash().put(authKey, RedisKeyConstant.FIELD_VERSION, "v1");
        redisTemplate.opsForValue().set(refreshKey, "some-refresh-token");

        userService.deleteAccount(user.getId(), TEST_PASSWORD);

        assertThat(redisTemplate.hasKey(authKey)).isFalse();
        assertThat(redisTemplate.hasKey(refreshKey)).isFalse();
    }

    // =========================================================================
    // 測試輔助方法
    // =========================================================================

    /**
     * 直接存入資料庫建立指定狀態的測試用戶。
     *
     * @param status 用戶狀態
     * @return 已儲存並帶有 ID 的 User 實體
     */
    private User buildAndSaveUser(UserStatus status) {
        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setEmail(TEST_EMAIL);
        user.setUsername(TEST_USERNAME);
        user.setNickname(TEST_NICKNAME);
        user.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
        user.setRole(Role.USER);
        user.setStatus(status);
        user.setTokenVersion("v1");
        return userRepository.save(user);
    }
}
