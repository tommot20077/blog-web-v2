package dowob.xyz.blog.module.user.race;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.mq.ThrowingRunnable;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.VerificationToken;
import dowob.xyz.blog.module.user.model.event.UserRegisteredEvent;
import dowob.xyz.blog.module.user.repository.UserRepository;
import dowob.xyz.blog.module.user.service.AbstractIntegrationTest;
import dowob.xyz.blog.module.user.service.AuthService;
import dowob.xyz.blog.module.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * user 模組兩個併發問題的整合測試（真實 Postgres，經 service → 交易，不 mock 任何約束）。
 *
 * <ul>
 *   <li><b>RACE-02</b>：{@code users.nickname} 沒有 DB UNIQUE，註冊與改名以 {@code existsByNickname}
 *       先查再寫 → 併發可產生永久重複暱稱。V34 補 {@code uq_users_nickname}，寫入撞約束轉成
 *       {@code NICKNAME_DUPLICATED}。</li>
 *   <li><b>RACE-03</b>：註冊以 {@code existsByEmail}／{@code existsByUsername} 先查再寫，併發撞到
 *       {@code users} 的 email／username UNIQUE 時無人接住 → 500。改為依約束名稱轉成現行的
 *       {@code EMAIL_DUPLICATED}／{@code USERNAME_DUPLICATED}，與「依序第二次註冊」行為完全一致。</li>
 * </ul>
 *
 * <p><b>讓競態穩定重現</b>：在 {@code users} 上安裝 {@code BEFORE INSERT OR UPDATE} 觸發器睡 0.3 秒。
 * 先查（{@code exists*}）發生在觸發器之前，兩條執行緒都先通過先查，才進入寫入；
 * 因此舊實作在每一輪都會落入競態（註冊另有 BCrypt 運算作為額外的時間差），正確實作在任何交錯下結果都相同，
 * 斷言不會是 flaky 的來源。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("user 併發問題整合測試（RACE-02 暱稱重複、RACE-03 註冊撞 email／username 唯一約束）")
class UserRaceConditionIntegrationTest extends AbstractIntegrationTest {

    /** 併發案例重複的輪數 */
    private static final int ROUNDS = 8;

    /** 本測試建立的 email 前綴，供清理使用 */
    private static final String EMAIL_PREFIX = "race-";

    /** 測試用密碼（符合密碼規則；服務層不驗證格式，但維持真實值） */
    private static final String PASSWORD = "Password123!";

    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    /** 每個測試前後都清掉本測試建立的使用者與觸發器 */
    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS race_test_slow_users ON users");
        jdbc.execute("DROP FUNCTION IF EXISTS race_test_slow_users_write()");
        jdbc.update("DELETE FROM users WHERE email LIKE ?", EMAIL_PREFIX + "%");
    }

    /** 在 users 上安裝寫入時睡 0.3 秒的觸發器，讓兩條執行緒都先通過先查再進入寫入。 */
    private void installSlowWriteTrigger() {
        jdbc.execute("CREATE OR REPLACE FUNCTION race_test_slow_users_write() RETURNS trigger AS $$ "
                + "BEGIN PERFORM pg_sleep(0.3); RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER race_test_slow_users BEFORE INSERT OR UPDATE ON users "
                + "FOR EACH ROW EXECUTE FUNCTION race_test_slow_users_write()");
    }

    /** 一次操作的結果：成功為 {@code OK}，業務例外為其錯誤碼，其他例外為 {@code ERR:類別名}。 */
    private static String outcomeOf(ThrowingRunnable action) {
        try {
            action.run();
            return "OK";
        } catch (BusinessException e) {
            return e.getCode();
        } catch (Exception e) {
            return "ERR:" + e.getClass().getSimpleName();
        }
    }

    /** 讓兩個執行緒同時執行 action（各取一個 index），回傳兩者的結果。 */
    private <T> List<T> runConcurrently(IntFunction<Callable<T>> actionOfThread) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<T> action = actionOfThread.apply(i);
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return action.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 每輪併發註冊前重置會被驗證的 mock（它們在整個測試方法內是同一個實例）。 */
    private void resetRegistrationSideEffectMocks() {
        reset(rabbitTemplate, verificationTokenRepository);
    }

    /**
     * 斷言併發註冊中「輸的那一方」沒有留下任何副作用，且贏的那一方的副作用完整只有一份：
     * <ul>
     *   <li>DB：沒有輸家的 {@code users} 列；{@code verification_tokens} 只儲存過一次，且屬於贏家
     *       （本模組測試基底把 {@code VerificationTokenRepository} 換成 mock，故以其 {@code save} 呼叫驗證，
     *       不查真實資料表）；</li>
     *   <li>MQ：只發出一個註冊事件，事件屬於贏家；</li>
     *   <li>Redis：驗證碼 key 的值是贏家事件的驗證碼；email 不同時，輸家的 email 沒有任何驗證碼 key。</li>
     * </ul>
     *
     * @param outcomes  兩條執行緒的結果（依執行緒編號排序，恰一個為 {@code OK}）
     * @param emails    兩條執行緒各自使用的 email（依執行緒編號，可相同）
     * @param keyColumn 能分辨兩條執行緒的 users 欄位（{@code username} 或 {@code email}，兩者的值必須不同）
     * @param keys      兩條執行緒在 {@code keyColumn} 上各自的值（依執行緒編號）
     */
    private void assertLoserLeftNoTraces(List<String> outcomes, String[] emails, String keyColumn, String[] keys) {
        int winner = OK.equals(outcomes.get(0)) ? 0 : 1;
        int loser = 1 - winner;

        assertThat(countWhere(keyColumn, keys[loser])).as("輸家不得有 users 列").isZero();
        Long winnerId = jdbc.queryForObject("SELECT id FROM users WHERE " + keyColumn + " = ?", Long.class, keys[winner]);

        ArgumentCaptor<VerificationToken> token = ArgumentCaptor.forClass(VerificationToken.class);
        verify(verificationTokenRepository, times(1)).save(token.capture());
        assertThat(token.getValue().getUserId()).as("唯一儲存的驗證 token 屬於贏家").isEqualTo(winnerId);

        ArgumentCaptor<UserRegisteredEvent> event = ArgumentCaptor.forClass(UserRegisteredEvent.class);
        verify(rabbitTemplate, times(1)).convertAndSend(anyString(), anyString(), event.capture());
        assertThat(event.getValue().email()).as("唯一發出的事件屬於贏家").isEqualTo(emails[winner]);

        assertThat(redis.opsForValue().get(RedisKeyConstant.getEmailVerifyCodeKey(emails[winner])))
                .as("贏家的驗證碼 key").isEqualTo(event.getValue().verificationCode());
        if (!emails[loser].equals(emails[winner])) {
            assertThat(redis.opsForValue().get(RedisKeyConstant.getEmailVerifyCodeKey(emails[loser])))
                    .as("輸家的 email 沒有任何驗證碼 key").isNull();
        }
        redis.delete(RedisKeyConstant.getEmailVerifyCodeKey(emails[winner]));
        redis.delete(RedisKeyConstant.getEmailVerifyCodeKey(emails[loser]));
    }

    private long countWhere(String column, String value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE " + column + " = ?", Long.class, value);
    }

    private User createUser(String key, String nickname, UserStatus status) {
        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setEmail(EMAIL_PREFIX + key + "@example.com");
        user.setUsername(EMAIL_PREFIX + key);
        user.setNickname(nickname);
        user.setPasswordHash("not-a-real-hash");
        user.setRole(Role.USER);
        user.setStatus(status);
        user.setTokenVersion("v1");
        return userRepository.save(user);
    }

    private static final String OK = "OK";

    // ─── 依序（基準行為：併發修正後對外行為必須與此一致）───────────────────────

    @Test
    @DisplayName("依序以相同 email 註冊第二次 → EMAIL_DUPLICATED（併發落敗者必須得到同樣的結果）")
    void registerSameEmailSequentially_secondGetsEmailDuplicated() {
        authService.register(EMAIL_PREFIX + "seq-e@example.com", PASSWORD, EMAIL_PREFIX + "seq-e1", "SeqNickE1");

        assertThatThrownBy(() -> authService.register(
                EMAIL_PREFIX + "seq-e@example.com", PASSWORD, EMAIL_PREFIX + "seq-e2", "SeqNickE2"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UserErrorCode.EMAIL_DUPLICATED.getCode()));
    }

    @Test
    @DisplayName("暱稱比對為精確比對且含軟刪除者：已刪除使用者的暱稱仍擋住註冊與改名（先查快速路徑與約束語意一致）")
    void nicknameOfSoftDeletedUser_stillBlocksRegisterAndRename() {
        createUser("ghost", "GhostNick", UserStatus.DELETED);
        User alive = createUser("alive", "AliveNick", UserStatus.ACTIVE);

        assertThatThrownBy(() -> authService.register(
                EMAIL_PREFIX + "new@example.com", PASSWORD, EMAIL_PREFIX + "new", "GhostNick"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UserErrorCode.NICKNAME_DUPLICATED.getCode()));
        assertThatThrownBy(() -> userService.updateProfile(
                alive.getUuid(), "GhostNick", null, null, null, null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UserErrorCode.NICKNAME_DUPLICATED.getCode()));
        assertThat(countWhere("nickname", "GhostNick")).isEqualTo(1L);
    }

    @Test
    @DisplayName("不同大小寫的暱稱是不同暱稱（維持現行精確比對語意，約束不做大小寫不敏感）")
    void nicknamesDifferingOnlyInCase_areDifferentNicknames() {
        createUser("case1", "CaseNick", UserStatus.ACTIVE);

        authService.register(EMAIL_PREFIX + "case2@example.com", PASSWORD, EMAIL_PREFIX + "case2", "casenick");

        assertThat(countWhere("lower(nickname)", "casenick")).isEqualTo(2L);
    }

    // ─── RACE-03：註冊撞 email／username ───────────────────────────────────────

    @Test
    @DisplayName("RACE-03：兩條執行緒同時以相同 email 註冊（重複多輪）→ 一個成功、另一個 EMAIL_DUPLICATED，絕不 500")
    void registerSameEmailConcurrently_oneWins_otherEmailDuplicated() throws Exception {
        installSlowWriteTrigger();
        for (int round = 0; round < ROUNDS; round++) {
            String email = EMAIL_PREFIX + "email-" + round + "@example.com";
            int r = round;
            String[] usernames = {EMAIL_PREFIX + "email-" + r + "-u0", EMAIL_PREFIX + "email-" + r + "-u1"};
            resetRegistrationSideEffectMocks();

            List<String> outcomes = runConcurrently(i -> () -> outcomeOf(() -> authService.register(
                    email, PASSWORD, usernames[i], "EmailRaceNick" + r + "x" + i)));

            assertThat(outcomes).as("第 %d 輪", round)
                    .containsExactlyInAnyOrder(OK, UserErrorCode.EMAIL_DUPLICATED.getCode());
            assertThat(countWhere("email", email)).as("第 %d 輪的使用者列數", round).isEqualTo(1L);
            assertLoserLeftNoTraces(outcomes, new String[]{email, email}, "username", usernames);
        }
    }

    @Test
    @DisplayName("RACE-03：兩條執行緒同時以相同 username 註冊（重複多輪）→ 一個成功、另一個 USERNAME_DUPLICATED，絕不 500")
    void registerSameUsernameConcurrently_oneWins_otherUsernameDuplicated() throws Exception {
        installSlowWriteTrigger();
        for (int round = 0; round < ROUNDS; round++) {
            String username = EMAIL_PREFIX + "uname-" + round;
            int r = round;
            String[] emails = {EMAIL_PREFIX + "uname-" + r + "-0@example.com",
                    EMAIL_PREFIX + "uname-" + r + "-1@example.com"};
            resetRegistrationSideEffectMocks();

            List<String> outcomes = runConcurrently(i -> () -> outcomeOf(() -> authService.register(
                    emails[i], PASSWORD, username, "UnameRaceNick" + r + "x" + i)));

            assertThat(outcomes).as("第 %d 輪", round)
                    .containsExactlyInAnyOrder(OK, UserErrorCode.USERNAME_DUPLICATED.getCode());
            assertThat(countWhere("username", username)).as("第 %d 輪的使用者列數", round).isEqualTo(1L);
            assertLoserLeftNoTraces(outcomes, emails, "email", emails);
        }
    }

    // ─── RACE-02：暱稱 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("RACE-02：兩條執行緒同時以相同暱稱註冊（重複多輪）→ 一個成功、另一個 NICKNAME_DUPLICATED，DB 無重複暱稱")
    void registerSameNicknameConcurrently_oneWins_otherNicknameDuplicated() throws Exception {
        installSlowWriteTrigger();
        for (int round = 0; round < ROUNDS; round++) {
            String nickname = "NickRace" + round;
            int r = round;
            String[] emails = {EMAIL_PREFIX + "nick-" + r + "-0@example.com",
                    EMAIL_PREFIX + "nick-" + r + "-1@example.com"};
            resetRegistrationSideEffectMocks();

            List<String> outcomes = runConcurrently(i -> () -> outcomeOf(() -> authService.register(
                    emails[i], PASSWORD, EMAIL_PREFIX + "nick-" + r + "-u" + i, nickname)));

            assertThat(outcomes).as("第 %d 輪", round)
                    .containsExactlyInAnyOrder(OK, UserErrorCode.NICKNAME_DUPLICATED.getCode());
            assertThat(countWhere("nickname", nickname)).as("第 %d 輪的同暱稱列數", round).isEqualTo(1L);
            assertLoserLeftNoTraces(outcomes, emails, "email", emails);
        }
    }

    @Test
    @DisplayName("RACE-02：兩位使用者同時把暱稱改成同一個值（重複多輪）→ 一個成功、另一個 NICKNAME_DUPLICATED，DB 無重複暱稱")
    void renameToSameNicknameConcurrently_oneWins_otherNicknameDuplicated() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            User first = createUser("ren-" + round + "-a", "RenameA" + round, UserStatus.ACTIVE);
            User second = createUser("ren-" + round + "-b", "RenameB" + round, UserStatus.ACTIVE);
            User[] users = {first, second};
            String target = "RenameTarget" + round;
            installSlowWriteTrigger();

            List<String> outcomes = runConcurrently(i -> () -> outcomeOf(() -> userService.updateProfile(
                    users[i].getUuid(), target, null, null, null, null, null)));
            jdbc.execute("DROP TRIGGER IF EXISTS race_test_slow_users ON users");

            assertThat(outcomes).as("第 %d 輪", round)
                    .containsExactlyInAnyOrder(OK, UserErrorCode.NICKNAME_DUPLICATED.getCode());
            assertThat(countWhere("nickname", target)).as("第 %d 輪的同暱稱列數", round).isEqualTo(1L);
        }
    }
}
