package dowob.xyz.blog.module.article.migration;

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
 * V31 遷移測試：{@code articles.author_id}（→ {@code users(id)}）改為 {@code author_uuid}，
 * 並解除對 users 的資料庫耦合（M6-a）。
 *
 * <p>比照 series 的 V29 遷移測試：先 migrate 到 V30 並灌入舊資料（Long 識別），再單獨執行 V31，
 * 模擬「既有資料已存在，然後升級」的時序。本測試固定在 V31（只 migrate 到 target），
 * 不會受之後新增的 migration 影響其前置狀態。</p>
 *
 * <p>範圍邊界：{@code articles.series_id}（→ {@code series(id)} ON DELETE SET NULL）是 M6-b 的欄位，
 * 本遷移不碰，測試也釘住它仍然存在。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Testcontainers
@DisplayName("V31 articles.author_id 改 author_uuid 遷移")
class ArticleAuthorUuidMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    private static final String INDEXES_SQL =
            "SELECT tablename || ' | ' || indexname || ' | ' || indexdef FROM pg_indexes "
                    + "WHERE tablename = 'articles' ORDER BY indexname";

    private static final String CONSTRAINTS_SQL =
            "SELECT conrelid::regclass || ' | ' || conname || ' | ' || pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conrelid = 'articles'::regclass OR confrelid = 'articles'::regclass "
                    + "ORDER BY conrelid::regclass::text, conname";

    private static final String ROW_DIGEST_SQL =
            "SELECT uuid::text || '|' || title || '|' || slug || '|' || content_md || '|' || status || '|' "
                    + "|| view_count || '|' || like_count || '|' || comment_count || '|' || version || '|' "
                    + "|| created_at::text || '|' || updated_at::text FROM articles WHERE id = ?";

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

    /** 每個測試都從乾淨的 V30 狀態開始。 */
    @BeforeEach
    void migrateToV30BeforeSeeding() {
        flyway("30").clean();
        flyway("30").migrate();
    }

    // ─────────────────────────── 回填 ───────────────────────────

    @Test
    @DisplayName("既有 articles 依 author_id 回填為作者的 uuid，其餘欄位原封不動")
    void existingArticles_areBackfilledWithAuthorUuid_otherColumnsUntouched() throws SQLException {
        User alice = insertUser("alice@example.com");
        User bob = insertUser("bob@example.com");
        long first = insertArticle(alice.id(), "first");
        long second = insertArticle(bob.id(), "second");
        String before = queryString(ROW_DIGEST_SQL, first);

        runV31();

        assertThat(queryUuid("SELECT author_uuid FROM articles WHERE id = ?", first)).isEqualTo(alice.uuid());
        assertThat(queryUuid("SELECT author_uuid FROM articles WHERE id = ?", second)).isEqualTo(bob.uuid());
        assertThat(queryLong("SELECT COUNT(*) FROM articles")).isEqualTo(2L);
        assertThat(queryString(ROW_DIGEST_SQL, first)).isEqualTo(before);
    }

    // ─────────────────────────── 欄位 ───────────────────────────

    @Test
    @DisplayName("舊的 author_id 已不存在；author_uuid 是 NOT NULL 的 uuid 型別")
    void legacyColumnDropped_andAuthorUuidIsNotNullUuid() throws SQLException {
        runV31();

        assertThat(columnExists("articles", "author_id")).isFalse();
        assertThat(queryString("SELECT is_nullable || '/' || data_type FROM information_schema.columns "
                + "WHERE table_name = 'articles' AND column_name = 'author_uuid'")).isEqualTo("NO/uuid");
    }

    // ─────────────────────────── FK ───────────────────────────

    @Test
    @DisplayName("articles 沒有任何指向 users 的 FK；articles.series_id → series(id) ON DELETE SET NULL 保留（M6-b 才動）")
    void articlesHasNoForeignKeyToUsers_seriesForeignKeyKept() throws SQLException {
        runV31();

        assertThat(queryLong("SELECT COUNT(*) FROM pg_constraint WHERE contype = 'f' "
                + "AND conrelid = 'articles'::regclass AND confrelid = 'users'::regclass")).isZero();
        assertThat(queryString("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE contype = 'f' AND conrelid = 'articles'::regclass AND confrelid = 'series'::regclass"))
                .isEqualTo("FOREIGN KEY (series_id) REFERENCES series(id) ON DELETE SET NULL");
    }

    @Test
    @DisplayName("新的 author_uuid 不建 FK：寫入不存在的使用者 uuid 不會被拒絕")
    void authorUuid_hasNoForeignKey() throws SQLException {
        runV31();

        execute("INSERT INTO articles (uuid, author_uuid, title, slug, content_md) VALUES (?, ?, 't', 's', 'md')",
                UUID.randomUUID(), UUID.randomUUID());

        assertThat(queryLong("SELECT COUNT(*) FROM articles")).isEqualTo(1L);
    }

    @Test
    @DisplayName("使用者軟刪除不影響文章：users 列仍在，文章的 author_uuid 維持不變（不新增連動）")
    void softDeletedUser_doesNotAffectArticles() throws SQLException {
        User alice = insertUser("alice@example.com");
        long article = insertArticle(alice.id(), "keep");
        runV31();

        execute("UPDATE users SET status = 'DELETED' WHERE id = ?", alice.id());

        assertThat(queryUuid("SELECT author_uuid FROM articles WHERE id = ?", article)).isEqualTo(alice.uuid());
    }

    // ─────────────────────────── 索引 ───────────────────────────

    @Test
    @DisplayName("idx_articles_author_id 以同名在 author_uuid 上重建（作者文章列表查詢都靠它）")
    void authorIndex_isRebuiltOnAuthorUuid() throws SQLException {
        runV31();

        assertThat(queryString("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_articles_author_id'"))
                .isEqualTo("CREATE INDEX idx_articles_author_id ON public.articles USING btree (author_uuid)");
    }

    // ─────────────────────────── 孤兒保護 ───────────────────────────

    @Test
    @DisplayName("找不到作者的 articles → migration 中止並整體回滾，不刪任何文章")
    void orphanArticleWithoutAuthor_abortsMigrationWithoutDeletingAnything() throws SQLException {
        User alice = insertUser("alice@example.com");
        insertArticle(alice.id(), "ok");
        execute("ALTER TABLE articles DROP CONSTRAINT articles_author_id_fkey");
        insertArticle(987_654_321L, "orphan");

        assertThatThrownBy(this::runV31)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("找不到對應作者");

        assertThat(queryLong("SELECT COUNT(*) FROM articles")).isEqualTo(2L);
        assertThat(columnExists("articles", "author_id")).isTrue();
        assertThat(columnExists("articles", "author_uuid")).isFalse();
    }

    // ─────────────────────────── 前後對照（供 PR 說明引用） ───────────────────────────

    @Test
    @DisplayName("遷移前後的 pg_indexes／pg_constraint 對照（印到 log）：只有作者相關的索引與 FK 變動")
    void indexAndConstraintComparison() throws SQLException {
        List<String> indexesBefore = queryStrings(INDEXES_SQL);
        List<String> constraintsBefore = queryStrings(CONSTRAINTS_SQL);
        printSection("V30（遷移前）pg_indexes", indexesBefore);
        printSection("V30（遷移前）pg_constraint", constraintsBefore);

        assertThat(indexesBefore).contains(
                "articles | idx_articles_author_id | CREATE INDEX idx_articles_author_id ON public.articles USING btree (author_id)");
        assertThat(constraintsBefore).contains(
                "articles | articles_author_id_fkey | FOREIGN KEY (author_id) REFERENCES users(id)");

        runV31();

        List<String> indexesAfter = queryStrings(INDEXES_SQL);
        List<String> constraintsAfter = queryStrings(CONSTRAINTS_SQL);
        printSection("V31（遷移後）pg_indexes", indexesAfter);
        printSection("V31（遷移後）pg_constraint", constraintsAfter);

        List<String> expectedIndexes = new ArrayList<>(indexesBefore);
        expectedIndexes.replaceAll(row -> row.contains("idx_articles_author_id")
                ? "articles | idx_articles_author_id | CREATE INDEX idx_articles_author_id ON public.articles USING btree (author_uuid)"
                : row);
        assertThat(indexesAfter).containsExactlyElementsOf(expectedIndexes);

        List<String> expectedConstraints = new ArrayList<>(constraintsBefore);
        expectedConstraints.remove("articles | articles_author_id_fkey | FOREIGN KEY (author_id) REFERENCES users(id)");
        assertThat(constraintsAfter).containsExactlyElementsOf(expectedConstraints);
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

    private void runV31() {
        flyway("31").migrate();
    }

    // ─────────────────────────── 測試資料組裝（V30 的欄位） ───────────────────────────

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

    private long insertArticle(long authorId, String slug) throws SQLException {
        return queryLong("INSERT INTO articles (uuid, author_id, title, slug, content_md, content_html, status, "
                + "view_count, like_count, comment_count) VALUES (?, ?, ?, ?, 'md', '<p>html</p>', 'PUBLISHED', 5, 3, 2) "
                + "RETURNING id", UUID.randomUUID(), authorId, "標題-" + slug, slug);
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
