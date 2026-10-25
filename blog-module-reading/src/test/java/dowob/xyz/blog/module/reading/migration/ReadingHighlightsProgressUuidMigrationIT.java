package dowob.xyz.blog.module.reading.migration;

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

import java.math.BigDecimal;
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
 * V27 遷移測試：reading 模組的 {@code user_highlights}、{@code user_reading_progress} 跨模組識別改為 UUID，
 * 並解除對 users／articles 的資料庫耦合（M2-c，承接 V26 的按讚與收藏）。
 *
 * <p>比照 {@link ReadingLikesBookmarksUuidMigrationIT}：先 migrate 到 V26 並灌入舊資料（Long 識別），
 * 再單獨執行 V27，模擬「既有資料已存在，然後升級」的時序。本測試固定在 V27（只 migrate 到 target），
 * 不會受之後新增的 migration 影響其前置狀態。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Testcontainers
@DisplayName("V27 user_highlights／user_reading_progress 跨模組識別改 UUID 遷移")
class ReadingHighlightsProgressUuidMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    private static final String INDEXES_SQL =
            "SELECT tablename || ' | ' || indexname || ' | ' || indexdef FROM pg_indexes "
                    + "WHERE tablename IN ('user_highlights','user_reading_progress') ORDER BY tablename, indexname";

    private static final String CONSTRAINTS_SQL =
            "SELECT conrelid::regclass || ' | ' || conname || ' | ' || pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conrelid IN ('user_highlights'::regclass, 'user_reading_progress'::regclass) "
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

    /** 每個測試都從乾淨的 V26 狀態開始。 */
    @BeforeEach
    void migrateToV26BeforeSeeding() {
        flyway("26").clean();
        flyway("26").migrate();
    }

    // ─────────────────────────── 回填 ───────────────────────────

    @Test
    @DisplayName("既有劃線依 user_id／article_id 回填為使用者與文章的 uuid，其餘欄位與時間原封不動")
    void existingHighlights_areBackfilledWithUserAndArticleUuid() throws SQLException {
        User alice = insertUser("alice@example.com");
        User bob = insertUser("bob@example.com");
        Article first = insertArticle(alice.id(), "first");
        Article second = insertArticle(alice.id(), "second");
        long highlightA = insertHighlight(alice.id(), first.id(), "片段A");
        long highlightB = insertHighlight(bob.id(), second.id(), "片段B");
        String before = queryString("SELECT uuid::text || '|' || snippet || '|' || created_at::text || '|' || updated_at::text "
                + "FROM user_highlights WHERE id = ?", highlightA);

        runV27();

        assertThat(queryUuid("SELECT user_uuid FROM user_highlights WHERE id = ?", highlightA)).isEqualTo(alice.uuid());
        assertThat(queryUuid("SELECT article_uuid FROM user_highlights WHERE id = ?", highlightA)).isEqualTo(first.uuid());
        assertThat(queryUuid("SELECT user_uuid FROM user_highlights WHERE id = ?", highlightB)).isEqualTo(bob.uuid());
        assertThat(queryUuid("SELECT article_uuid FROM user_highlights WHERE id = ?", highlightB)).isEqualTo(second.uuid());
        assertThat(queryLong("SELECT COUNT(*) FROM user_highlights")).isEqualTo(2L);
        assertThat(queryString("SELECT uuid::text || '|' || snippet || '|' || created_at::text || '|' || updated_at::text "
                + "FROM user_highlights WHERE id = ?", highlightA)).isEqualTo(before);
    }

    @Test
    @DisplayName("既有閱讀進度依 user_id／article_id 回填為使用者與文章的 uuid，進度與時間原封不動")
    void existingProgress_areBackfilledWithUserAndArticleUuid() throws SQLException {
        User alice = insertUser("alice@example.com");
        User bob = insertUser("bob@example.com");
        Article first = insertArticle(alice.id(), "first");
        Article second = insertArticle(alice.id(), "second");
        long progressA = insertProgress(alice.id(), first.id(), "0.420");
        long progressB = insertProgress(bob.id(), first.id(), "0.800");
        long progressC = insertProgress(bob.id(), second.id(), "0.100");
        String before = queryString("SELECT progress::text || '|' || updated_at::text FROM user_reading_progress WHERE id = ?",
                progressA);

        runV27();

        assertThat(queryUuid("SELECT user_uuid FROM user_reading_progress WHERE id = ?", progressA)).isEqualTo(alice.uuid());
        assertThat(queryUuid("SELECT article_uuid FROM user_reading_progress WHERE id = ?", progressA)).isEqualTo(first.uuid());
        assertThat(queryUuid("SELECT user_uuid FROM user_reading_progress WHERE id = ?", progressB)).isEqualTo(bob.uuid());
        assertThat(queryUuid("SELECT article_uuid FROM user_reading_progress WHERE id = ?", progressB)).isEqualTo(first.uuid());
        assertThat(queryUuid("SELECT user_uuid FROM user_reading_progress WHERE id = ?", progressC)).isEqualTo(bob.uuid());
        assertThat(queryUuid("SELECT article_uuid FROM user_reading_progress WHERE id = ?", progressC)).isEqualTo(second.uuid());
        assertThat(queryLong("SELECT COUNT(*) FROM user_reading_progress")).isEqualTo(3L);
        assertThat(queryString("SELECT progress::text || '|' || updated_at::text FROM user_reading_progress WHERE id = ?",
                progressA)).isEqualTo(before);
    }

    // ─────────────────────────── 欄位 ───────────────────────────

    @Test
    @DisplayName("兩張表的 user_id、article_id 欄位已不存在")
    void legacyLongColumns_areDropped() throws SQLException {
        runV27();

        for (String table : List.of("user_highlights", "user_reading_progress")) {
            assertThat(columnExists(table, "user_id")).as("%s.user_id", table).isFalse();
            assertThat(columnExists(table, "article_id")).as("%s.article_id", table).isFalse();
        }
    }

    @Test
    @DisplayName("user_uuid、article_uuid 在兩張表都是 NOT NULL 的 uuid 型別")
    void uuidColumns_areNotNullUuid() throws SQLException {
        runV27();

        for (String table : List.of("user_highlights", "user_reading_progress")) {
            for (String column : List.of("user_uuid", "article_uuid")) {
                assertThat(queryString("SELECT is_nullable || '/' || data_type FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?", table, column))
                        .as("%s.%s", table, column).isEqualTo("NO/uuid");
            }
        }
    }

    // ─────────────────────────── FK ───────────────────────────

    @Test
    @DisplayName("兩張表沒有任何指向 users／articles 的 FK")
    void crossModuleForeignKeys_areAllDropped() throws SQLException {
        runV27();

        assertThat(queryLong("SELECT COUNT(*) FROM pg_constraint WHERE contype = 'f' "
                + "AND conrelid IN ('user_highlights'::regclass, 'user_reading_progress'::regclass)")).isZero();
    }

    @Test
    @DisplayName("新的 user_uuid／article_uuid 欄位不建 FK：寫入不存在的使用者與文章 uuid 不會被拒絕")
    void uuidColumns_haveNoForeignKey() throws SQLException {
        runV27();

        execute("INSERT INTO user_highlights (user_uuid, article_uuid, snippet) VALUES (?, ?, 's')",
                UUID.randomUUID(), UUID.randomUUID());
        execute("INSERT INTO user_reading_progress (user_uuid, article_uuid, progress) VALUES (?, ?, 0.5)",
                UUID.randomUUID(), UUID.randomUUID());

        assertThat(queryLong("SELECT COUNT(*) FROM user_highlights")).isEqualTo(1L);
        assertThat(queryLong("SELECT COUNT(*) FROM user_reading_progress")).isEqualTo(1L);
    }

    @Test
    @DisplayName("刪除文章不再連帶刪除劃線與閱讀進度（FK 已移除，改由事件＋校正排程負責）")
    void deletingArticle_noLongerCascadesToHighlightsAndProgress() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "doomed");
        insertHighlight(alice.id(), article.id(), "片段");
        insertProgress(alice.id(), article.id(), "0.500");
        runV27();

        execute("DELETE FROM articles WHERE id = ?", article.id());

        assertThat(queryLong("SELECT COUNT(*) FROM user_highlights")).isEqualTo(1L);
        assertThat(queryLong("SELECT COUNT(*) FROM user_reading_progress")).isEqualTo(1L);
    }

    // ─────────────────────────── UNIQUE 與索引 ───────────────────────────

    @Test
    @DisplayName("uq_user_reading_progress_user_article 重建為 UNIQUE (user_uuid, article_uuid) 並生效（重複寫入被拒絕）")
    void uniqueProgressConstraint_isRebuiltOnUuidColumns() throws SQLException {
        runV27();

        assertThat(queryString("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid = 'user_reading_progress'::regclass AND conname = 'uq_user_reading_progress_user_article'"))
                .isEqualTo("UNIQUE (user_uuid, article_uuid)");
        UUID user = UUID.randomUUID();
        UUID article = UUID.randomUUID();
        execute("INSERT INTO user_reading_progress (user_uuid, article_uuid, progress) VALUES (?, ?, 0.5)", user, article);
        assertThatThrownBy(() ->
                execute("INSERT INTO user_reading_progress (user_uuid, article_uuid, progress) VALUES (?, ?, 0.6)",
                        user, article))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
    }

    @Test
    @DisplayName("INSERT ... ON CONFLICT (user_uuid, article_uuid) DO UPDATE 依賴新 UNIQUE：第二次寫入更新同一列")
    void onConflictDoUpdate_worksOnRebuiltUniqueConstraint() throws SQLException {
        runV27();
        UUID user = UUID.randomUUID();
        UUID article = UUID.randomUUID();
        String sql = "INSERT INTO user_reading_progress (user_uuid, article_uuid, progress) VALUES (?, ?, ?) "
                + "ON CONFLICT (user_uuid, article_uuid) DO UPDATE SET progress = EXCLUDED.progress";

        execute(sql, user, article, new BigDecimal("0.300"));
        execute(sql, user, article, new BigDecimal("0.700"));

        assertThat(queryLong("SELECT COUNT(*) FROM user_reading_progress")).isEqualTo(1L);
        assertThat(queryString("SELECT progress::text FROM user_reading_progress")).isEqualTo("0.700");
    }

    @Test
    @DisplayName("刪除連動與校正用的 article_uuid 單欄索引存在（重建的索引都以 user_uuid 開頭，無法支援）")
    void articleUuidIndexes_exist() throws SQLException {
        runV27();

        assertThat(queryString("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_user_highlights_article_uuid'"))
                .isEqualTo("CREATE INDEX idx_user_highlights_article_uuid ON public.user_highlights "
                        + "USING btree (article_uuid)");
        assertThat(queryString("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_user_reading_progress_article_uuid'"))
                .isEqualTo("CREATE INDEX idx_user_reading_progress_article_uuid ON public.user_reading_progress "
                        + "USING btree (article_uuid)");
    }

    // ─────────────────────────── 孤兒保護 ───────────────────────────

    @Test
    @DisplayName("找不到使用者的劃線 → migration 中止並整體回滾，不刪任何劃線")
    void orphanHighlightWithoutUser_abortsMigrationWithoutDeletingAnything() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "first");
        insertHighlight(alice.id(), article.id(), "好的");
        dropForeignKeys("user_highlights");
        insertHighlight(987_654_321L, article.id(), "孤兒");

        assertThatThrownBy(this::runV27)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("找不到對應使用者");

        assertThat(queryLong("SELECT COUNT(*) FROM user_highlights")).isEqualTo(2L);
        assertThat(columnExists("user_highlights", "user_id")).isTrue();
        assertThat(columnExists("user_highlights", "user_uuid")).isFalse();
    }

    @Test
    @DisplayName("找不到文章的劃線 → migration 中止並整體回滾，不刪任何劃線")
    void orphanHighlightWithoutArticle_abortsMigrationWithoutDeletingAnything() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "first");
        insertHighlight(alice.id(), article.id(), "好的");
        dropForeignKeys("user_highlights");
        insertHighlight(alice.id(), 987_654_321L, "孤兒");

        assertThatThrownBy(this::runV27)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("找不到對應文章");

        assertThat(queryLong("SELECT COUNT(*) FROM user_highlights")).isEqualTo(2L);
        assertThat(columnExists("user_highlights", "article_id")).isTrue();
    }

    @Test
    @DisplayName("找不到使用者的閱讀進度 → migration 中止並整體回滾，不刪任何進度")
    void orphanProgressWithoutUser_abortsMigrationWithoutDeletingAnything() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "first");
        insertProgress(alice.id(), article.id(), "0.500");
        dropForeignKeys("user_reading_progress");
        insertProgress(987_654_321L, article.id(), "0.500");

        assertThatThrownBy(this::runV27)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("找不到對應使用者");

        assertThat(queryLong("SELECT COUNT(*) FROM user_reading_progress")).isEqualTo(2L);
        assertThat(columnExists("user_reading_progress", "user_id")).isTrue();
        assertThat(columnExists("user_reading_progress", "user_uuid")).isFalse();
    }

    @Test
    @DisplayName("找不到文章的閱讀進度 → migration 中止並整體回滾，不刪任何進度")
    void orphanProgressWithoutArticle_abortsMigrationWithoutDeletingAnything() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "first");
        insertProgress(alice.id(), article.id(), "0.500");
        dropForeignKeys("user_reading_progress");
        insertProgress(alice.id(), 987_654_321L, "0.500");

        assertThatThrownBy(this::runV27)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("找不到對應文章");

        assertThat(queryLong("SELECT COUNT(*) FROM user_reading_progress")).isEqualTo(2L);
        assertThat(columnExists("user_reading_progress", "article_id")).isTrue();
    }

    // ─────────────────────────── 前後對照（供 PR 說明引用） ───────────────────────────

    @Test
    @DisplayName("遷移前後的 pg_indexes／pg_constraint 對照（印到 log，並釘住遷移前後的完整清單）")
    void indexAndConstraintComparison() throws SQLException {
        List<String> indexesBefore = queryStrings(INDEXES_SQL);
        List<String> constraintsBefore = queryStrings(CONSTRAINTS_SQL);
        printSection("V26（遷移前）pg_indexes", indexesBefore);
        printSection("V26（遷移前）pg_constraint", constraintsBefore);

        assertThat(indexesBefore).containsExactly(
                "user_highlights | idx_user_highlights_article_for_user | CREATE INDEX idx_user_highlights_article_for_user ON public.user_highlights USING btree (user_id, article_id)",
                "user_highlights | idx_user_highlights_user_recent | CREATE INDEX idx_user_highlights_user_recent ON public.user_highlights USING btree (user_id, created_at DESC)",
                "user_highlights | user_highlights_pkey | CREATE UNIQUE INDEX user_highlights_pkey ON public.user_highlights USING btree (id)",
                "user_highlights | user_highlights_uuid_key | CREATE UNIQUE INDEX user_highlights_uuid_key ON public.user_highlights USING btree (uuid)",
                "user_reading_progress | idx_user_reading_progress_user_recent | CREATE INDEX idx_user_reading_progress_user_recent ON public.user_reading_progress USING btree (user_id, updated_at DESC)",
                "user_reading_progress | uq_user_reading_progress_user_article | CREATE UNIQUE INDEX uq_user_reading_progress_user_article ON public.user_reading_progress USING btree (user_id, article_id)",
                "user_reading_progress | user_reading_progress_pkey | CREATE UNIQUE INDEX user_reading_progress_pkey ON public.user_reading_progress USING btree (id)");

        assertThat(constraintsBefore).containsExactly(
                "user_highlights | chk_user_highlights_color | CHECK (((color)::text ~ '^#[0-9A-Fa-f]{6}$'::text))",
                "user_highlights | user_highlights_article_id_fkey | FOREIGN KEY (article_id) REFERENCES articles(id) ON DELETE CASCADE",
                "user_highlights | user_highlights_pkey | PRIMARY KEY (id)",
                "user_highlights | user_highlights_user_id_fkey | FOREIGN KEY (user_id) REFERENCES users(id)",
                "user_highlights | user_highlights_uuid_key | UNIQUE (uuid)",
                "user_reading_progress | chk_user_reading_progress_range | CHECK (((progress >= (0)::numeric) AND (progress <= (1)::numeric)))",
                "user_reading_progress | uq_user_reading_progress_user_article | UNIQUE (user_id, article_id)",
                "user_reading_progress | user_reading_progress_article_id_fkey | FOREIGN KEY (article_id) REFERENCES articles(id) ON DELETE CASCADE",
                "user_reading_progress | user_reading_progress_pkey | PRIMARY KEY (id)",
                "user_reading_progress | user_reading_progress_user_id_fkey | FOREIGN KEY (user_id) REFERENCES users(id)");

        runV27();

        List<String> indexes = queryStrings(INDEXES_SQL);
        List<String> constraints = queryStrings(CONSTRAINTS_SQL);
        printSection("V27（遷移後）pg_indexes", indexes);
        printSection("V27（遷移後）pg_constraint", constraints);

        assertThat(indexes).containsExactly(
                "user_highlights | idx_user_highlights_article_for_user | CREATE INDEX idx_user_highlights_article_for_user ON public.user_highlights USING btree (user_uuid, article_uuid)",
                "user_highlights | idx_user_highlights_article_uuid | CREATE INDEX idx_user_highlights_article_uuid ON public.user_highlights USING btree (article_uuid)",
                "user_highlights | idx_user_highlights_user_recent | CREATE INDEX idx_user_highlights_user_recent ON public.user_highlights USING btree (user_uuid, created_at DESC)",
                "user_highlights | user_highlights_pkey | CREATE UNIQUE INDEX user_highlights_pkey ON public.user_highlights USING btree (id)",
                "user_highlights | user_highlights_uuid_key | CREATE UNIQUE INDEX user_highlights_uuid_key ON public.user_highlights USING btree (uuid)",
                "user_reading_progress | idx_user_reading_progress_article_uuid | CREATE INDEX idx_user_reading_progress_article_uuid ON public.user_reading_progress USING btree (article_uuid)",
                "user_reading_progress | idx_user_reading_progress_user_recent | CREATE INDEX idx_user_reading_progress_user_recent ON public.user_reading_progress USING btree (user_uuid, updated_at DESC)",
                "user_reading_progress | uq_user_reading_progress_user_article | CREATE UNIQUE INDEX uq_user_reading_progress_user_article ON public.user_reading_progress USING btree (user_uuid, article_uuid)",
                "user_reading_progress | user_reading_progress_pkey | CREATE UNIQUE INDEX user_reading_progress_pkey ON public.user_reading_progress USING btree (id)");
        assertThat(constraints).containsExactly(
                "user_highlights | chk_user_highlights_color | CHECK (((color)::text ~ '^#[0-9A-Fa-f]{6}$'::text))",
                "user_highlights | user_highlights_pkey | PRIMARY KEY (id)",
                "user_highlights | user_highlights_uuid_key | UNIQUE (uuid)",
                "user_reading_progress | chk_user_reading_progress_range | CHECK (((progress >= (0)::numeric) AND (progress <= (1)::numeric)))",
                "user_reading_progress | uq_user_reading_progress_user_article | UNIQUE (user_uuid, article_uuid)",
                "user_reading_progress | user_reading_progress_pkey | PRIMARY KEY (id)");
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

    private void runV27() {
        flyway("27").migrate();
    }

    // ─────────────────────────── 測試資料組裝（V26 的欄位） ───────────────────────────

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

    private long insertHighlight(long userId, long articleId, String snippet) throws SQLException {
        return queryLong("INSERT INTO user_highlights (user_id, article_id, snippet) VALUES (?, ?, ?) RETURNING id",
                userId, articleId, snippet);
    }

    private long insertProgress(long userId, long articleId, String progress) throws SQLException {
        return queryLong("INSERT INTO user_reading_progress (user_id, article_id, progress) VALUES (?, ?, ?) RETURNING id",
                userId, articleId, new BigDecimal(progress));
    }

    /**
     * 拿掉指定表上的所有 FK（V26 時 user_id／article_id 有 FK、理論上不會有孤兒；
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
