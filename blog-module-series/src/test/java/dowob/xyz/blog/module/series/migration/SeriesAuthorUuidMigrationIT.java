package dowob.xyz.blog.module.series.migration;

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
 * V29 遷移測試：{@code series.author_id}（→ {@code users(id)}）改為 {@code author_uuid}，
 * 並解除對 users 的資料庫耦合（M4-b）。
 *
 * <p>比照 version 的 V28 遷移測試：先 migrate 到 V28 並灌入舊資料（Long 識別），再單獨執行 V29，
 * 模擬「既有資料已存在，然後升級」的時序。本測試固定在 V29（只 migrate 到 target），
 * 不會受之後新增的 migration 影響其前置狀態。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Testcontainers
@DisplayName("V29 series.author_id 改 author_uuid 遷移")
class SeriesAuthorUuidMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    private static final String INDEXES_SQL =
            "SELECT tablename || ' | ' || indexname || ' | ' || indexdef FROM pg_indexes "
                    + "WHERE tablename = 'series' ORDER BY indexname";

    private static final String CONSTRAINTS_SQL =
            "SELECT conrelid::regclass || ' | ' || conname || ' | ' || pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conrelid = 'series'::regclass OR confrelid = 'series'::regclass "
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

    /** 每個測試都從乾淨的 V28 狀態開始。 */
    @BeforeEach
    void migrateToV28BeforeSeeding() {
        flyway("28").clean();
        flyway("28").migrate();
    }

    // ─────────────────────────── 回填 ───────────────────────────

    @Test
    @DisplayName("既有 series 依 author_id 回填為作者的 uuid，其餘欄位原封不動")
    void existingSeries_areBackfilledWithAuthorUuid_otherColumnsUntouched() throws SQLException {
        User alice = insertUser("alice@example.com");
        User bob = insertUser("bob@example.com");
        long first = insertSeries(alice.id(), "first");
        long second = insertSeries(bob.id(), "second");
        String before = queryString("SELECT uuid::text || '|' || title || '|' || slug || '|' || description || '|' "
                + "|| cover_image_url || '|' || article_count || '|' || created_at::text || '|' || updated_at::text "
                + "FROM series WHERE id = ?", first);

        runV29();

        assertThat(queryUuid("SELECT author_uuid FROM series WHERE id = ?", first)).isEqualTo(alice.uuid());
        assertThat(queryUuid("SELECT author_uuid FROM series WHERE id = ?", second)).isEqualTo(bob.uuid());
        assertThat(queryLong("SELECT COUNT(*) FROM series")).isEqualTo(2L);
        assertThat(queryString("SELECT uuid::text || '|' || title || '|' || slug || '|' || description || '|' "
                + "|| cover_image_url || '|' || article_count || '|' || created_at::text || '|' || updated_at::text "
                + "FROM series WHERE id = ?", first)).isEqualTo(before);
    }

    // ─────────────────────────── 欄位 ───────────────────────────

    @Test
    @DisplayName("舊的 author_id 已不存在；author_uuid 是 NOT NULL 的 uuid 型別")
    void legacyColumnDropped_andAuthorUuidIsNotNullUuid() throws SQLException {
        runV29();

        assertThat(columnExists("series", "author_id")).isFalse();
        assertThat(queryString("SELECT is_nullable || '/' || data_type FROM information_schema.columns "
                + "WHERE table_name = 'series' AND column_name = 'author_uuid'")).isEqualTo("NO/uuid");
    }

    // ─────────────────────────── FK ───────────────────────────

    @Test
    @DisplayName("series 表沒有任何指向其他模組（users）的 FK；articles.series_id → series(id) ON DELETE SET NULL 保留（M6 才動）")
    void seriesHasNoOutgoingForeignKey_articleSeriesForeignKeyKept() throws SQLException {
        runV29();

        assertThat(queryLong("SELECT COUNT(*) FROM pg_constraint WHERE contype = 'f' "
                + "AND conrelid = 'series'::regclass")).isZero();
        assertThat(queryString("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE contype = 'f' AND conrelid = 'articles'::regclass AND confrelid = 'series'::regclass"))
                .isEqualTo("FOREIGN KEY (series_id) REFERENCES series(id) ON DELETE SET NULL");
    }

    @Test
    @DisplayName("新的 author_uuid 不建 FK：寫入不存在的使用者 uuid 不會被拒絕")
    void authorUuid_hasNoForeignKey() throws SQLException {
        runV29();

        execute("INSERT INTO series (title, slug, author_uuid) VALUES ('t', 's', ?)", UUID.randomUUID());

        assertThat(queryLong("SELECT COUNT(*) FROM series")).isEqualTo(1L);
    }

    @Test
    @DisplayName("刪除 articles.series_id 的 FK 行為不變：刪除 series → 成員文章的 series_id 設為 NULL")
    void deletingSeries_stillSetsArticlesSeriesIdToNull() throws SQLException {
        User alice = insertUser("alice@example.com");
        long series = insertSeries(alice.id(), "doomed");
        long article = insertArticleInSeries(alice.id(), series, "member");
        runV29();

        execute("DELETE FROM series WHERE id = ?", series);

        assertThat(queryLong("SELECT COUNT(*) FROM articles WHERE id = ? AND series_id IS NULL", article)).isEqualTo(1L);
    }

    // ─────────────────────────── 索引 ───────────────────────────

    @Test
    @DisplayName("idx_series_author 以同名在 author_uuid 上重建（授權刪除連動與作者查詢都靠它）")
    void authorIndex_isRebuiltOnAuthorUuid() throws SQLException {
        runV29();

        assertThat(queryString("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_series_author'"))
                .isEqualTo("CREATE INDEX idx_series_author ON public.series USING btree (author_uuid)");
    }

    // ─────────────────────────── 孤兒保護 ───────────────────────────

    @Test
    @DisplayName("找不到作者的 series → migration 中止並整體回滾，不刪任何 series")
    void orphanSeriesWithoutAuthor_abortsMigrationWithoutDeletingAnything() throws SQLException {
        User alice = insertUser("alice@example.com");
        insertSeries(alice.id(), "ok");
        execute("ALTER TABLE series DROP CONSTRAINT series_author_id_fkey");
        insertSeries(987_654_321L, "orphan");

        assertThatThrownBy(this::runV29)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("找不到對應作者");

        assertThat(queryLong("SELECT COUNT(*) FROM series")).isEqualTo(2L);
        assertThat(columnExists("series", "author_id")).isTrue();
        assertThat(columnExists("series", "author_uuid")).isFalse();
    }

    // ─────────────────────────── 前後對照（供 PR 說明引用） ───────────────────────────

    @Test
    @DisplayName("遷移前後的 pg_indexes／pg_constraint 對照（印到 log，並釘住遷移前後的完整清單）")
    void indexAndConstraintComparison() throws SQLException {
        List<String> indexesBefore = queryStrings(INDEXES_SQL);
        List<String> constraintsBefore = queryStrings(CONSTRAINTS_SQL);
        printSection("V28（遷移前）pg_indexes", indexesBefore);
        printSection("V28（遷移前）pg_constraint", constraintsBefore);

        assertThat(indexesBefore).containsExactly(
                "series | idx_series_author | CREATE INDEX idx_series_author ON public.series USING btree (author_id)",
                "series | series_pkey | CREATE UNIQUE INDEX series_pkey ON public.series USING btree (id)",
                "series | series_slug_key | CREATE UNIQUE INDEX series_slug_key ON public.series USING btree (slug)",
                "series | series_uuid_key | CREATE UNIQUE INDEX series_uuid_key ON public.series USING btree (uuid)");
        assertThat(constraintsBefore).containsExactly(
                "articles | articles_series_id_fkey | FOREIGN KEY (series_id) REFERENCES series(id) ON DELETE SET NULL",
                "series | series_author_id_fkey | FOREIGN KEY (author_id) REFERENCES users(id)",
                "series | series_pkey | PRIMARY KEY (id)",
                "series | series_slug_key | UNIQUE (slug)",
                "series | series_uuid_key | UNIQUE (uuid)");

        runV29();

        List<String> indexes = queryStrings(INDEXES_SQL);
        List<String> constraints = queryStrings(CONSTRAINTS_SQL);
        printSection("V29（遷移後）pg_indexes", indexes);
        printSection("V29（遷移後）pg_constraint", constraints);

        assertThat(indexes).containsExactly(
                "series | idx_series_author | CREATE INDEX idx_series_author ON public.series USING btree (author_uuid)",
                "series | series_pkey | CREATE UNIQUE INDEX series_pkey ON public.series USING btree (id)",
                "series | series_slug_key | CREATE UNIQUE INDEX series_slug_key ON public.series USING btree (slug)",
                "series | series_uuid_key | CREATE UNIQUE INDEX series_uuid_key ON public.series USING btree (uuid)");
        assertThat(constraints).containsExactly(
                "articles | articles_series_id_fkey | FOREIGN KEY (series_id) REFERENCES series(id) ON DELETE SET NULL",
                "series | series_pkey | PRIMARY KEY (id)",
                "series | series_slug_key | UNIQUE (slug)",
                "series | series_uuid_key | UNIQUE (uuid)");
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

    private void runV29() {
        flyway("29").migrate();
    }

    // ─────────────────────────── 測試資料組裝（V28 的欄位） ───────────────────────────

    private record User(long id, UUID uuid) {
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

    private long insertSeries(long authorId, String slug) throws SQLException {
        return queryLong("INSERT INTO series (title, slug, description, cover_image_url, author_id, article_count) "
                + "VALUES (?, ?, '描述', 'https://img/c.png', ?, 3) RETURNING id", "標題-" + slug, slug, authorId);
    }

    private long insertArticleInSeries(long authorId, long seriesId, String slug) throws SQLException {
        return queryLong("INSERT INTO articles (uuid, author_id, title, slug, content_md, content_html, status, "
                + "series_id, series_position) VALUES (?, ?, '標題', ?, 'md', '<p>html</p>', 'PUBLISHED', ?, 1) "
                + "RETURNING id", UUID.randomUUID(), authorId, slug, seriesId);
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
