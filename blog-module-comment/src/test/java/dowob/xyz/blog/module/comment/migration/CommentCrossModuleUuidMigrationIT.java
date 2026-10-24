package dowob.xyz.blog.module.comment.migration;

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
 * V25 遷移測試：comment 模組的跨模組識別全面改為 UUID，並解除對 users／articles 的資料庫耦合。
 *
 * <p>比照 {@link CommentArticleUuidMigrationIT}：先 migrate 到 V24 並灌入舊資料（Long 識別），
 * 再單獨執行 V25，模擬「既有留言已存在，然後升級」的時序——一般 IT 從空資料庫一路 migrate，測不到回填。
 * 本測試固定在 V25，{@code CommentArticleUuidMigrationIT} 固定在 V24，兩者互不影響。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Testcontainers
@DisplayName("V25 comments／comment_likes 跨模組識別改 UUID 遷移")
class CommentCrossModuleUuidMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    private static final String INDEX_AND_CONSTRAINT_ORDER_SQL_INDEXES =
            "SELECT tablename || ' | ' || indexname || ' | ' || indexdef FROM pg_indexes "
                    + "WHERE tablename IN ('comments','comment_likes') ORDER BY tablename, indexname";

    private static final String CONSTRAINTS_SQL =
            "SELECT conrelid::regclass || ' | ' || conname || ' | ' || pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conrelid IN ('comments'::regclass, 'comment_likes'::regclass) "
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
     * 每個測試都從乾淨的 V24 狀態開始。
     */
    @BeforeEach
    void migrateToV24BeforeSeeding() {
        flyway("24").clean();
        flyway("24").migrate();
    }

    // ─────────────────────────── 回填 ───────────────────────────

    @Test
    @DisplayName("既有留言（含回覆）依 user_id 回填為留言者的 uuid")
    void existingComments_areBackfilledWithTheirUserUuid() throws SQLException {
        User alice = insertUser("alice@example.com");
        User bob = insertUser("bob@example.com");
        Article article = insertArticle(alice.id(), "first");
        long topLevel = insertComment(article, alice.id(), null);
        long reply = insertComment(article, bob.id(), topLevel);

        runV25();

        assertThat(commentUserUuid(topLevel)).isEqualTo(alice.uuid());
        assertThat(commentUserUuid(reply)).isEqualTo(bob.uuid());
    }

    @Test
    @DisplayName("既有留言讚依 user_id 回填為按讚者的 uuid，且仍掛在原本的留言上")
    void existingCommentLikes_areBackfilledWithTheirUserUuid() throws SQLException {
        User alice = insertUser("alice@example.com");
        User bob = insertUser("bob@example.com");
        Article article = insertArticle(alice.id(), "first");
        long comment = insertComment(article, alice.id(), null);
        long likeByAlice = insertLike(alice.id(), comment);
        long likeByBob = insertLike(bob.id(), comment);

        runV25();

        assertThat(likeUserUuid(likeByAlice)).isEqualTo(alice.uuid());
        assertThat(likeUserUuid(likeByBob)).isEqualTo(bob.uuid());
        assertThat(queryLong("SELECT COUNT(*) FROM comment_likes WHERE comment_id = ?", comment)).isEqualTo(2L);
    }

    @Test
    @DisplayName("留言的其他欄位（內容、計數、parent_id、article_uuid）遷移後原封不動")
    void otherColumns_areUntouched() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "first");
        long topLevel = insertComment(article, alice.id(), null);
        long reply = insertComment(article, alice.id(), topLevel);

        runV25();

        assertThat(queryString("SELECT content FROM comments WHERE id = ?", reply)).isEqualTo("hi");
        assertThat(queryLong("SELECT parent_id FROM comments WHERE id = ?", reply)).isEqualTo(topLevel);
        assertThat(queryString("SELECT article_uuid::text FROM comments WHERE id = ?", reply))
                .isEqualTo(article.uuid().toString());
    }

    // ─────────────────────────── 欄位 ───────────────────────────

    @Test
    @DisplayName("comments.article_id、comments.user_id、comment_likes.user_id 欄位已不存在")
    void legacyLongColumns_areDropped() throws SQLException {
        runV25();

        assertThat(columnExists("comments", "article_id")).isFalse();
        assertThat(columnExists("comments", "user_id")).isFalse();
        assertThat(columnExists("comment_likes", "user_id")).isFalse();
    }

    @Test
    @DisplayName("user_uuid 在兩張表都是 NOT NULL")
    void userUuid_isNotNullOnBothTables() throws SQLException {
        runV25();

        assertThat(queryString("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'comments' AND column_name = 'user_uuid'")).isEqualTo("NO");
        assertThat(queryString("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'comment_likes' AND column_name = 'user_uuid'")).isEqualTo("NO");
    }

    // ─────────────────────────── FK ───────────────────────────

    @Test
    @DisplayName("comments_article_uuid_fkey 已不存在，comments 與 comment_likes 沒有任何指向 users／articles 的 FK")
    void crossModuleForeignKeys_areAllDropped() throws SQLException {
        runV25();

        assertThat(queryLong("SELECT COUNT(*) FROM pg_constraint WHERE conname = 'comments_article_uuid_fkey'"))
                .isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM pg_constraint "
                + "WHERE contype = 'f' AND conrelid IN ('comments'::regclass, 'comment_likes'::regclass) "
                + "AND confrelid IN ('users'::regclass, 'articles'::regclass)")).isZero();
    }

    @Test
    @DisplayName("模組內部關聯保留：comments.parent_id → comments(id)、comment_likes.comment_id → comments(id) ON DELETE CASCADE")
    void moduleInternalForeignKeys_areKept() throws SQLException {
        runV25();

        assertThat(queryString("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid = 'comments'::regclass AND contype = 'f'"))
                .isEqualTo("FOREIGN KEY (parent_id) REFERENCES comments(id) ON DELETE CASCADE");
        assertThat(queryString("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid = 'comment_likes'::regclass AND contype = 'f'"))
                .isEqualTo("FOREIGN KEY (comment_id) REFERENCES comments(id) ON DELETE CASCADE");
    }

    @Test
    @DisplayName("新的 user_uuid／article_uuid 欄位不建 FK：寫入不存在的使用者 uuid 不會被拒絕")
    void userUuid_hasNoForeignKey() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "first");
        runV25();

        execute("INSERT INTO comments (article_uuid, user_uuid, content) VALUES (?, ?, 'ghost')",
                article.uuid(), UUID.randomUUID());

        assertThat(queryLong("SELECT COUNT(*) FROM comments WHERE content = 'ghost'")).isEqualTo(1L);
    }

    @Test
    @DisplayName("刪除文章不再連帶刪除留言（FK 已移除，改由事件＋校正排程負責）")
    void deletingArticle_noLongerCascadesToComments() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "doomed");
        insertComment(article, alice.id(), null);
        runV25();

        execute("DELETE FROM articles WHERE id = ?", article.id());

        assertThat(queryLong("SELECT COUNT(*) FROM comments")).isEqualTo(1L);
    }

    @Test
    @DisplayName("刪除留言仍會連帶刪除其留言讚（模組內 FK CASCADE 不變）")
    void deletingComment_stillCascadesToItsLikes() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "first");
        long comment = insertComment(article, alice.id(), null);
        insertLike(alice.id(), comment);
        runV25();

        execute("DELETE FROM comments WHERE id = ?", comment);

        assertThat(queryLong("SELECT COUNT(*) FROM comment_likes")).isZero();
    }

    // ─────────────────────────── UNIQUE 與索引 ───────────────────────────

    @Test
    @DisplayName("uq_comment_likes_user_comment 重建為 UNIQUE (user_uuid, comment_id) 並生效（重複按讚被拒絕）")
    void uniqueLikeConstraint_isRebuiltOnUserUuid() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "first");
        long comment = insertComment(article, alice.id(), null);
        runV25();

        assertThat(queryString("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid = 'comment_likes'::regclass AND conname = 'uq_comment_likes_user_comment'"))
                .isEqualTo("UNIQUE (user_uuid, comment_id)");
        execute("INSERT INTO comment_likes (user_uuid, comment_id) VALUES (?, ?)", alice.uuid(), comment);
        assertThatThrownBy(() ->
                execute("INSERT INTO comment_likes (user_uuid, comment_id) VALUES (?, ?)", alice.uuid(), comment))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
    }

    @Test
    @DisplayName("idx_comments_user_created 重建為 (user_uuid, created_at DESC)，名稱不變")
    void userCreatedIndex_isRebuiltOnUserUuid() throws SQLException {
        runV25();

        assertThat(queryString("SELECT indexdef FROM pg_indexes "
                + "WHERE tablename = 'comments' AND indexname = 'idx_comments_user_created'"))
                .isEqualTo("CREATE INDEX idx_comments_user_created ON public.comments "
                        + "USING btree (user_uuid, created_at DESC)");
    }

    @Test
    @DisplayName("idx_comments_article_top_level（article_id 版）隨欄位消失且不重建；V24 的 article_uuid 版索引保留")
    void articleIdIndex_isGone_andArticleUuidIndexesRemain() throws SQLException {
        runV25();

        assertThat(indexExists("idx_comments_article_top_level")).isFalse();
        assertThat(indexExists("idx_comments_article_uuid_top_level")).isTrue();
        assertThat(indexExists("idx_comments_article_uuid")).isTrue();
        assertThat(indexExists("idx_comments_replies")).isTrue();
    }

    // ─────────────────────────── 孤兒保護 ───────────────────────────

    @Test
    @DisplayName("找不到使用者的孤兒留言 → migration 中止並整體回滾，不刪任何留言")
    void orphanComment_abortsMigrationWithoutDeletingAnything() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "first");
        insertComment(article, alice.id(), null);
        // V24 時 user_id 有 FK、理論上不會有孤兒；這裡先拿掉 FK 再製造一筆，驗證保護機制本身
        execute("ALTER TABLE comments DROP CONSTRAINT comments_user_id_fkey");
        insertComment(article, 987_654_321L, null);

        assertThatThrownBy(this::runV25)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("找不到對應使用者");

        assertThat(queryLong("SELECT COUNT(*) FROM comments")).isEqualTo(2L);
        assertThat(columnExists("comments", "user_id")).isTrue();
        assertThat(columnExists("comments", "user_uuid")).isFalse();
    }

    @Test
    @DisplayName("找不到使用者的孤兒留言讚 → migration 中止並整體回滾，不刪任何留言讚")
    void orphanCommentLike_abortsMigrationWithoutDeletingAnything() throws SQLException {
        User alice = insertUser("alice@example.com");
        Article article = insertArticle(alice.id(), "first");
        long comment = insertComment(article, alice.id(), null);
        insertLike(alice.id(), comment);
        execute("ALTER TABLE comment_likes DROP CONSTRAINT comment_likes_user_id_fkey");
        insertLike(987_654_321L, comment);

        assertThatThrownBy(this::runV25)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("找不到對應使用者");

        assertThat(queryLong("SELECT COUNT(*) FROM comment_likes")).isEqualTo(2L);
        assertThat(columnExists("comment_likes", "user_id")).isTrue();
    }

    // ─────────────────────────── 前後對照（供 PR 說明引用） ───────────────────────────

    @Test
    @DisplayName("遷移前後的 pg_indexes／pg_constraint 對照（印到 log，並釘住遷移後的完整清單）")
    void indexAndConstraintComparison() throws SQLException {
        printSection("V24（遷移前）pg_indexes", queryStrings(INDEX_AND_CONSTRAINT_ORDER_SQL_INDEXES));
        printSection("V24（遷移前）pg_constraint", queryStrings(CONSTRAINTS_SQL));

        runV25();

        List<String> indexes = queryStrings(INDEX_AND_CONSTRAINT_ORDER_SQL_INDEXES);
        List<String> constraints = queryStrings(CONSTRAINTS_SQL);
        printSection("V25（遷移後）pg_indexes", indexes);
        printSection("V25（遷移後）pg_constraint", constraints);

        assertThat(indexes).containsExactly(
                "comment_likes | comment_likes_pkey | CREATE UNIQUE INDEX comment_likes_pkey ON public.comment_likes USING btree (id)",
                "comment_likes | uq_comment_likes_user_comment | CREATE UNIQUE INDEX uq_comment_likes_user_comment ON public.comment_likes USING btree (user_uuid, comment_id)",
                "comments | comments_pkey | CREATE UNIQUE INDEX comments_pkey ON public.comments USING btree (id)",
                "comments | comments_uuid_key | CREATE UNIQUE INDEX comments_uuid_key ON public.comments USING btree (uuid)",
                "comments | idx_comments_article_uuid | CREATE INDEX idx_comments_article_uuid ON public.comments USING btree (article_uuid)",
                "comments | idx_comments_article_uuid_top_level | CREATE INDEX idx_comments_article_uuid_top_level ON public.comments USING btree (article_uuid, created_at DESC) WHERE (parent_id IS NULL)",
                "comments | idx_comments_replies | CREATE INDEX idx_comments_replies ON public.comments USING btree (parent_id, created_at) WHERE (parent_id IS NOT NULL)",
                "comments | idx_comments_user_created | CREATE INDEX idx_comments_user_created ON public.comments USING btree (user_uuid, created_at DESC)");
        assertThat(constraints).containsExactly(
                "comment_likes | comment_likes_comment_id_fkey | FOREIGN KEY (comment_id) REFERENCES comments(id) ON DELETE CASCADE",
                "comment_likes | comment_likes_pkey | PRIMARY KEY (id)",
                "comment_likes | uq_comment_likes_user_comment | UNIQUE (user_uuid, comment_id)",
                "comments | comments_parent_id_fkey | FOREIGN KEY (parent_id) REFERENCES comments(id) ON DELETE CASCADE",
                "comments | comments_pkey | PRIMARY KEY (id)",
                "comments | comments_uuid_key | UNIQUE (uuid)");
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

    private void runV25() {
        flyway("25").migrate();
    }

    // ─────────────────────────── 測試資料組裝（V24 的欄位） ───────────────────────────

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

    /**
     * 以 V24 時的欄位寫入留言（article_id＋article_uuid 雙欄、user_id）。
     */
    private long insertComment(Article article, long userId, Long parentId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO comments (article_id, article_uuid, parent_id, user_id, content) "
                        + "VALUES (?, ?, ?, ?, 'hi') RETURNING id")) {
            ps.setLong(1, article.id());
            ps.setObject(2, article.uuid());
            ps.setObject(3, parentId);
            ps.setLong(4, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertLike(long userId, long commentId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO comment_likes (user_id, comment_id) VALUES (?, ?) RETURNING id")) {
            ps.setLong(1, userId);
            ps.setLong(2, commentId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ─────────────────────────── 查詢輔助 ───────────────────────────

    private UUID commentUserUuid(long commentId) throws SQLException {
        return queryUuid("SELECT user_uuid FROM comments WHERE id = ?", commentId);
    }

    private UUID likeUserUuid(long likeId) throws SQLException {
        return queryUuid("SELECT user_uuid FROM comment_likes WHERE id = ?", likeId);
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

    private boolean indexExists(String indexName) throws SQLException {
        return queryLong("SELECT COUNT(*) FROM pg_indexes WHERE indexname = ?", indexName) > 0;
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
