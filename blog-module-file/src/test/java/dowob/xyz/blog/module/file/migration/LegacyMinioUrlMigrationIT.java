package dowob.xyz.blog.module.file.migration;

import org.flywaydb.core.Flyway;
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
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V21 資料遷移整合測試：把舊內文裡的 MinIO 絕對網址收斂到 {@code /api/v1/files/{id}/content}。
 *
 * <h2>為什麼需要這支測試</h2>
 * <p>V21 會<b>改寫使用者的文章內文</b>，而改寫靠的是一段正則。正則寫錯的後果不是報錯，
 * 是安靜地把內容改壞（吞掉後面的 markdown 語法、或漏掉該改的網址讓圖片繼續繞過授權），
 * 因此必須有測試釘住「改什麼、不改什麼」。</p>
 *
 * <h2>測試方式</h2>
 * <p>刻意不啟動 Spring context：資料遷移要驗的是「先有舊資料、再跑 migration」的時序，
 * 而 Spring 管理的 Flyway 在 context 啟動時就跑完了，測試沒有機會先塞舊資料。
 * 這裡改為程式化操作 Flyway——先 migrate 到 V20（V21 之前的狀態）、塞入 V20 時代格式的
 * 舊資料、再單獨跑 V21，這正是正式環境升級時實際發生的順序。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Testcontainers
@DisplayName("V21 舊 MinIO 絕對網址資料遷移")
class LegacyMinioUrlMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    /** V20 時代的舊格式：{minio.endpoint}/{bucket}/{storage_path} */
    private static final String LEGACY_ENDPOINT = "http://localhost:9000/blog-files/";

    private static Connection connection;

    @BeforeAll
    static void openConnection() throws SQLException {
        connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /**
     * 每個測試都從乾淨的 V20 狀態開始：清空資料庫後 migrate 到 V20。
     */
    @BeforeEach
    void migrateToV20BeforeSeeding() {
        flyway().clean();
        flyway().migrate();
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(MIGRATION_LOCATION)
                .target("20")
                .cleanDisabled(false)
                .load();
    }

    /** 單獨執行 V21——模擬正式環境「舊資料已存在，然後升級」的時序 */
    private void runV21() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(MIGRATION_LOCATION)
                .target("21")
                .cleanDisabled(false)
                .load()
                .migrate();
    }

    // ─────────────────────────── 測試資料組裝 ───────────────────────────

    private long insertUser(UUID userUuid, String email) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users (uuid, email, password_hash, nickname, username, role, status) "
                        + "VALUES (?, ?, 'x', 'nick', ?, 'AUTHOR', 'ACTIVE') RETURNING id")) {
            ps.setObject(1, userUuid);
            ps.setString(2, email);
            ps.setString(3, email);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private UUID insertArticle(long authorId, String slug, String contentMd, String contentHtml) throws SQLException {
        UUID articleUuid = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO articles (uuid, author_id, title, slug, content_md, content_html, status) "
                        + "VALUES (?, ?, '標題', ?, ?, ?, 'PUBLISHED')")) {
            ps.setObject(1, articleUuid);
            ps.setLong(2, authorId);
            ps.setString(3, slug);
            ps.setString(4, contentMd);
            ps.setString(5, contentHtml);
            ps.executeUpdate();
        }
        return articleUuid;
    }

    private UUID insertFile(UUID uploaderUuid, String storagePath) throws SQLException {
        UUID fileId = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO file_metadata (id, original_name, storage_path, content_type, size, usage_type, uploader_id) "
                        + "VALUES (?, 'a.png', ?, 'image/png', 100, 'ARTICLE_CONTENT', ?)")) {
            ps.setObject(1, fileId);
            ps.setString(2, storagePath);
            ps.setObject(3, uploaderUuid);
            ps.executeUpdate();
        }
        return fileId;
    }

    private String contentMdOf(UUID articleUuid) throws SQLException {
        return queryString("SELECT content_md FROM articles WHERE uuid = ?", articleUuid);
    }

    private String contentHtmlOf(UUID articleUuid) throws SQLException {
        return queryString("SELECT content_html FROM articles WHERE uuid = ?", articleUuid);
    }

    private UUID boundArticleOf(UUID fileId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT article_uuid FROM file_metadata WHERE id = ?")) {
            ps.setObject(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private String queryString(String sql, UUID key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    // ─────────────────────────── 測試 ───────────────────────────

    @Test
    @DisplayName("內文的 MinIO 絕對網址改寫為 /api/v1/files/{id}/content，且不吞掉 markdown 語法")
    void migrate_absoluteMinioUrl_rewrittenToRelativePathInBothColumns() throws SQLException {
        UUID authorUuid = UUID.randomUUID();
        long authorId = insertUser(authorUuid, "author@test.local");
        String storagePath = "articles/2026/07/26/45c20c82-2727-4809-b2f8-78eca83b74e7.png";
        UUID fileId = insertFile(authorUuid, storagePath);

        String legacyUrl = LEGACY_ENDPOINT + storagePath;
        UUID articleUuid = insertArticle(authorId, "a1",
                "前言\n\n![示意圖](" + legacyUrl + ")\n\n後記",
                "<p><img src=\"" + legacyUrl + "\" alt=\"示意圖\"></p>");

        runV21();

        String expectedUrl = "/api/v1/files/" + fileId + "/content";
        assertThat(contentMdOf(articleUuid))
                .as("markdown 的圖片連結需改寫，且結尾的 ) 不可被吞掉")
                .isEqualTo("前言\n\n![示意圖](" + expectedUrl + ")\n\n後記");
        assertThat(contentHtmlOf(articleUuid))
                .as("content_html 一併改寫，已發布文章不必等作者重存就能正常顯示")
                .isEqualTo("<p><img src=\"" + expectedUrl + "\" alt=\"示意圖\"></p>");
    }

    @Test
    @DisplayName("回填綁定：上傳者即文章作者時，article_uuid 應指向該文章")
    void migrate_uploaderIsAuthor_backfillsArticleBinding() throws SQLException {
        UUID authorUuid = UUID.randomUUID();
        long authorId = insertUser(authorUuid, "author@test.local");
        String storagePath = "articles/2026/07/26/aaaaaaaa-2727-4809-b2f8-78eca83b74e7.png";
        UUID fileId = insertFile(authorUuid, storagePath);
        UUID articleUuid = insertArticle(authorId, "a2",
                "![圖](" + LEGACY_ENDPOINT + storagePath + ")", null);

        runV21();

        assertThat(boundArticleOf(fileId))
                .as("回填綁定後，圖片才會隨文章的公開狀態決定可讀性")
                .isEqualTo(articleUuid);
    }

    @Test
    @DisplayName("擁有權不變量：上傳者不是文章作者時，不得回填綁定")
    void migrate_uploaderIsNotAuthor_doesNotBackfillBinding() throws SQLException {
        UUID authorUuid = UUID.randomUUID();
        UUID strangerUuid = UUID.randomUUID();
        long authorId = insertUser(authorUuid, "author@test.local");
        insertUser(strangerUuid, "stranger@test.local");
        String storagePath = "articles/2026/07/26/bbbbbbbb-2727-4809-b2f8-78eca83b74e7.png";
        UUID fileId = insertFile(strangerUuid, storagePath);
        insertArticle(authorId, "a3", "![圖](" + LEGACY_ENDPOINT + storagePath + ")", null);

        runV21();

        assertThat(boundArticleOf(fileId))
                .as("沿用 FileServiceImpl#bindToArticle 的擁有權不變量，"
                        + "避免舊內容被拿來把他人檔案掛到自己的已發布文章上")
                .isNull();
    }

    @Test
    @DisplayName("冪等：已是相對路徑的內文不得再被改寫")
    void migrate_alreadyRelativePath_leftUntouched() throws SQLException {
        UUID authorUuid = UUID.randomUUID();
        long authorId = insertUser(authorUuid, "author@test.local");
        String storagePath = "articles/2026/07/26/cccccccc-2727-4809-b2f8-78eca83b74e7.png";
        UUID fileId = insertFile(authorUuid, storagePath);
        String alreadyMigrated = "![圖](/api/v1/files/" + fileId + "/content)";
        UUID articleUuid = insertArticle(authorId, "a4", alreadyMigrated, null);

        runV21();

        assertThat(contentMdOf(articleUuid)).isEqualTo(alreadyMigrated);
    }

    @Test
    @DisplayName("不越界：與本站檔案無關的外部圖片網址不得被動到")
    void migrate_unrelatedExternalUrl_leftUntouched() throws SQLException {
        UUID authorUuid = UUID.randomUUID();
        long authorId = insertUser(authorUuid, "author@test.local");
        insertFile(authorUuid, "articles/2026/07/26/dddddddd-2727-4809-b2f8-78eca83b74e7.png");
        String external = "![外站圖](https://example.com/images/logo.png)";
        UUID articleUuid = insertArticle(authorId, "a5", external, null);

        runV21();

        assertThat(contentMdOf(articleUuid)).isEqualTo(external);
    }

    @Test
    @DisplayName("多張圖：同一篇文章內的多個舊網址都要被改寫，不可只改到第一個")
    void migrate_multipleLegacyUrlsInOneArticle_allRewritten() throws SQLException {
        UUID authorUuid = UUID.randomUUID();
        long authorId = insertUser(authorUuid, "author@test.local");
        String path1 = "articles/2026/07/26/11111111-2727-4809-b2f8-78eca83b74e7.png";
        String path2 = "articles/2026/07/26/22222222-2727-4809-b2f8-78eca83b74e7.png";
        UUID file1 = insertFile(authorUuid, path1);
        UUID file2 = insertFile(authorUuid, path2);
        UUID articleUuid = insertArticle(authorId, "a6",
                "![一](" + LEGACY_ENDPOINT + path1 + ")\n![二](" + LEGACY_ENDPOINT + path2 + ")", null);

        runV21();

        assertThat(contentMdOf(articleUuid)).isEqualTo(
                "![一](/api/v1/files/" + file1 + "/content)\n"
                        + "![二](/api/v1/files/" + file2 + "/content)");
    }

    /** 收尾：避免連線洩漏影響同 JVM 內其他測試 */
    @org.junit.jupiter.api.AfterAll
    static void closeConnection() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            try (Statement ignored = connection.createStatement()) {
                connection.close();
            }
        }
    }
}
