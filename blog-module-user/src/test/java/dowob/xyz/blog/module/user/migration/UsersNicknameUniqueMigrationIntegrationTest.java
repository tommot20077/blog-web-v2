package dowob.xyz.blog.module.user.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V34 遷移測試：{@code users.nickname} 加 UNIQUE 約束 {@code uq_users_nickname}（RACE-02）。
 *
 * <p>比照 V32 的遷移測試：先 migrate 到 V34 之前的最後一版並灌入資料，再單獨執行 V34。重點：
 * ① 無重複時建立約束，比對語意與應用層 {@code existsByNickname} 一致（精確比對、含軟刪除者）；
 * ② 已有重複時<b>中止並列出重複的暱稱與筆數、不修改任何資料</b>（需人工處理）。
 * 本測試固定在 V34（只 migrate 到 target），不受之後新增的 migration 影響其前置狀態。</p>
 *
 * <p>命名為 {@code *IntegrationTest}：blog-module-user 的 surefire 沒有 {@code *IT} 的 includes。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Testcontainers
@DisplayName("V34 users.nickname 唯一約束遷移")
class UsersNicknameUniqueMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    private static final String USERS_DIGEST_SQL =
            "SELECT string_agg(id || '|' || uuid || '|' || email || '|' || username || '|' || nickname || '|' "
                    + "|| status, ';' ORDER BY id) FROM users";

    private static Connection connection;

    @BeforeAll
    static void openConnection() throws SQLException {
        connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @AfterAll
    static void closeConnection() throws SQLException {
        connection.close();
    }

    /** 每個測試都從乾淨的「V34 之前最後一版」狀態開始。 */
    @BeforeEach
    void migrateToBeforeV34BeforeSeeding() {
        String baseline = lastVersionBefore34();
        flyway(baseline).clean();
        flyway(baseline).migrate();
    }

    /**
     * 找出 classpath 上小於 V34 的最高 migration 版本（Flyway 不接受不存在的 target，
     * 所以不能直接寫死 33——在尚未合併 V33 的分支上 V34 的前一版是 V32）。
     *
     * @return 版本號字串
     */
    private static String lastVersionBefore34() {
        MigrationVersion v34 = MigrationVersion.fromVersion("34");
        return Arrays.stream(Flyway.configure()
                        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                        .locations(MIGRATION_LOCATION).load().info().all())
                .map(MigrationInfo::getVersion)
                .filter(v -> v != null && v.compareTo(v34) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow()
                .getVersion();
    }

    // ─────────────────────────── 無重複：建立約束 ───────────────────────────

    @Test
    @DisplayName("遷移前（V34 之前）users.nickname 沒有唯一約束——重複暱稱寫得進去（作為對照）")
    void beforeMigration_duplicateNicknameIsAccepted() throws SQLException {
        insertUser("a", "Same", "ACTIVE");
        insertUser("b", "Same", "ACTIVE");

        assertThat(queryLong("SELECT COUNT(*) FROM users WHERE nickname = 'Same'")).isEqualTo(2L);
    }

    @Test
    @DisplayName("無重複時建立 uq_users_nickname，定義為 UNIQUE (nickname)，既有資料原封不動")
    void noDuplicates_createsNamedUniqueConstraint_dataUntouched() throws SQLException {
        insertUser("a", "Alice", "ACTIVE");
        insertUser("b", "Bob", "ACTIVE");
        String before = queryString(USERS_DIGEST_SQL);

        runV34();

        assertThat(queryString("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid = 'users'::regclass AND conname = 'uq_users_nickname'"))
                .isEqualTo("UNIQUE (nickname)");
        assertThat(queryString("SELECT contype::text FROM pg_constraint WHERE conname = 'uq_users_nickname'"))
                .isEqualTo("u");
        assertThat(queryString(USERS_DIGEST_SQL)).isEqualTo(before);
    }

    @Test
    @DisplayName("約束建立後：重複暱稱被拒絕（SQLSTATE 23505，約束名稱為 uq_users_nickname）")
    void afterMigration_duplicateNicknameIsRejectedWithNamedConstraint() throws SQLException {
        insertUser("a", "Alice", "ACTIVE");
        runV34();

        assertThatThrownBy(() -> insertUser("b", "Alice", "ACTIVE"))
                .isInstanceOfSatisfying(SQLException.class, e -> {
                    assertThat(e.getSQLState()).isEqualTo("23505");
                    assertThat(e.getMessage().lines().findFirst().orElse("")).contains("\"uq_users_nickname\"");
                });
    }

    @Test
    @DisplayName("比對語意與 existsByNickname 一致：軟刪除（DELETED）的使用者暱稱同樣被佔用；"
            + "僅大小寫不同視為不同暱稱")
    void constraintSemantics_matchExistsByNickname_includesSoftDeleted_caseSensitive() throws SQLException {
        insertUser("ghost", "Ghost", "DELETED");
        runV34();

        assertThatThrownBy(() -> insertUser("new", "Ghost", "ACTIVE")).isInstanceOf(SQLException.class);
        insertUser("lower", "ghost", "ACTIVE");

        assertThat(queryLong("SELECT COUNT(*) FROM users WHERE lower(nickname) = 'ghost'")).isEqualTo(2L);
    }

    @Test
    @DisplayName("users 的其他唯一約束（email／username／uuid）不受影響，仍在")
    void otherUniqueConstraints_remain() throws SQLException {
        runV34();

        List<String> names = queryStrings("SELECT conname FROM pg_constraint "
                + "WHERE conrelid = 'users'::regclass AND contype = 'u' ORDER BY conname");
        assertThat(names).containsExactly(
                "uq_users_nickname", "users_email_key", "users_username_key", "users_uuid_key");
    }

    // ─────────────────────────── 有重複：中止，不改資料 ───────────────────────────

    @Test
    @DisplayName("已有重複暱稱 → migration 中止，訊息列出重複的暱稱與筆數；資料與 schema 版本完全不變")
    void existingDuplicates_abortMigration_listsThem_dataAndVersionUnchanged() throws SQLException {
        insertUser("a", "Dup", "ACTIVE");
        insertUser("b", "Dup", "ACTIVE");
        insertUser("c", "Dup", "DELETED");
        insertUser("d", "Twin", "ACTIVE");
        insertUser("e", "Twin", "ACTIVE");
        insertUser("f", "Unique", "ACTIVE");
        String before = queryString(USERS_DIGEST_SQL);
        String versionBefore = currentVersion();

        assertThatThrownBy(this::runV34)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V34 中止")
                .hasMessageContaining("2 組重複")
                .hasMessageContaining("\"Dup\" × 3 筆")
                .hasMessageContaining("\"Twin\" × 2 筆")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Unique"));

        assertThat(queryString(USERS_DIGEST_SQL)).as("不得修改或刪除任何資料").isEqualTo(before);
        assertThat(queryLong("SELECT COUNT(*) FROM pg_constraint WHERE conname = 'uq_users_nickname'")).isZero();
        assertThat(currentVersion()).as("失敗的 migration 不留下版本紀錄").isEqualTo(versionBefore);
    }

    // ─────────────────────────── Flyway ───────────────────────────

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(MIGRATION_LOCATION)
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    /** 目前已套用的最高 schema 版本（V34 失敗後必須與失敗前相同）。 */
    private String currentVersion() {
        return flyway("34").info().current().getVersion().getVersion();
    }

    private void runV34() {
        flyway("34").migrate();
    }

    // ─────────────────────────── 測試資料與查詢輔助 ───────────────────────────

    private void insertUser(String key, String nickname, String status) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users (uuid, email, password_hash, nickname, username, role, status) "
                        + "VALUES (?, ?, 'x', ?, ?, 'USER', ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, key + "@test.local");
            ps.setString(3, nickname);
            ps.setString(4, "u-" + key);
            ps.setString(5, status);
            ps.executeUpdate();
        }
    }

    private String queryString(String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private List<String> queryStrings(String sql) throws SQLException {
        List<String> rows = new java.util.ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }

    private long queryLong(String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
