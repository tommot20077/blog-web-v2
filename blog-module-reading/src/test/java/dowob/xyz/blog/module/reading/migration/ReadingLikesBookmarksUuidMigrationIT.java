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
 * V26 遷移測試：reading 模組的 {@code user_article_likes}、{@code user_bookmarks} 跨模組識別改為 UUID，
 * 並解除對 users／articles 的資料庫耦合。
 *
 * <p>比照 comment 模組的 V25 遷移測試：先 migrate 到 V25 並灌入舊資料（Long 識別），再單獨執行 V26，
 * 模擬「既有資料已存在，然後升級」的時序——一般 IT 從空資料庫一路 migrate，測不到回填。
 * 本測試固定在 V26，不會受之後新增的 migration 影響其前置狀態（只 migrate 到 target）。</p>
 *
 * <p>範圍邊界：{@code user_highlights}、{@code user_reading_progress} 不在本次遷移內（留待 M2-c），
 * 本測試也斷言它們保持原樣。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Testcontainers
@DisplayName("V26 user_article_likes／user_bookmarks 跨模組識別改 UUID 遷移")
class ReadingLikesBookmarksUuidMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    private static final String INDEXES_SQL =
            "SELECT tablename || ' | ' || indexname || ' | ' || indexdef FROM pg_indexes "
                    + "WHERE tablename IN ('user_article_likes','user_bookmarks') ORDER BY tablename, indexname";

    private static final String CONSTRAINTS_SQL =
            "SELECT conrelid::regclass || ' | ' || conname || ' | ' || pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conrelid IN ('user_article_likes'::regclass, 'user_bookmarks'::regclass) "
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

    /**
     * 每個測試都從乾淨的 V25 狀態開始。
     */
    @BeforeEach
    void migrateToV25BeforeSeeding() {
        flyway("25").clean();
        flyway("25").migrate();
    }

    // ─────────────────────────── 回填 ───────────────────────────

    @Test
    @DisplayName("既有按讚依 user_id／article_id 回填為使用者與文章的 uuid，建立時間原封不動")
    void existingLikes_areBackfilledWithUserAndArticleUuid() throws SQLException {
        User alice = insertUser("alice@example.com");
        User bob = insertUser("bob@example.com");
        Article first = insertArticle(alice.id(), "first");
        Article second = insertArticle(alice.id(), "second");
        long likeA = insertLike(alice.id(), first.id());
        long likeB = insertLike(bob.id(), second.id());
        String createdBefore = queryString("SELECT created_at::text FROM user_article_likes WHERE id = ?", likeA);

        runV26();

        assertThat(likeUserUuid(likeA)).isEqualTo(alice.uuid());
        assertThat(likeArticleUuid(likeA)).isEqualTo(first.uuid());
        assertThat(likeUserUuid(likeB)).isEqualTo(bob.uuid());
        assertThat(likeArticleUuid(likeB)).isEqualTo(second.uuid());
        assertThat(queryLong("SELECT COUNT(*) FROM user_article_likes")).isEqualTo(2L);
        assertThat(queryString("SELECT created_at::text FROM user_article_likes WHERE id = ?", likeA))
                .isEqualTo(createdBefore);
    }

    @Test
    @DisplayName("既有收藏依 user_id／article_id 回填為使用者與文章的 uuid，建立時間原封不動")
    void existingBookmarks_areBackfilledWithUserAndArticleUuid() throws SQLException {
        User alice = insertUser("alice@example.com");
        User bob = insertUser("bob@example.com");
        Article first = insertArticle(alice.id(), "first");
        Article second = insertArticle(alice.id(), "second");
        long bookmarkA = insertBookmark(alice.id(), first.id());
        long bookmarkB = insertBookmark(bob.id(), first.id());
        long bookmarkC = insertBookmark(bob.id(), second.id());
        String createdBefore = queryString("SELECT created_at::text FROM user_bookmarks WHERE id = ?", bookmarkA);

        runV26();

        assertThat(bookmarkUserUuid(bookmarkA)).isEqualTo(alice.uuid());
        assertThat(bookmarkArticleUuid(bookmarkA)).isEqualTo(first.uuid());
        assertThat(bookmarkUserUuid(bookmarkB)).isEqualTo(bob.uuid());
        assertThat(bookmarkArticleUuid(bookmarkB)).isEqualTo(first.uuid());
        assertThat(bookmarkUserUuid(bookmarkC)).isEqualTo(bob.uuid());
        assertThat(bookmarkArticleUuid(bookmarkC)).isEqualTo(second.uuid());
        assertThat(queryLong("SELECT COUNT(*) FROM user_bookmarks")).isEqualTo(3L);
        assertThat(queryString("SELECT created_at::text FROM user_bookmarks WHERE id = ?", bookmarkA))
                .isEqualTo(createdBefore);
    }

    // ─────────────────────────── 欄位 ───────────────────────────

    @Test
    @DisplayName("兩張表的 user_id、article_id 欄位已不存在")
    void legacyLongColumns_areDropped() throws SQLException {
        runV26();

        assertThat(columnExists("user_article_likes", "user_id")).isFalse();
        assertThat(columnExists("user_article_likes", "article_id")).isFalse();
        assertThat(columnExists("user_bookmarks", "user_id")).isFalse();
        assertThat(columnExists("user_bookmarks", "article_id")).isFalse();
    }

    @Test
    @DisplayName("user_uuid、article_uuid 在兩張表都是 NOT NULL 的 uuid 型別")
    void uuidColumns_areNotNullUuid() throws SQLException {
        runV26();

        for (String table : List.of("user_article_likes", "user_bookmarks")) {
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
        runV26();

        assertThat(queryLong("SELECT COUNT(*) FROM pg_constraint "
                + "WHERE contype = 'f' AND conrelid IN ('user_article_likes'::regclass, 'user_bookmarks'::regclass)"))
                .isZero();
    }

    @Test
    @DisplayName("新的 user_uuid／article_uuid 欄位不建 FK：寫入不存在的使用者與文章 uuid 不會被拒絕")
    void uuidColumns_haveNoForeignKey() throws SQLException {
        runV26();

        execute("INSERT INTO user_article_likes (user_uuid, article_uuid) VALUES (?, ?)",
                UUID.randomUUID(), UUID.randomUUID());
        execute("INSERT INTO user_bookmarks (user_uuid, article_uuid) VALUES (?, ?)",
                UUID.randomUUID(), UUID.randomUUID());

        assertThat(queryLong("SELECT COUNT(*) FROM user_article_likes")).isEqualTo(1L);
        assertThat(queryLong("SELECT COUNT(*) FROM user_bookmarks")).isEqualTo(1L);
    }

    @Test
    @DisplayName("刪除文章不再連帶刪除按讚與收藏（FK 已移除，改由事件＋校正排程負責）")
    void deletingArticle_noLongerCascadesToLikesAndBookmarks() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "doomed");
        insertLike(alice.id(), article.id());
        insertBookmark(alice.id(), article.id());
        runV26();

        execute("DELETE FROM articles WHERE id = ?", article.id());

        assertThat(queryLong("SELECT COUNT(*) FROM user_article_likes")).isEqualTo(1L);
        assertThat(queryLong("SELECT COUNT(*) FROM user_bookmarks")).isEqualTo(1L);
    }

    @Test
    @DisplayName("範圍邊界：user_highlights、user_reading_progress 不在本次遷移內，欄位與 FK 保持原樣")
    void highlightsAndProgress_areUntouched() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "first");
        runV26();

        for (String table : List.of("user_highlights", "user_reading_progress")) {
            assertThat(columnExists(table, "user_id")).as("%s.user_id", table).isTrue();
            assertThat(columnExists(table, "article_id")).as("%s.article_id", table).isTrue();
            assertThat(queryLong("SELECT COUNT(*) FROM pg_constraint WHERE contype = 'f' AND conrelid = ?::regclass",
                    table)).as("%s 的 FK 數", table).isEqualTo(2L);
        }
        execute("INSERT INTO user_reading_progress (user_id, article_id, progress) VALUES (?, ?, 0.5)",
                alice.id(), article.id());
        execute("DELETE FROM articles WHERE id = ?", article.id());
        assertThat(queryLong("SELECT COUNT(*) FROM user_reading_progress"))
                .as("progress 仍由 FK CASCADE 清除（M2-c 才改）").isZero();
    }

    // ─────────────────────────── UNIQUE 與索引 ───────────────────────────

    @Test
    @DisplayName("uq_user_article_likes_user_article 重建為 UNIQUE (user_uuid, article_uuid) 並生效（重複按讚被拒絕）")
    void uniqueLikeConstraint_isRebuiltOnUuidColumns() throws SQLException {
        runV26();

        assertThat(queryString("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid = 'user_article_likes'::regclass AND conname = 'uq_user_article_likes_user_article'"))
                .isEqualTo("UNIQUE (user_uuid, article_uuid)");
        UUID user = UUID.randomUUID();
        UUID article = UUID.randomUUID();
        execute("INSERT INTO user_article_likes (user_uuid, article_uuid) VALUES (?, ?)", user, article);
        assertThatThrownBy(() ->
                execute("INSERT INTO user_article_likes (user_uuid, article_uuid) VALUES (?, ?)", user, article))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
    }

    @Test
    @DisplayName("uq_user_bookmarks_user_article 重建為 UNIQUE (user_uuid, article_uuid) 並生效（重複收藏被拒絕）")
    void uniqueBookmarkConstraint_isRebuiltOnUuidColumns() throws SQLException {
        runV26();

        assertThat(queryString("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid = 'user_bookmarks'::regclass AND conname = 'uq_user_bookmarks_user_article'"))
                .isEqualTo("UNIQUE (user_uuid, article_uuid)");
        UUID user = UUID.randomUUID();
        UUID article = UUID.randomUUID();
        execute("INSERT INTO user_bookmarks (user_uuid, article_uuid) VALUES (?, ?)", user, article);
        assertThatThrownBy(() ->
                execute("INSERT INTO user_bookmarks (user_uuid, article_uuid) VALUES (?, ?)", user, article))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
    }

    @Test
    @DisplayName("INSERT ... ON CONFLICT (user_uuid, article_uuid) DO NOTHING 依賴新 UNIQUE，重複寫入回傳 0 列")
    void onConflictDoNothing_worksOnRebuiltUniqueConstraints() throws SQLException {
        runV26();
        UUID user = UUID.randomUUID();
        UUID article = UUID.randomUUID();

        for (String table : List.of("user_article_likes", "user_bookmarks")) {
            String sql = "INSERT INTO " + table + " (user_uuid, article_uuid) VALUES (?, ?) "
                    + "ON CONFLICT (user_uuid, article_uuid) DO NOTHING";
            assertThat(executeUpdate(sql, user, article)).as("%s 第一次", table).isEqualTo(1);
            assertThat(executeUpdate(sql, user, article)).as("%s 第二次", table).isZero();
        }
    }

    @Test
    @DisplayName("刪除連動與校正用的 article_uuid 單欄索引存在（UNIQUE 以 user_uuid 開頭，無法支援）")
    void articleUuidIndexes_exist() throws SQLException {
        runV26();

        assertThat(queryString("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_user_article_likes_article_uuid'"))
                .isEqualTo("CREATE INDEX idx_user_article_likes_article_uuid ON public.user_article_likes "
                        + "USING btree (article_uuid)");
        assertThat(queryString("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_user_bookmarks_article_uuid'"))
                .isEqualTo("CREATE INDEX idx_user_bookmarks_article_uuid ON public.user_bookmarks "
                        + "USING btree (article_uuid)");
    }

    // ─────────────────────────── 孤兒保護 ───────────────────────────

    @Test
    @DisplayName("找不到使用者的按讚 → migration 中止並整體回滾，不刪任何按讚")
    void orphanLikeWithoutUser_abortsMigrationWithoutDeletingAnything() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "first");
        insertLike(alice.id(), article.id());
        dropForeignKeys("user_article_likes");
        insertLike(987_654_321L, article.id());

        assertThatThrownBy(this::runV26)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("找不到對應使用者");

        assertThat(queryLong("SELECT COUNT(*) FROM user_article_likes")).isEqualTo(2L);
        assertThat(columnExists("user_article_likes", "user_id")).isTrue();
        assertThat(columnExists("user_article_likes", "user_uuid")).isFalse();
    }

    @Test
    @DisplayName("找不到文章的按讚 → migration 中止並整體回滾，不刪任何按讚")
    void orphanLikeWithoutArticle_abortsMigrationWithoutDeletingAnything() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "first");
        insertLike(alice.id(), article.id());
        dropForeignKeys("user_article_likes");
        insertLike(alice.id(), 987_654_321L);

        assertThatThrownBy(this::runV26)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("找不到對應文章");

        assertThat(queryLong("SELECT COUNT(*) FROM user_article_likes")).isEqualTo(2L);
        assertThat(columnExists("user_article_likes", "article_id")).isTrue();
    }

    @Test
    @DisplayName("找不到使用者的收藏 → migration 中止並整體回滾，不刪任何收藏")
    void orphanBookmarkWithoutUser_abortsMigrationWithoutDeletingAnything() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "first");
        insertBookmark(alice.id(), article.id());
        dropForeignKeys("user_bookmarks");
        insertBookmark(987_654_321L, article.id());

        assertThatThrownBy(this::runV26)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("找不到對應使用者");

        assertThat(queryLong("SELECT COUNT(*) FROM user_bookmarks")).isEqualTo(2L);
        assertThat(columnExists("user_bookmarks", "user_id")).isTrue();
        assertThat(columnExists("user_bookmarks", "user_uuid")).isFalse();
    }

    @Test
    @DisplayName("找不到文章的收藏 → migration 中止並整體回滾，不刪任何收藏")
    void orphanBookmarkWithoutArticle_abortsMigrationWithoutDeletingAnything() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "first");
        insertBookmark(alice.id(), article.id());
        dropForeignKeys("user_bookmarks");
        insertBookmark(alice.id(), 987_654_321L);

        assertThatThrownBy(this::runV26)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("找不到對應文章");

        assertThat(queryLong("SELECT COUNT(*) FROM user_bookmarks")).isEqualTo(2L);
        assertThat(columnExists("user_bookmarks", "article_id")).isTrue();
    }

    // ─────────────────────────── 前後對照（供 PR 說明引用） ───────────────────────────

    @Test
    @DisplayName("遷移前後的 pg_indexes／pg_constraint 對照（印到 log，並釘住遷移後的完整清單）")
    void indexAndConstraintComparison() throws SQLException {
        printSection("V25（遷移前）pg_indexes", queryStrings(INDEXES_SQL));
        printSection("V25（遷移前）pg_constraint", queryStrings(CONSTRAINTS_SQL));

        runV26();

        List<String> indexes = queryStrings(INDEXES_SQL);
        List<String> constraints = queryStrings(CONSTRAINTS_SQL);
        printSection("V26（遷移後）pg_indexes", indexes);
        printSection("V26（遷移後）pg_constraint", constraints);

        /** 注意：user_article_likes 的主鍵索引沿用 V1 的舊表名 article_likes_pkey（V15 改表名時未改 PK 名稱） */
        assertThat(indexes).containsExactly(
                "user_article_likes | article_likes_pkey | CREATE UNIQUE INDEX article_likes_pkey ON public.user_article_likes USING btree (id)",
                "user_article_likes | idx_user_article_likes_article_uuid | CREATE INDEX idx_user_article_likes_article_uuid ON public.user_article_likes USING btree (article_uuid)",
                "user_article_likes | uq_user_article_likes_user_article | CREATE UNIQUE INDEX uq_user_article_likes_user_article ON public.user_article_likes USING btree (user_uuid, article_uuid)",
                "user_bookmarks | idx_user_bookmarks_article_uuid | CREATE INDEX idx_user_bookmarks_article_uuid ON public.user_bookmarks USING btree (article_uuid)",
                "user_bookmarks | uq_user_bookmarks_user_article | CREATE UNIQUE INDEX uq_user_bookmarks_user_article ON public.user_bookmarks USING btree (user_uuid, article_uuid)",
                "user_bookmarks | user_bookmarks_pkey | CREATE UNIQUE INDEX user_bookmarks_pkey ON public.user_bookmarks USING btree (id)");
        assertThat(constraints).containsExactly(
                "user_article_likes | article_likes_pkey | PRIMARY KEY (id)",
                "user_article_likes | uq_user_article_likes_user_article | UNIQUE (user_uuid, article_uuid)",
                "user_bookmarks | uq_user_bookmarks_user_article | UNIQUE (user_uuid, article_uuid)",
                "user_bookmarks | user_bookmarks_pkey | PRIMARY KEY (id)");
    }

    /**
     * 把一段對照資料印到標準輸出，供 PR 說明引用（此測試是整個類別中唯一輸出內容的地方）。
     */
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

    private void runV26() {
        flyway("26").migrate();
    }

    // ─────────────────────────── 測試資料組裝（V25 的欄位） ───────────────────────────

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

    private long insertLike(long userId, long articleId) throws SQLException {
        return queryLong("INSERT INTO user_article_likes (user_id, article_id) VALUES (?, ?) RETURNING id",
                userId, articleId);
    }

    private long insertBookmark(long userId, long articleId) throws SQLException {
        return queryLong("INSERT INTO user_bookmarks (user_id, article_id) VALUES (?, ?) RETURNING id",
                userId, articleId);
    }

    /**
     * 拿掉指定表上的所有 FK（V25 時 user_id／article_id 有 FK、理論上不會有孤兒；
     * 這裡先拿掉 FK 再製造孤兒，驗證保護機制本身）。
     */
    private void dropForeignKeys(String table) throws SQLException {
        execute("DO $$ DECLARE r RECORD; BEGIN "
                + "FOR r IN SELECT conname FROM pg_constraint WHERE contype = 'f' AND conrelid = '" + table
                + "'::regclass LOOP EXECUTE format('ALTER TABLE " + table
                + " DROP CONSTRAINT %I', r.conname); END LOOP; END $$");
    }

    // ─────────────────────────── 查詢輔助 ───────────────────────────

    private UUID likeUserUuid(long id) throws SQLException {
        return queryUuid("SELECT user_uuid FROM user_article_likes WHERE id = ?", id);
    }

    private UUID likeArticleUuid(long id) throws SQLException {
        return queryUuid("SELECT article_uuid FROM user_article_likes WHERE id = ?", id);
    }

    private UUID bookmarkUserUuid(long id) throws SQLException {
        return queryUuid("SELECT user_uuid FROM user_bookmarks WHERE id = ?", id);
    }

    private UUID bookmarkArticleUuid(long id) throws SQLException {
        return queryUuid("SELECT article_uuid FROM user_bookmarks WHERE id = ?", id);
    }

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
        executeUpdate(sql, params);
    }

    private int executeUpdate(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
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
