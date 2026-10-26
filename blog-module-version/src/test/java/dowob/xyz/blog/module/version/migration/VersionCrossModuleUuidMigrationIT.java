package dowob.xyz.blog.module.version.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V28 遷移測試：version 模組的 {@code article_versions}、{@code user_preferences} 跨模組識別改為 UUID，
 * 並解除對 users／articles 的資料庫耦合（M3-b）。
 *
 * <p>比照 reading 的 V27 遷移測試：先 migrate 到 V27 並灌入舊資料（Long 識別），再單獨執行 V28，
 * 模擬「既有資料已存在，然後升級」的時序。本測試固定在 V28（只 migrate 到 target），
 * 不會受之後新增的 migration 影響其前置狀態。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Testcontainers
@DisplayName("V28 article_versions／user_preferences 跨模組識別改 UUID 遷移")
class VersionCrossModuleUuidMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    private static final String INDEXES_SQL =
            "SELECT tablename || ' | ' || indexname || ' | ' || indexdef FROM pg_indexes "
                    + "WHERE tablename IN ('article_versions','user_preferences') ORDER BY tablename, indexname";

    private static final String CONSTRAINTS_SQL =
            "SELECT conrelid::regclass || ' | ' || conname || ' | ' || pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conrelid IN ('article_versions'::regclass, 'user_preferences'::regclass) "
                    + "ORDER BY conrelid::regclass::text, conname";

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

    /** 每個測試都從乾淨的 V27 狀態開始。 */
    @BeforeEach
    void migrateToV27BeforeSeeding() {
        flyway("27").clean();
        flyway("27").migrate();
    }

    // ─────────────────────────── 回填 ───────────────────────────

    @Test
    @DisplayName("既有版本快照依 article_id／author_id 回填為文章與作者的 uuid，其餘欄位原封不動")
    void existingVersions_areBackfilledWithArticleAndAuthorUuid() throws SQLException {
        User alice = insertUser("alice@example.com");
        User bob = insertUser("bob@example.com");
        Article first = insertArticle(alice.id(), "first");
        Article second = insertArticle(bob.id(), "second");
        long versionA = insertVersion(first.id(), alice.id(), "AUTO");
        long versionB = insertVersion(second.id(), bob.id(), "MANUAL");
        String before = queryString("SELECT uuid::text || '|' || type || '|' || title || '|' || content || '|' || created_at::text "
                + "FROM article_versions WHERE id = ?", versionA);

        runV28();

        assertThat(queryUuid("SELECT article_uuid FROM article_versions WHERE id = ?", versionA)).isEqualTo(first.uuid());
        assertThat(queryUuid("SELECT author_uuid FROM article_versions WHERE id = ?", versionA)).isEqualTo(alice.uuid());
        assertThat(queryUuid("SELECT article_uuid FROM article_versions WHERE id = ?", versionB)).isEqualTo(second.uuid());
        assertThat(queryUuid("SELECT author_uuid FROM article_versions WHERE id = ?", versionB)).isEqualTo(bob.uuid());
        assertThat(queryLong("SELECT COUNT(*) FROM article_versions")).isEqualTo(2L);
        assertThat(queryString("SELECT uuid::text || '|' || type || '|' || title || '|' || content || '|' || created_at::text "
                + "FROM article_versions WHERE id = ?", versionA)).isEqualTo(before);
    }

    @Test
    @DisplayName("既有使用者偏好依 user_id 回填為使用者的 uuid，鍵值與時間原封不動")
    void existingPreferences_areBackfilledWithUserUuid() throws SQLException {
        User alice = insertUser("alice@example.com");
        User bob = insertUser("bob@example.com");
        long prefA = insertPreference(alice.id(), "version.auto.retain", "30");
        long prefB = insertPreference(bob.id(), "version.auto.retain", "80");
        String before = queryString("SELECT pref_key || '|' || pref_value || '|' || updated_at::text "
                + "FROM user_preferences WHERE id = ?", prefA);

        runV28();

        assertThat(queryUuid("SELECT user_uuid FROM user_preferences WHERE id = ?", prefA)).isEqualTo(alice.uuid());
        assertThat(queryUuid("SELECT user_uuid FROM user_preferences WHERE id = ?", prefB)).isEqualTo(bob.uuid());
        assertThat(queryLong("SELECT COUNT(*) FROM user_preferences")).isEqualTo(2L);
        assertThat(queryString("SELECT pref_key || '|' || pref_value || '|' || updated_at::text "
                + "FROM user_preferences WHERE id = ?", prefA)).isEqualTo(before);
    }

    // ─────────────────────────── 欄位 ───────────────────────────

    @Test
    @DisplayName("舊的 Long 欄位已不存在；category_id（非 FK 的快照分類）保留")
    void legacyLongColumns_areDropped_categoryIdKept() throws SQLException {
        runV28();

        assertThat(columnExists("article_versions", "article_id")).isFalse();
        assertThat(columnExists("article_versions", "author_id")).isFalse();
        assertThat(columnExists("user_preferences", "user_id")).isFalse();
        assertThat(columnExists("article_versions", "category_id")).as("不在授權清單，不動").isTrue();
    }

    @Test
    @DisplayName("article_uuid、author_uuid、user_uuid 都是 NOT NULL 的 uuid 型別")
    void uuidColumns_areNotNullUuid() throws SQLException {
        runV28();

        for (String[] tc : new String[][]{
                {"article_versions", "article_uuid"}, {"article_versions", "author_uuid"},
                {"user_preferences", "user_uuid"}}) {
            assertThat(queryString("SELECT is_nullable || '/' || data_type FROM information_schema.columns "
                    + "WHERE table_name = ? AND column_name = ?", tc[0], tc[1]))
                    .as("%s.%s", tc[0], tc[1]).isEqualTo("NO/uuid");
        }
    }

    // ─────────────────────────── FK ───────────────────────────

    @Test
    @DisplayName("兩張表沒有任何指向 users／articles 的 FK")
    void crossModuleForeignKeys_areAllDropped() throws SQLException {
        runV28();

        assertThat(queryLong("SELECT COUNT(*) FROM pg_constraint WHERE contype = 'f' "
                + "AND conrelid IN ('article_versions'::regclass, 'user_preferences'::regclass)")).isZero();
    }

    @Test
    @DisplayName("新的 uuid 欄位不建 FK：寫入不存在的文章、使用者 uuid 不會被拒絕")
    void uuidColumns_haveNoForeignKey() throws SQLException {
        runV28();

        execute("INSERT INTO article_versions (article_uuid, author_uuid, type, title, slug, content, status) "
                + "VALUES (?, ?, 'AUTO', 't', 's', 'c', 'DRAFT')", UUID.randomUUID(), UUID.randomUUID());
        execute("INSERT INTO user_preferences (user_uuid, pref_key, pref_value) VALUES (?, 'k', 'v')", UUID.randomUUID());

        assertThat(queryLong("SELECT COUNT(*) FROM article_versions")).isEqualTo(1L);
        assertThat(queryLong("SELECT COUNT(*) FROM user_preferences")).isEqualTo(1L);
    }

    @Test
    @DisplayName("刪除文章不再連帶刪除版本快照（FK 已移除，改由事件＋校正排程負責）")
    void deletingArticle_noLongerCascadesToVersions() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "doomed");
        insertVersion(article.id(), alice.id(), "MANUAL");
        runV28();

        execute("DELETE FROM articles WHERE id = ?", article.id());

        assertThat(queryLong("SELECT COUNT(*) FROM article_versions")).isEqualTo(1L);
    }

    // ─────────────────────────── UNIQUE 與索引 ───────────────────────────

    @Test
    @DisplayName("uq_user_preferences_user_key 重建為 UNIQUE (user_uuid, pref_key) 並生效（重複寫入被拒絕）")
    void uniquePreferenceConstraint_isRebuiltOnUuidColumn() throws SQLException {
        runV28();

        assertThat(queryString("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid = 'user_preferences'::regclass AND conname = 'uq_user_preferences_user_key'"))
                .isEqualTo("UNIQUE (user_uuid, pref_key)");
        UUID user = UUID.randomUUID();
        execute("INSERT INTO user_preferences (user_uuid, pref_key, pref_value) VALUES (?, 'k', 'v1')", user);
        assertThatThrownBy(() ->
                execute("INSERT INTO user_preferences (user_uuid, pref_key, pref_value) VALUES (?, 'k', 'v2')", user))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
    }

    @Test
    @DisplayName("INSERT ... ON CONFLICT (user_uuid, pref_key) DO UPDATE 依賴新 UNIQUE：第二次寫入更新同一列")
    void onConflictDoUpdate_worksOnRebuiltUniqueConstraint() throws SQLException {
        runV28();
        UUID user = UUID.randomUUID();
        String sql = "INSERT INTO user_preferences (user_uuid, pref_key, pref_value) VALUES (?, 'k', ?) "
                + "ON CONFLICT (user_uuid, pref_key) DO UPDATE SET pref_value = EXCLUDED.pref_value";

        execute(sql, user, "v1");
        execute(sql, user, "v2");

        assertThat(queryLong("SELECT COUNT(*) FROM user_preferences")).isEqualTo(1L);
        assertThat(queryString("SELECT pref_value FROM user_preferences")).isEqualTo("v2");
    }

    @Test
    @DisplayName("兩個以 article_id 開頭的索引以同名在 article_uuid 上重建（刪除連動與列表查詢都靠它）")
    void articleIndexes_areRebuiltOnArticleUuid() throws SQLException {
        runV28();

        assertThat(queryString("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_article_versions_article_created'"))
                .isEqualTo("CREATE INDEX idx_article_versions_article_created ON public.article_versions "
                        + "USING btree (article_uuid, created_at DESC)");
        assertThat(queryString("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_article_versions_article_type'"))
                .isEqualTo("CREATE INDEX idx_article_versions_article_type ON public.article_versions "
                        + "USING btree (article_uuid, type)");
    }

    // ─────────────────────────── 孤兒保護 ───────────────────────────

    @Test
    @DisplayName("找不到文章的版本快照 → migration 中止並整體回滾，不刪任何快照")
    void orphanVersionWithoutArticle_abortsMigrationWithoutDeletingAnything() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "first");
        insertVersion(article.id(), alice.id(), "MANUAL");
        dropForeignKeys("article_versions");
        insertVersion(987_654_321L, alice.id(), "MANUAL");

        assertThatThrownBy(this::runV28)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("找不到對應文章");

        assertThat(queryLong("SELECT COUNT(*) FROM article_versions")).isEqualTo(2L);
        assertThat(columnExists("article_versions", "article_id")).isTrue();
        assertThat(columnExists("article_versions", "article_uuid")).isFalse();
    }

    @Test
    @DisplayName("找不到作者的版本快照 → migration 中止並整體回滾，不刪任何快照")
    void orphanVersionWithoutAuthor_abortsMigrationWithoutDeletingAnything() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "first");
        insertVersion(article.id(), alice.id(), "MANUAL");
        dropForeignKeys("article_versions");
        insertVersion(article.id(), 987_654_321L, "MANUAL");

        assertThatThrownBy(this::runV28)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("找不到對應作者");

        assertThat(queryLong("SELECT COUNT(*) FROM article_versions")).isEqualTo(2L);
        assertThat(columnExists("article_versions", "author_id")).isTrue();
    }

    @Test
    @DisplayName("找不到使用者的偏好設定 → migration 中止並整體回滾，不刪任何偏好")
    void orphanPreferenceWithoutUser_abortsMigrationWithoutDeletingAnything() throws SQLException {
        User alice = insertUser("alice@example.com");
        insertPreference(alice.id(), "k", "v");
        dropForeignKeys("user_preferences");
        insertPreference(987_654_321L, "k", "v");

        assertThatThrownBy(this::runV28)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("找不到對應使用者");

        assertThat(queryLong("SELECT COUNT(*) FROM user_preferences")).isEqualTo(2L);
        assertThat(columnExists("user_preferences", "user_id")).isTrue();
        assertThat(columnExists("user_preferences", "user_uuid")).isFalse();
    }

    // ─────────────────────────── 前後對照（供 PR 說明引用） ───────────────────────────

    @Test
    @DisplayName("遷移前後的 pg_indexes／pg_constraint 對照（印到 log，並釘住遷移前後的完整清單）")
    void indexAndConstraintComparison() throws SQLException {
        List<String> indexesBefore = queryStrings(INDEXES_SQL);
        List<String> constraintsBefore = queryStrings(CONSTRAINTS_SQL);
        printSection("V27（遷移前）pg_indexes", indexesBefore);
        printSection("V27（遷移前）pg_constraint", constraintsBefore);

        assertThat(indexesBefore).containsExactly(
                "article_versions | article_versions_pkey | CREATE UNIQUE INDEX article_versions_pkey ON public.article_versions USING btree (id)",
                "article_versions | article_versions_uuid_key | CREATE UNIQUE INDEX article_versions_uuid_key ON public.article_versions USING btree (uuid)",
                "article_versions | idx_article_versions_article_created | CREATE INDEX idx_article_versions_article_created ON public.article_versions USING btree (article_id, created_at DESC)",
                "article_versions | idx_article_versions_article_type | CREATE INDEX idx_article_versions_article_type ON public.article_versions USING btree (article_id, type)",
                "user_preferences | uq_user_preferences_user_key | CREATE UNIQUE INDEX uq_user_preferences_user_key ON public.user_preferences USING btree (user_id, pref_key)",
                "user_preferences | user_preferences_pkey | CREATE UNIQUE INDEX user_preferences_pkey ON public.user_preferences USING btree (id)");
        assertThat(constraintsBefore).containsExactly(
                "article_versions | article_versions_article_id_fkey | FOREIGN KEY (article_id) REFERENCES articles(id) ON DELETE CASCADE",
                "article_versions | article_versions_author_id_fkey | FOREIGN KEY (author_id) REFERENCES users(id)",
                "article_versions | article_versions_pkey | PRIMARY KEY (id)",
                "article_versions | article_versions_type_check | CHECK (((type)::text = ANY ((ARRAY['AUTO'::character varying, 'MANUAL'::character varying, 'PUBLISHED'::character varying])::text[])))",
                "article_versions | article_versions_uuid_key | UNIQUE (uuid)",
                "user_preferences | uq_user_preferences_user_key | UNIQUE (user_id, pref_key)",
                "user_preferences | user_preferences_pkey | PRIMARY KEY (id)",
                "user_preferences | user_preferences_user_id_fkey | FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE");

        runV28();

        List<String> indexes = queryStrings(INDEXES_SQL);
        List<String> constraints = queryStrings(CONSTRAINTS_SQL);
        printSection("V28（遷移後）pg_indexes", indexes);
        printSection("V28（遷移後）pg_constraint", constraints);

        assertThat(indexes).containsExactly(
                "article_versions | article_versions_pkey | CREATE UNIQUE INDEX article_versions_pkey ON public.article_versions USING btree (id)",
                "article_versions | article_versions_uuid_key | CREATE UNIQUE INDEX article_versions_uuid_key ON public.article_versions USING btree (uuid)",
                "article_versions | idx_article_versions_article_created | CREATE INDEX idx_article_versions_article_created ON public.article_versions USING btree (article_uuid, created_at DESC)",
                "article_versions | idx_article_versions_article_type | CREATE INDEX idx_article_versions_article_type ON public.article_versions USING btree (article_uuid, type)",
                "user_preferences | uq_user_preferences_user_key | CREATE UNIQUE INDEX uq_user_preferences_user_key ON public.user_preferences USING btree (user_uuid, pref_key)",
                "user_preferences | user_preferences_pkey | CREATE UNIQUE INDEX user_preferences_pkey ON public.user_preferences USING btree (id)");
        assertThat(constraints).containsExactly(
                "article_versions | article_versions_pkey | PRIMARY KEY (id)",
                "article_versions | article_versions_type_check | CHECK (((type)::text = ANY ((ARRAY['AUTO'::character varying, 'MANUAL'::character varying, 'PUBLISHED'::character varying])::text[])))",
                "article_versions | article_versions_uuid_key | UNIQUE (uuid)",
                "user_preferences | uq_user_preferences_user_key | UNIQUE (user_uuid, pref_key)",
                "user_preferences | user_preferences_pkey | PRIMARY KEY (id)");
    }

    private static void printSection(String title, List<String> rows) {
        System.out.println("=== " + title + " ===\n" + String.join("\n", rows));
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

    private void runV28() {
        flyway("28").migrate();
    }

    // ─────────────────────────── 測試資料組裝（V27 的欄位） ───────────────────────────

    private record User(long id, UUID uuid) {
    }

    private record Article(long id, UUID uuid) {
    }

    private User insertUser(String email) throws SQLException {
        UUID uuid = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users (uuid, email, password_hash, nickname, username, role, status) "
                        + "VALUES (?, ?, 'x', 'nick', ?, 'AUTHOR', 'ACTIVE') RETURNING id")) {
            ps.setObject(1, uuid);
            ps.setString(2, email);
            ps.setString(3, email);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new User(rs.getLong(1), uuid);
            }
        }
    }

    private Article insertArticle(long authorId, String slug) throws SQLException {
        UUID uuid = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO articles (uuid, author_id, title, slug, content_md, content_html, status) "
                        + "VALUES (?, ?, '標題', ?, 'md', '<p>html</p>', 'PUBLISHED') RETURNING id")) {
            ps.setObject(1, uuid);
            ps.setLong(2, authorId);
            ps.setString(3, slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Article(rs.getLong(1), uuid);
            }
        }
    }

    private long insertVersion(long articleId, long authorId, String type) throws SQLException {
        return queryLong("INSERT INTO article_versions (article_id, author_id, type, title, slug, content, status) "
                + "VALUES (?, ?, ?, '版本標題', 'slug', '內容', 'DRAFT') RETURNING id", articleId, authorId, type);
    }

    private long insertPreference(long userId, String key, String value) throws SQLException {
        return queryLong("INSERT INTO user_preferences (user_id, pref_key, pref_value) VALUES (?, ?, ?) RETURNING id",
                userId, key, value);
    }

    /**
     * 拿掉指定表上的所有 FK（V27 時 article_id／author_id／user_id 有 FK、理論上不會有孤兒；
     * 這裡先拿掉 FK 再製造孤兒，驗證保護機制本身）。
     */
    private void dropForeignKeys(String table) throws SQLException {
        execute("DO $$ DECLARE r RECORD; BEGIN "
                + "FOR r IN SELECT conname FROM pg_constraint WHERE contype = 'f' AND conrelid = '" + table
                + "'::regclass LOOP EXECUTE format('ALTER TABLE " + table
                + " DROP CONSTRAINT %I', r.conname); END LOOP; END $$");
    }

    // ─────────────────────────── 查詢輔助 ───────────────────────────

    private UUID queryUuid(String sql, long param) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        return queryLong("SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                table, column) > 0;
    }

    private void execute(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    private String queryString(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private List<String> queryStrings(String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }

    private long queryLong(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
