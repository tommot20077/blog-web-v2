package dowob.xyz.blog.module.comment.migration;

import org.flywaydb.core.Flyway;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V24 遷移測試：{@code comments} 新增 {@code article_uuid}（ARCH-30 第 2 段 P1 Expand）。
 *
 * <p>先 migrate 到 V23 並灌入舊資料，再單獨執行 V24，模擬「既有留言已存在，然後升級」的時序——
 * 一般 IT 從空資料庫一路 migrate，測不到回填。</p>
 */
@Testcontainers
@DisplayName("V24 comments.article_uuid 擴充遷移")
class CommentArticleUuidMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

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
     * 每個測試都從乾淨的 V23 狀態開始。
     */
    @BeforeEach
    void migrateToV23BeforeSeeding() {
        flyway("23").clean();
        flyway("23").migrate();
    }

    @Test
    @DisplayName("既有留言（含回覆）依 article_id 回填為所屬文章的 uuid")
    void existingComments_areBackfilledWithTheirArticleUuid() throws SQLException {
        long author = insertUser("author@example.com");
        Article first = insertArticle(author, "first");
        Article second = insertArticle(author, "second");
        long topLevel = insertComment(first.id(), author, null);
        long reply = insertComment(first.id(), author, topLevel);
        long other = insertComment(second.id(), author, null);

        runV24();

        assertThat(articleUuidOf(topLevel)).isEqualTo(first.uuid());
        assertThat(articleUuidOf(reply)).isEqualTo(first.uuid());
        assertThat(articleUuidOf(other)).isEqualTo(second.uuid());
    }

    @Test
    @DisplayName("回填後 article_uuid 為 NOT NULL：新留言未帶 article_uuid 會被拒絕")
    void articleUuid_isNotNullAfterMigration() throws SQLException {
        long author = insertUser("author@example.com");
        Article article = insertArticle(author, "a");

        runV24();

        assertThatThrownBy(() -> insertComment(article.id(), author, null))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23502"));
    }

    @Test
    @DisplayName("article_uuid 有 FK：指向不存在的文章會被拒絕")
    void articleUuid_mustReferenceAnExistingArticle() throws SQLException {
        long author = insertUser("author@example.com");
        Article article = insertArticle(author, "a");

        runV24();

        assertThatThrownBy(() -> insertCommentWithUuid(article.id(), UUID.randomUUID(), author))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23503"));
    }

    @Test
    @DisplayName("新 FK 明確命名為 comments_article_uuid_fkey，指向 articles(uuid) 且 ON DELETE CASCADE（刪除語意不變）")
    void newForeignKey_referencesArticleUuidWithCascade() throws SQLException {
        runV24();

        String definition = queryString(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conrelid = 'comments'::regclass AND conname = 'comments_article_uuid_fkey'");

        assertThat(definition)
                .isEqualTo("FOREIGN KEY (article_uuid) REFERENCES articles(uuid) ON DELETE CASCADE");
    }

    @Test
    @DisplayName("刪除文章時，其留言經由新舊 FK 一併刪除")
    void deletingArticle_cascadesToItsComments() throws SQLException {
        long author = insertUser("author@example.com");
        Article doomed = insertArticle(author, "doomed");
        Article kept = insertArticle(author, "kept");
        insertComment(doomed.id(), author, null);
        long survivor = insertComment(kept.id(), author, null);

        runV24();
        execute("DELETE FROM articles WHERE id = ?", doomed.id());

        assertThat(queryLong("SELECT COUNT(*) FROM comments")).isEqualTo(1L);
        assertThat(queryLong("SELECT COUNT(*) FROM comments WHERE id = ?", survivor)).isEqualTo(1L);
    }

    @Test
    @DisplayName("建立以 article_uuid 查頂層留言的 partial index，對應既有的 idx_comments_article_top_level")
    void topLevelIndexOnArticleUuid_isCreated() throws SQLException {
        runV24();

        String definition = queryString(
                "SELECT indexdef FROM pg_indexes "
                        + "WHERE tablename = 'comments' AND indexname = 'idx_comments_article_uuid_top_level'");

        assertThat(definition)
                .isEqualTo("CREATE INDEX idx_comments_article_uuid_top_level ON public.comments "
                        + "USING btree (article_uuid, created_at DESC) WHERE (parent_id IS NULL)");
    }

    @Test
    @DisplayName("建立涵蓋所有留言（含回覆）的 article_uuid 索引，供 FK CASCADE 與含回覆的計數查詢使用")
    void fullIndexOnArticleUuid_isCreatedForCascadeAndCounts() throws SQLException {
        runV24();

        String definition = queryString(
                "SELECT indexdef FROM pg_indexes "
                        + "WHERE tablename = 'comments' AND indexname = 'idx_comments_article_uuid'");

        assertThat(definition)
                .isEqualTo("CREATE INDEX idx_comments_article_uuid ON public.comments USING btree (article_uuid)");
    }

    @Test
    @DisplayName("舊的 article_id 欄位、FK 與索引在 P1 保持不變")
    void legacyArticleIdColumn_isKept() throws SQLException {
        runV24();

        assertThat(queryString(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'comments' AND column_name = 'article_id'"))
                .isEqualTo("NO");
        assertThat(queryLong(
                "SELECT COUNT(*) FROM pg_indexes "
                        + "WHERE tablename = 'comments' AND indexname = 'idx_comments_article_top_level'"))
                .isEqualTo(1L);
        assertThat(queryLong(
                "SELECT COUNT(*) FROM pg_constraint WHERE conrelid = 'comments'::regclass AND contype = 'f' "
                        + "AND pg_get_constraintdef(oid) = "
                        + "'FOREIGN KEY (article_id) REFERENCES articles(id) ON DELETE CASCADE'"))
                .isEqualTo(1L);
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

    private void runV24() {
        flyway("24").migrate();
    }

    // ─────────────────────────── 測試資料組裝 ───────────────────────────

    private record Article(long id, UUID uuid) {
    }

    private long insertUser(String email) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users (uuid, email, password_hash, nickname, username, role, status) "
                        + "VALUES (?, ?, 'x', 'nick', ?, 'AUTHOR', 'ACTIVE') RETURNING id")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, email);
            ps.setString(3, email);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
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
     * 以 V23 時的欄位寫入留言（不帶 article_uuid）。
     */
    private long insertComment(long articleId, long userId, Long parentId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO comments (article_id, parent_id, user_id, content) "
                        + "VALUES (?, ?, ?, 'hi') RETURNING id")) {
            ps.setLong(1, articleId);
            ps.setObject(2, parentId);
            ps.setLong(3, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insertCommentWithUuid(long articleId, UUID articleUuid, long userId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO comments (article_id, article_uuid, user_id, content) VALUES (?, ?, ?, 'hi')")) {
            ps.setLong(1, articleId);
            ps.setObject(2, articleUuid);
            ps.setLong(3, userId);
            ps.executeUpdate();
        }
    }

    // ─────────────────────────── 查詢輔助 ───────────────────────────

    private UUID articleUuidOf(long commentId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT article_uuid FROM comments WHERE id = ?")) {
            ps.setLong(1, commentId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private void execute(String sql, long param) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, param);
            ps.executeUpdate();
        }
    }

    private String queryString(String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
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
