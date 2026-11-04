package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.infrastructure.event.UserEventContract;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.module.user.TestUserModuleApplication;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 刪除帳號（匿名化，DATA-02）整合測試：真 PostgreSQL ＋ 真 Redis。
 *
 * <p>與 {@link AbstractIntegrationTest} 的差異：<b>不</b> mock {@code VerificationTokenRepository}，
 * 才能驗證 {@code verification_tokens} 真的被清空；{@code RabbitTemplate} 仍為 mock，用來驗證事件
 * 「在 commit 之後才發送」與「rollback 不發送」。Postgres／Redis 容器沿用 {@link AbstractIntegrationTest} 的 singleton。</p>
 *
 * <p>命名為 {@code *IntegrationTest}：blog-module-user 的 surefire 沒有 {@code *IT} 的 includes。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = TestUserModuleApplication.class)
@ActiveProfiles("test")
@DisplayName("刪除帳號匿名化 整合測試（真 Postgres／Redis）")
class UserAccountDeletionIntegrationTest {

    /** 測試用明文密碼 */
    private static final String PASSWORD = "password123";

    /**
     * 註冊容器連線屬性（沿用 {@link AbstractIntegrationTest} 的 singleton 容器）。
     *
     * @param registry 動態屬性註冊器
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractIntegrationTest.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractIntegrationTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", AbstractIntegrationTest.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", AbstractIntegrationTest.REDIS::getHost);
        registry.add("spring.data.redis.port", AbstractIntegrationTest.REDIS::getFirstMappedPort);
    }

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private JavaMailSender mailSender;

    @Autowired private UserService userService;
    @Autowired private AuthService authService;
    @Autowired private UserFacade userFacade;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private JdbcTemplate jdbc;

    /** 本測試建立的使用者，供清理 */
    private final List<User> created = new ArrayList<>();

    /** 清除本測試建立的資料與可能遺留的失敗觸發器。 */
    @AfterEach
    void cleanUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_user_update ON users");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_user_update()");
        for (User user : created) {
            jdbc.update("DELETE FROM verification_tokens WHERE user_id = ?", user.getId());
            userRepository.findById(user.getId()).ifPresent(userRepository::delete);
            redisTemplate.delete(RedisKeyConstant.getUserAuthKey(user.getUuid()));
            redisTemplate.delete(RedisKeyConstant.getUserRefreshKey(user.getUuid()));
        }
        created.clear();
        Mockito.reset(rabbitTemplate);
    }

    @Test
    @DisplayName("刪除帳號：users 列的 PII 全被洗掉，狀態 DELETED，密碼雜湊不再匹配原密碼")
    void deleteAccount_anonymizesUsersRow() {
        User user = createActiveUser("alice-del");

        userService.deleteAccount(user.getUuid(), PASSWORD);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM users WHERE uuid = ?", user.getUuid());
        assertThat(row.get("status")).isEqualTo("DELETED");
        assertThat((String) row.get("nickname")).matches("已刪除使用者#[0-9a-f]{8}")
                .isEqualTo("已刪除使用者#" + user.getUuid().toString().substring(0, 8));
        assertThat(row.get("username")).isEqualTo("deleted_" + user.getUuid());
        assertThat(row.get("email")).isEqualTo("deleted+" + user.getUuid() + "@invalid.invalid");
        assertThat(row.get("avatar_url")).isNull();
        assertThat(row.get("bio")).isNull();
        assertThat(row.get("website")).isNull();
        assertThat(row.get("social_links")).isNull();
        assertThat(row.get("location")).isNull();
        assertThat(row.get("email_verified")).isEqualTo(false);
        assertThat(row.get("notification_newsletter")).isEqualTo(false);
        assertThat(passwordEncoder.matches(PASSWORD, (String) row.get("password_hash"))).isFalse();
        assertThat(row.get("token_version")).isEqualTo("v2");
        /* 不是 PII、也不該被動到的欄位 */
        assertThat(row.get("role")).isEqualTo(Role.USER.name());
        assertThat(row.get("uuid")).isEqualTo(user.getUuid());
    }

    @Test
    @DisplayName("兩個帳號先後刪除：都成功，且匿名暱稱互不相同（不撞 uq_users_nickname）")
    void deleteAccount_twoAccountsInSequence_getDistinctNicknames() {
        User first = createActiveUser("first-del");
        User second = createActiveUser("second-del");

        userService.deleteAccount(first.getUuid(), PASSWORD);
        userService.deleteAccount(second.getUuid(), PASSWORD);

        String firstNickname = nicknameOf(first.getUuid());
        String secondNickname = nicknameOf(second.getUuid());
        assertThat(firstNickname).startsWith("已刪除使用者#");
        assertThat(secondNickname).startsWith("已刪除使用者#").isNotEqualTo(firstNickname);
    }

    @Test
    @DisplayName("暱稱／帳號／信箱的墓碑值被他人預先註冊卡位：刪帳仍成功並改用備援值")
    void deleteAccount_tombstoneValuesSquattedByOthers_stillSucceeds() {
        User victim = createActiveUser("victim-del");
        User squatter = createActiveUser("squatter-del");
        squatter.setNickname("已刪除使用者#" + victim.getUuid().toString().substring(0, 8));
        squatter.setUsername("deleted_" + victim.getUuid());
        squatter.setEmail("deleted+" + victim.getUuid() + "@invalid.invalid");
        userRepository.save(squatter);

        userService.deleteAccount(victim.getUuid(), PASSWORD);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM users WHERE uuid = ?", victim.getUuid());
        assertThat(row.get("status")).isEqualTo("DELETED");
        assertThat(row.get("nickname")).isEqualTo("已刪除使用者#" + victim.getUuid().toString().replace("-", ""));
        assertThat((String) row.get("username")).startsWith("deleted_").isNotEqualTo(squatter.getUsername());
        assertThat((String) row.get("email")).endsWith("@invalid.invalid").isNotEqualTo(squatter.getEmail());
    }

    @Test
    @DisplayName("刪除後無法登入：原信箱／原帳號找不到人，墓碑信箱配原密碼也不通過")
    void deleteAccount_thenCannotLoginByAnyIdentity() {
        User user = createActiveUser("login-del");
        String originalEmail = user.getEmail();
        String originalUsername = user.getUsername();

        userService.deleteAccount(user.getUuid(), PASSWORD);

        assertThatThrownBy(() -> authService.login(originalEmail, PASSWORD)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> authService.login(originalUsername, PASSWORD)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> authService.login("deleted+" + user.getUuid() + "@invalid.invalid", PASSWORD))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> authService.login("deleted_" + user.getUuid(), PASSWORD))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("刪除帳號：該使用者的 verification_tokens 全部清空，其他人的不受影響")
    void deleteAccount_clearsOwnVerificationTokensOnly() {
        User user = createActiveUser("token-del");
        User other = createActiveUser("token-other");
        insertToken(user.getId(), "EMAIL_VERIFICATION");
        insertToken(user.getId(), "PASSWORD_RESET");
        insertToken(other.getId(), "PASSWORD_RESET");

        userService.deleteAccount(user.getUuid(), PASSWORD);

        assertThat(countTokens(user.getId())).isZero();
        assertThat(countTokens(other.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("刪除帳號：commit 之後才發送 UserDeletedEvent（發送當下從另一條連線已看得到 DELETED）")
    void deleteAccount_publishesEventOnlyAfterCommit() {
        User user = createActiveUser("event-del");
        AtomicReference<String> statusSeenWhenPublished = new AtomicReference<>();
        doAnswer(invocation -> {
            statusSeenWhenPublished.set(jdbc.queryForObject(
                    "SELECT status FROM users WHERE uuid = ?", String.class, user.getUuid()));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq(UserEventContract.EXCHANGE),
                eq(UserEventContract.ROUTING_KEY_DELETED), any(Object.class));

        userService.deleteAccount(user.getUuid(), PASSWORD);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(
                eq(UserEventContract.EXCHANGE), eq(UserEventContract.ROUTING_KEY_DELETED), event.capture());
        assertThat(event.getValue()).isInstanceOf(UserDeletedEvent.class);
        assertThat(((UserDeletedEvent) event.getValue()).userUuid()).isEqualTo(user.getUuid());
        assertThat(statusSeenWhenPublished.get()).as("發送事件時交易已 commit").isEqualTo("DELETED");
    }

    @Test
    @DisplayName("交易 rollback（users 更新失敗）：不發送事件、資料不變、Redis session 不被撤銷")
    void deleteAccount_whenTransactionRollsBack_publishesNothingAndKeepsSessions() {
        User user = createActiveUser("rollback-del");
        String authKey = RedisKeyConstant.getUserAuthKey(user.getUuid());
        redisTemplate.opsForHash().put(authKey, RedisKeyConstant.FIELD_VERSION, "v1");
        jdbc.execute("CREATE FUNCTION fail_user_update() RETURNS trigger AS $$ "
                + "BEGIN RAISE EXCEPTION 'users update blocked by test'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER trg_fail_user_update BEFORE UPDATE ON users "
                + "FOR EACH ROW EXECUTE FUNCTION fail_user_update()");

        assertThatThrownBy(() -> userService.deleteAccount(user.getUuid(), PASSWORD))
                .isInstanceOf(RuntimeException.class);

        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE uuid = ?", String.class, user.getUuid()))
                .isEqualTo("ACTIVE");
        assertThat(redisTemplate.hasKey(authKey)).as("rollback 時不得撤銷 session").isTrue();
    }

    @Test
    @DisplayName("刪除帳號：commit 後 Redis 的 auth hash 與 refresh ZSet 被清除")
    void deleteAccount_revokesRedisSessionsAfterCommit() {
        User user = createActiveUser("redis-del");
        String authKey = RedisKeyConstant.getUserAuthKey(user.getUuid());
        String refreshKey = RedisKeyConstant.getUserRefreshKey(user.getUuid());
        redisTemplate.opsForHash().put(authKey, RedisKeyConstant.FIELD_VERSION, "v1");
        redisTemplate.opsForZSet().add(refreshKey, "refresh-token", System.currentTimeMillis());

        userService.deleteAccount(user.getUuid(), PASSWORD);

        assertThat(redisTemplate.hasKey(authKey)).isFalse();
        assertThat(redisTemplate.hasKey(refreshKey)).isFalse();
    }

    @Test
    @DisplayName("公開讀取路徑：UserFacade.getDisplayInfoByUuids 對已刪除使用者回傳匿名暱稱、無頭貼")
    void deleteAccount_thenFacadeDisplayInfoIsAnonymous() {
        User user = createActiveUser("facade-del");
        User bystander = createActiveUser("facade-other");

        userService.deleteAccount(user.getUuid(), PASSWORD);

        Map<UUID, UserDisplayInfo> info = userFacade.getDisplayInfoByUuids(List.of(user.getUuid(), bystander.getUuid()));
        assertThat(info.get(user.getUuid()).nickname())
                .isEqualTo("已刪除使用者#" + user.getUuid().toString().substring(0, 8));
        assertThat(info.get(user.getUuid()).avatarUrl()).isNull();
        assertThat(info.get(bystander.getUuid()).nickname()).isEqualTo("facade-other-nick");
        assertThat(info.get(bystander.getUuid()).avatarUrl()).isEqualTo("https://cdn.example.com/facade-other.png");
    }

    @Test
    @DisplayName("M2：刪帳會更新 updated_at；findRecentlyDeletedUuids 只回 48h 內的 DELETED 使用者")
    void findRecentlyDeletedUuids_returnsOnlyRecentDeleted() {
        User recent = createActiveUser("resend-recent");
        User old = createActiveUser("resend-old");
        User active = createActiveUser("resend-active");
        jdbc.update("UPDATE users SET updated_at = ? WHERE uuid = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(10)), recent.getUuid());
        userService.deleteAccount(recent.getUuid(), PASSWORD);
        userService.deleteAccount(old.getUuid(), PASSWORD);
        jdbc.update("UPDATE users SET updated_at = ? WHERE uuid = ?",
                Timestamp.valueOf(LocalDateTime.now().minusHours(49)), old.getUuid());

        List<UUID> found = userRepository.findRecentlyDeletedUuids(LocalDateTime.now().minusHours(48));

        assertThat(found).contains(recent.getUuid()).doesNotContain(old.getUuid(), active.getUuid());
    }

    /**
     * 建立並持久化一位帶完整個資的 ACTIVE 使用者。
     *
     * @param key 用於組成 username／email／nickname 的唯一字串
     * @return 已持久化的使用者
     */
    private User createActiveUser(String key) {
        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setEmail(key + "@example.com");
        user.setUsername(key);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setNickname(key + "-nick");
        user.setAvatarUrl("https://cdn.example.com/" + key + ".png");
        user.setBio("bio of " + key);
        user.setWebsite("https://" + key + ".example.com");
        user.setSocialLinks("{\"twitter\":\"@" + key + "\"}");
        user.setLocation("Taipei");
        user.setRole(Role.USER);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(true);
        user.setTokenVersion("v1");
        User saved = userRepository.save(user);
        created.add(saved);
        return saved;
    }

    /**
     * 讀取使用者目前的暱稱。
     *
     * @param uuid 使用者公開 UUID
     * @return 暱稱
     */
    private String nicknameOf(UUID uuid) {
        return jdbc.queryForObject("SELECT nickname FROM users WHERE uuid = ?", String.class, uuid);
    }

    /**
     * 直接以 SQL 寫入一筆驗證 token。
     *
     * @param userId 使用者主鍵
     * @param type   token 類型
     */
    private void insertToken(Long userId, String type) {
        jdbc.update("INSERT INTO verification_tokens (user_id, token, type, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
                userId, UUID.randomUUID().toString(), type,
                Timestamp.valueOf(LocalDateTime.now().plusHours(1)), Timestamp.valueOf(LocalDateTime.now()));
    }

    /**
     * 計算某使用者的驗證 token 數量。
     *
     * @param userId 使用者主鍵
     * @return token 數量
     */
    private int countTokens(Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM verification_tokens WHERE user_id = ?", Integer.class, userId);
    }
}
