package dowob.xyz.blog.module.tag.migration;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V33 遷移測試：移除 {@code article_tags.article_id} → {@code articles(uuid)} 的 FK（ON DELETE CASCADE），
 * 並補上 {@code article_tags.tag_id}、{@code user_tag_follows.tag_id} 的索引（PERF-07）。
 *
 * <p>比照 V32 的遷移測試：先 migrate 到 V32 並灌入資料，再單獨執行 V33。本測試固定在 V33
 * （只 migrate 到 target），不受之後新增的 migration 影響其前置狀態。</p>
 *
 * <p>刪除連動由 FK 的 CASCADE 改為訂閱 {@code ArticleDeletedEvent}＋每日校正，所以本測試也釘住
 * 「FK 已拿掉、刪除文章不再動到 article_tags」；模組內的 {@code tag_id} → {@code tags(id)} FK 必須保留。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Testcontainers
@DisplayName("V33 article_tags 移除跨模組 FK 並補 tag_id 索引")
class ArticleTagsCrossModuleFkMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    private static final String INDEXES_SQL =
            "SELECT tablename || ' | ' || indexname || ' | ' || indexdef FROM pg_indexes "
                    + "WHERE tablename IN ('article_tags','user_tag_follows') ORDER BY tablename, indexname";

    private static final String CONSTRAINTS_SQL =
            "SELECT conrelid::regclass || ' | ' || conname || ' | ' || pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conrelid IN ('article_tags'::regclass, 'user_tag_follows'::regclass) "
                    + "ORDER BY conrelid::regclass::text, conname";

    private static final String ARTICLE_FK_V32 =
            "article_tags | article_tags_article_id_fkey | FOREIGN KEY (article_id) REFERENCES articles(uuid) ON DELETE CASCADE";

    private static final String TAG_FK =
            "article_tags | article_tags_tag_id_fkey | FOREIGN KEY (tag_id) REFERENCES tags(id)";

    private static final String ARTICLE_TAGS_TAG_INDEX =
            "article_tags | idx_article_tags_tag_id | CREATE INDEX idx_article_tags_tag_id ON public.article_tags USING btree (tag_id)";

    private static final String FOLLOWS_TAG_INDEX =
            "user_tag_follows | idx_user_tag_follows_tag_id | CREATE INDEX idx_user_tag_follows_tag_id ON public.user_tag_follows USING btree (tag_id)";

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

    /** 每個測試都從乾淨的 V32 狀態開始。 */
    @BeforeEach
    void migrateToV32BeforeSeeding() {
        flyway("32").clean();
        flyway("32").migrate();
    }

    // ─────────────────────────── FK ───────────────────────────

    @Test
    @DisplayName("遷移前（對照）：article_tags 有 article_id → articles(uuid) ON DELETE CASCADE；刪除文章會連帶刪除關聯")
    void beforeMigration_articleFkCascadesOnDelete() throws SQLException {
        UUID article = insertArticle("doomed");
        UUID tag = insertTag("java");
        execute("INSERT INTO article_tags (article_id, tag_id) VALUES (?, ?)", article, tag);
        assertThat(queryStrings(CONSTRAINTS_SQL)).contains(ARTICLE_FK_V32);

        execute("DELETE FROM articles WHERE uuid = ?", article);

        assertThat(queryLong("SELECT COUNT(*) FROM article_tags")).isZero();
    }

    @Test
    @DisplayName("article_tags 沒有任何指向 articles 的 FK；tag_id → tags(id) 的模組內 FK 保留")
    void afterMigration_crossModuleFkDropped_tagFkKept() throws SQLException {
        runV33();

        assertThat(queryLong("SELECT COUNT(*) FROM pg_constraint WHERE contype = 'f' "
                + "AND conrelid = 'article_tags'::regclass AND confrelid = 'articles'::regclass")).isZero();
        assertThat(queryStrings(CONSTRAINTS_SQL)).contains(TAG_FK).doesNotContain(ARTICLE_FK_V32);
        assertThat(queryString("SELECT contype::text FROM pg_constraint WHERE conname = 'article_tags_pkey'"))
                .as("PK (article_id, tag_id) 保留").isEqualTo("p");
    }

    @Test
    @DisplayName("FK 移除後：可寫入不存在的文章 uuid；刪除文章不再連帶刪除關聯（改由事件＋校正排程負責）")
    void afterMigration_articleDeleteNoLongerCascades_andUnknownArticleUuidAccepted() throws SQLException {
        UUID article = insertArticle("kept-links");
        UUID tag = insertTag("java");
        execute("INSERT INTO article_tags (article_id, tag_id) VALUES (?, ?)", article, tag);
        runV33();

        execute("DELETE FROM articles WHERE uuid = ?", article);
        execute("INSERT INTO article_tags (article_id, tag_id) VALUES (?, ?)", UUID.randomUUID(), tag);

        assertThat(queryLong("SELECT COUNT(*) FROM article_tags")).isEqualTo(2L);
    }

    @Test
    @DisplayName("既有資料原封不動：article_tags 與 user_tag_follows 的列數、tags 不變")
    void afterMigration_existingRowsUntouched() throws SQLException {
        UUID article = insertArticle("a1");
        UUID tagA = insertTag("java");
        UUID tagB = insertTag("spring");
        execute("INSERT INTO article_tags (article_id, tag_id) VALUES (?, ?)", article, tagA);
        execute("INSERT INTO article_tags (article_id, tag_id) VALUES (?, ?)", article, tagB);
        execute("INSERT INTO user_tag_follows (user_id, tag_id) VALUES (?, ?)", UUID.randomUUID(), tagA);

        runV33();

        assertThat(queryLong("SELECT COUNT(*) FROM article_tags")).isEqualTo(2L);
        assertThat(queryLong("SELECT COUNT(*) FROM user_tag_follows")).isEqualTo(1L);
        assertThat(queryLong("SELECT COUNT(*) FROM tags")).isEqualTo(2L);
    }

    // ─────────────────────────── 索引（PERF-07） ───────────────────────────

    @Test
    @DisplayName("遷移後 article_tags(tag_id)、user_tag_follows(tag_id) 各有一個 btree 索引；遷移前沒有")
    void afterMigration_tagIdIndexesExist() throws SQLException {
        assertThat(queryStrings(INDEXES_SQL)).doesNotContain(ARTICLE_TAGS_TAG_INDEX, FOLLOWS_TAG_INDEX);

        runV33();

        assertThat(queryStrings(INDEXES_SQL)).contains(ARTICLE_TAGS_TAG_INDEX, FOLLOWS_TAG_INDEX);
    }

    // ─────────────────────────── 前後對照（供 PR 說明引用） ───────────────────────────

    @Test
    @DisplayName("遷移前後的 pg_indexes／pg_constraint 對照（印到 log）：只有 article → articles FK 消失、兩個 tag_id 索引新增")
    void indexAndConstraintComparison() throws SQLException {
        List<String> indexesBefore = queryStrings(INDEXES_SQL);
        List<String> constraintsBefore = queryStrings(CONSTRAINTS_SQL);
        printSection("V32（遷移前）pg_indexes", indexesBefore);
        printSection("V32（遷移前）pg_constraint", constraintsBefore);

        runV33();

        List<String> indexesAfter = queryStrings(INDEXES_SQL);
        List<String> constraintsAfter = queryStrings(CONSTRAINTS_SQL);
        printSection("V33（遷移後）pg_indexes", indexesAfter);
        printSection("V33（遷移後）pg_constraint", constraintsAfter);

        List<String> expectedIndexes = new ArrayList<>(indexesBefore);
        expectedIndexes.add(ARTICLE_TAGS_TAG_INDEX);
        expectedIndexes.add(FOLLOWS_TAG_INDEX);
        assertThat(indexesAfter).containsExactlyInAnyOrderElementsOf(expectedIndexes);

        List<String> expectedConstraints = new ArrayList<>(constraintsBefore);
        expectedConstraints.remove(ARTICLE_FK_V32);
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

    private void runV33() {
        flyway("33").migrate();
    }

    // ─────────────────────────── 測試資料組裝（V32 的欄位） ───────────────────────────

    private UUID insertArticle(String slug) throws SQLException {
        UUID uuid = UUID.randomUUID();
        execute("INSERT INTO articles (uuid, author_uuid, title, slug, content_md) VALUES (?, ?, ?, ?, 'md')",
                uuid, UUID.randomUUID(), "標題-" + slug, slug);
        return uuid;
    }

    private UUID insertTag(String name) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO tags (id, name, slug) VALUES (?, ?, ?)", id, name, name);
        return id;
    }

    // ─────────────────────────── 查詢輔助 ───────────────────────────

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
