package dowob.xyz.blog.module.user.controller;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.common.util.SecurityUtils;
import dowob.xyz.blog.infrastructure.security.AuthState;
import dowob.xyz.blog.infrastructure.security.AuthStateLoader;
import dowob.xyz.blog.infrastructure.security.JwtAuthenticationFilter;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.dto.response.AuthResponse;
import dowob.xyz.blog.module.user.model.dto.response.LoginResult;
import dowob.xyz.blog.module.user.repository.UserRepository;
import dowob.xyz.blog.module.user.service.AbstractIntegrationTest;
import dowob.xyz.blog.module.user.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * refresh 與 {@link AuthStateLoader} 的整合測試（真實 PostgreSQL + Redis）
 *
 * <p>重點是 SEC-26：Redis 的 auth hash 被刪除（或過期）後，refresh 必須由 loader 查 DB 回填並成功，
 * 而不是誤判為 ACCOUNT_SUSPENDED；非 ACTIVE 帳號則仍被 refresh 拒絕。</p>
 *
 * @author Yuan
 * @version 1.0
 */
class AuthRefreshIntegrationTest extends AbstractIntegrationTest {

    /** 受測 controller */
    @Autowired
    private AuthController authController;

    /** 登入服務（建立 refresh token 與 Redis 狀態） */
    @Autowired
    private AuthService authService;

    /** 受測 loader */
    @Autowired
    private AuthStateLoader authStateLoader;

    /** JWT 服務（解析 refresh 後簽出的 access token） */
    @Autowired
    private JwtService jwtService;

    /** 用戶資料存取 */
    @Autowired
    private UserRepository userRepository;

    /** Redis 模板 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 密碼加密器 */
    @Autowired
    private PasswordEncoder passwordEncoder;

    /** 舊版 auth hash 曾帶的使用者主鍵欄位名稱（Z1 已不再讀寫；向下相容測試用字面值） */
    private static final String LEGACY_ID_FIELD = "id";

    /** 測試用電子信箱 */
    private static final String TEST_EMAIL = "refresh-it@example.com";

    /** 測試用密碼 */
    private static final String TEST_PASSWORD = "password123";

    /**
     * 清理測試帳號與其 Redis 狀態。
     */
    @AfterEach
    void cleanUp() {
        userRepository.findByEmail(TEST_EMAIL).ifPresent(user -> {
            redisTemplate.delete(RedisKeyConstant.getUserAuthKey(user.getUuid()));
            redisTemplate.delete(RedisKeyConstant.getUserRefreshKey(user.getUuid()));
            userRepository.delete(user);
        });
    }

    @Test
    @DisplayName("SEC-26：登入後 Redis auth hash 被刪除 → refresh 仍成功，且 hash 由 DB 回填（status／role／version，不含 id）")
    void refresh_afterAuthHashDeleted_shouldSucceedAndBackfill() {
        User user = saveUser(UserStatus.ACTIVE, Role.AUTHOR);
        LoginResult login = authService.login(TEST_EMAIL, TEST_PASSWORD);
        String authKey = RedisKeyConstant.getUserAuthKey(user.getUuid());
        redisTemplate.delete(authKey);

        ApiResponse<AuthResponse> response = authController.refresh(login.refreshToken());

        String newAccessToken = response.getData().getAccessToken();
        assertThat(jwtService.getUserUuidFromToken(newAccessToken)).isEqualTo(user.getUuid());
        assertThat(jwtService.getRoleFromToken(newAccessToken)).isEqualTo(Role.AUTHOR);
        assertThat(jwtService.getVersionFromToken(newAccessToken)).isEqualTo("v1");
        Map<Object, Object> refilled = redisTemplate.opsForHash().entries(authKey);
        assertThat(refilled)
                .doesNotContainKey("id")
                .containsEntry(RedisKeyConstant.FIELD_STATUS, "ACTIVE")
                .containsEntry(RedisKeyConstant.FIELD_ROLE, "AUTHOR")
                .containsEntry(RedisKeyConstant.FIELD_VERSION, "v1");
    }

    @Test
    @DisplayName("Redis hash 缺 version、DB 的 version 為 v3 → refresh 新發的 access token 帶 v3，hash 回填 v3")
    void refresh_hashMissingVersionAndDbVersionV3_shouldIssueTokenWithV3() {
        User user = saveUser(UserStatus.ACTIVE, Role.USER);
        LoginResult login = authService.login(TEST_EMAIL, TEST_PASSWORD);
        user.setTokenVersion("v3");
        userRepository.save(user);
        String authKey = RedisKeyConstant.getUserAuthKey(user.getUuid());
        redisTemplate.opsForHash().delete(authKey, RedisKeyConstant.FIELD_VERSION);

        ApiResponse<AuthResponse> response = authController.refresh(login.refreshToken());

        assertThat(jwtService.getVersionFromToken(response.getData().getAccessToken())).isEqualTo("v3");
        assertThat(redisTemplate.opsForHash().get(authKey, RedisKeyConstant.FIELD_VERSION)).isEqualTo("v3");
    }

    @Test
    @DisplayName("Redis auth hash 被刪除且帳號已 BANNED → refresh 仍被拒絕（ACCOUNT_SUSPENDED）")
    void refresh_afterAuthHashDeletedAndUserBanned_shouldBeRejected() {
        User user = saveUser(UserStatus.ACTIVE, Role.USER);
        LoginResult login = authService.login(TEST_EMAIL, TEST_PASSWORD);
        user.setStatus(UserStatus.BANNED);
        userRepository.save(user);
        redisTemplate.delete(RedisKeyConstant.getUserAuthKey(user.getUuid()));

        assertThatThrownBy(() -> authController.refresh(login.refreshToken()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.ACCOUNT_SUSPENDED));
    }

    @Test
    @DisplayName("Z1 loader：hash 只有 status／role／version（不含 id）→ 命中，採用 hash 內容，不回填 id")
    void load_hashWithoutId_shouldBeCacheHitAndNotAddId() {
        User user = saveUser(UserStatus.ACTIVE, Role.USER);
        String authKey = RedisKeyConstant.getUserAuthKey(user.getUuid());
        redisTemplate.opsForHash().put(authKey, RedisKeyConstant.FIELD_VERSION, "hash-v9");
        redisTemplate.opsForHash().put(authKey, RedisKeyConstant.FIELD_STATUS, "ACTIVE");
        redisTemplate.opsForHash().put(authKey, RedisKeyConstant.FIELD_ROLE, "USER");

        Optional<AuthState> state = authStateLoader.load(user.getUuid());

        assertThat(state).isPresent();
        assertThat(state.get().tokenVersion()).isEqualTo("hash-v9");
        assertThat(redisTemplate.opsForHash().hasKey(authKey, LEGACY_ID_FIELD)).isFalse();
    }

    @Test
    @DisplayName("Z1 向下相容：舊格式 hash（仍帶 id 欄位）→ 命中且忽略 id，不判停權、不被覆寫")
    void load_legacyHashWithIdField_shouldStillBeCacheHit() {
        User user = saveUser(UserStatus.ACTIVE, Role.USER);
        String authKey = RedisKeyConstant.getUserAuthKey(user.getUuid());
        redisTemplate.opsForHash().put(authKey, LEGACY_ID_FIELD, "999999");
        redisTemplate.opsForHash().put(authKey, RedisKeyConstant.FIELD_VERSION, "legacy-v2");
        redisTemplate.opsForHash().put(authKey, RedisKeyConstant.FIELD_STATUS, "ACTIVE");
        redisTemplate.opsForHash().put(authKey, RedisKeyConstant.FIELD_ROLE, "USER");

        Optional<AuthState> state = authStateLoader.load(user.getUuid());

        assertThat(state).isPresent();
        assertThat(state.get().status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(state.get().tokenVersion()).isEqualTo("legacy-v2");
        assertThat(redisTemplate.opsForHash().get(authKey, RedisKeyConstant.FIELD_VERSION)).isEqualTo("legacy-v2");
    }

    @Test
    @DisplayName("Z1 向下相容：舊格式 hash（帶 id）+ 有效 JWT → JwtAuthenticationFilter 仍認證成功，principal 為該 uuid")
    void filter_legacyHashWithIdField_shouldStillAuthenticate() throws Exception {
        User user = saveUser(UserStatus.ACTIVE, Role.AUTHOR);
        String authKey = RedisKeyConstant.getUserAuthKey(user.getUuid());
        redisTemplate.opsForHash().put(authKey, LEGACY_ID_FIELD, String.valueOf(user.getId()));
        redisTemplate.opsForHash().put(authKey, RedisKeyConstant.FIELD_VERSION, "v1");
        redisTemplate.opsForHash().put(authKey, RedisKeyConstant.FIELD_STATUS, "ACTIVE");
        redisTemplate.opsForHash().put(authKey, RedisKeyConstant.FIELD_ROLE, "AUTHOR");
        String accessToken = jwtService.generateAccessToken(user.getUuid(), "AUTHOR", "v1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + accessToken);
        SecurityContextHolder.clearContext();

        try {
            new JwtAuthenticationFilter(jwtService, authStateLoader)
                    .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication).isNotNull();
            assertThat(SecurityUtils.currentAuthUser(authentication).map(AuthUser::uuid)).contains(user.getUuid());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("loader：Redis 與 DB 都查不到 → 回傳 empty，且不建立 Redis hash")
    void load_unknownUuid_shouldReturnEmptyWithoutCaching() {
        UUID unknown = UUID.randomUUID();

        assertThat(authStateLoader.load(unknown)).isEmpty();
        assertThat(redisTemplate.hasKey(RedisKeyConstant.getUserAuthKey(unknown))).isFalse();
    }

    /**
     * 直接存入資料庫建立測試帳號。
     *
     * @param status 帳號狀態
     * @param role   角色
     * @return 已儲存的使用者
     */
    private User saveUser(UserStatus status, Role role) {
        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setEmail(TEST_EMAIL);
        user.setUsername("refreshituser");
        user.setNickname("refreshItUser");
        user.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
        user.setRole(role);
        user.setStatus(status);
        user.setTokenVersion("v1");
        return userRepository.save(user);
    }
}
