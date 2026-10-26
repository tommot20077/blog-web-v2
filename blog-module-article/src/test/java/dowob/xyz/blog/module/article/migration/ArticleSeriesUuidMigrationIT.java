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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V32 遷移測試：{@code articles.series_id}（→ {@code series(id)} ON DELETE SET NULL）改為
 * {@code series_uuid}，並解除 article 對 series 的資料庫耦合（M6-b，系列維度）。
 *
 * <p>比照 V31 的遷移測試：先 migrate 到 V31 並灌入舊資料（Long 識別），再單獨執行 V32，
 * 模擬「既有資料已存在，然後升級」的時序。本測試固定在 V32（只 migrate 到 target），
 * 不會受之後新增的 migration 影響其前置狀態。</p>
 *
 * <p>刪除連動由 FK 的 {@code ON DELETE SET NULL} 改為訂閱 {@code SeriesDeletedEvent}＋每日校正，
 * 所以本測試也釘住「FK 已拿掉、刪除 series 不再動到 articles」。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Testcontainers
@DisplayName("V32 articles.series_id 改 series_uuid 遷移")
class ArticleSeriesUuidMigrationIT {

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
            "SELECT uuid::text || '|' || author_uuid::text || '|' || title || '|' || slug || '|' || content_md || '|' "
                    + "|| status || '|' || view_count || '|' || like_count || '|' || comment_count || '|' || version "
                    + "|| '|' || created_at::text || '|' || updated_at::text FROM articles WHERE id = ?";

    private static final String SERIES_INDEX_V31 =
            "articles | idx_articles_series_position | CREATE INDEX idx_articles_series_position ON public.articles "
                    + "USING btree (series_id, series_position) WHERE (series_id IS NOT NULL)";

    private static final String SERIES_INDEX_V32 =
            "articles | idx_articles_series_position | CREATE INDEX idx_articles_series_position ON public.articles "
                    + "USING btree (series_uuid, series_position) WHERE (series_uuid IS NOT NULL)";

    private static final String SERIES_FK_V31 =
            "articles | articles_series_id_fkey | FOREIGN KEY (series_id) REFERENCES series(id) ON DELETE SET NULL";

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

    /** 每個測試都從乾淨的 V31 狀態開始。 */
    @BeforeEach
    void migrateToV31BeforeSeeding() {
        flyway("31").clean();
        flyway("31").migrate();
    }

    // ─────────────────────────── 回填 ───────────────────────────

    @Test
    @DisplayName("既有 articles 依 series_id 回填為系列的 uuid；無系列者維持 NULL；series_position 與其餘欄位原封不動")
    void existingArticles_areBackfilledWithSeriesUuid_nullStaysNull_otherColumnsUntouched() throws SQLException {
        Series alpha = insertSeries("alpha");
        Series beta = insertSeries("beta");
        long first = insertArticle("first", alpha.id(), 1);
        long second = insertArticle("second", beta.id(), 7);
        long loner = insertArticle("loner", null, null);
        String before = queryString(ROW_DIGEST_SQL, first);

        runV32();

        assertThat(queryUuid("SELECT series_uuid FROM articles WHERE id = ?", first)).isEqualTo(alpha.uuid());
        assertThat(queryUuid("SELECT series_uuid FROM articles WHERE id = ?", second)).isEqualTo(beta.uuid());
        assertThat(queryUuid("SELECT series_uuid FROM articles WHERE id = ?", loner)).isNull();
        assertThat(queryLong("SELECT series_position FROM articles WHERE id = ?", first)).isEqualTo(1L);
        assertThat(queryLong("SELECT series_position FROM articles WHERE id = ?", second)).isEqualTo(7L);
        assertThat(queryString("SELECT series_position::text FROM articles WHERE id = ?", loner)).isNull();
        assertThat(queryLong("SELECT COUNT(*) FROM articles")).isEqualTo(3L);
        assertThat(queryString(ROW_DIGEST_SQL, first)).isEqualTo(before);
    }

    // ─────────────────────────── 欄位 ───────────────────────────

    @Test
    @DisplayName("舊的 series_id 已不存在；series_uuid 是可為 NULL 的 uuid 型別；series_position 保留")
    void legacyColumnDropped_andSeriesUuidIsNullableUuid() throws SQLException {
        runV32();

        assertThat(columnExists("articles", "series_id")).isFalse();
        assertThat(queryString("SELECT is_nullable || '/' || data_type FROM information_schema.columns "
                + "WHERE table_name = 'articles' AND column_name = 'series_uuid'")).isEqualTo("YES/uuid");
        assertThat(columnExists("articles", "series_position")).isTrue();
    }

    // ─────────────────────────── FK ───────────────────────────

    @Test
    @DisplayName("articles 沒有任何指向 series 的 FK；M6 完成後 articles 沒有任何外向 FK（沒有跨模組 FK）")
    void articlesHasNoOutgoingForeignKeys() throws SQLException {
        runV32();

        assertThat(queryLong("SELECT COUNT(*) FROM pg_constraint WHERE contype = 'f' "
                + "AND conrelid = 'articles'::regclass AND confrelid = 'series'::regclass")).isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM pg_constraint WHERE contype = 'f' "
                + "AND conrelid = 'articles'::regclass")).isZero();
    }

    @Test
    @DisplayName("新的 series_uuid 不建 FK：寫入不存在的系列 uuid 不會被拒絕")
    void seriesUuid_hasNoForeignKey() throws SQLException {
        runV32();

        execute("INSERT INTO articles (uuid, author_uuid, title, slug, content_md, series_uuid, series_position) "
                + "VALUES (?, ?, 't', 's', 'md', ?, 1)", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(queryLong("SELECT COUNT(*) FROM articles")).isEqualTo(1L);
    }

    @Test
    @DisplayName("刪除 series 不再連動 articles：FK 已拿掉，文章的 series_uuid 維持原值（清空改由事件與每日校正負責）")
    void deletingSeries_doesNotTouchArticlesAnymore() throws SQLException {
        Series alpha = insertSeries("alpha");
        long article = insertArticle("member", alpha.id(), 3);
        runV32();
        String before = queryString(ROW_DIGEST_SQL, article);

        execute("DELETE FROM series WHERE id = ?", alpha.id());

        assertThat(queryUuid("SELECT series_uuid FROM articles WHERE id = ?", article)).isEqualTo(alpha.uuid());
        assertThat(queryLong("SELECT series_position FROM articles WHERE id = ?", article)).isEqualTo(3L);
        assertThat(queryString(ROW_DIGEST_SQL, article)).isEqualTo(before);
    }

    // ─────────────────────────── 索引 ───────────────────────────

    @Test
    @DisplayName("idx_articles_series_position 以同名在 (series_uuid, series_position) 上重建，仍是 WHERE series_uuid IS NOT NULL 的 partial index")
    void seriesPositionIndex_isRebuiltOnSeriesUuidAsPartial() throws SQLException {
        runV32();

        assertThat(queryString("SELECT 'articles | ' || indexname || ' | ' || indexdef FROM pg_indexes "
                + "WHERE indexname = 'idx_articles_series_position'")).isEqualTo(SERIES_INDEX_V32);
    }

    // ─────────────────────────── 孤兒保護 ───────────────────────────

    @Test
    @DisplayName("series_id 非 NULL 卻找不到系列 → migration 中止並整體回滾，不刪任何文章")
    void orphanArticleWithoutSeries_abortsMigrationWithoutDeletingAnything() throws SQLException {
        Series alpha = insertSeries("alpha");
        insertArticle("ok", alpha.id(), 1);
        execute("ALTER TABLE articles DROP CONSTRAINT articles_series_id_fkey");
        insertArticle("orphan", 987_654_321L, 2);

        assertThatThrownBy(this::runV32)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("找不到對應系列");

        assertThat(queryLong("SELECT COUNT(*) FROM articles")).isEqualTo(2L);
        assertThat(columnExists("articles", "series_id")).isTrue();
        assertThat(columnExists("articles", "series_uuid")).isFalse();
    }

    // ─────────────────────────── 鎖順序（M4-b 審查留給 M6 的事項） ───────────────────────────

    /**
     * 讓「文章列」被另一個交易鎖住，再於另一條連線刪除該文章所屬的系列，回傳刪除遇到的錯誤（沒有則為 null）。
     *
     * <p>刪除連線設 {@code lock_timeout}：若刪除需要等文章列的鎖，就會以 SQLSTATE 55P03 失敗，
     * 而不是無限等待。</p>
     */
    private SQLException deleteSeriesWhileMemberArticleRowIsLocked(long seriesId, long articleId) throws SQLException {
        try (Connection holder = newConnection(); Connection deleter = newConnection()) {
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement()) {
                st.execute("UPDATE articles SET title = title WHERE id = " + articleId);
            }
            try (Statement st = deleter.createStatement()) {
                st.execute("SET lock_timeout = '1500ms'");
                st.executeUpdate("DELETE FROM series WHERE id = " + seriesId);
                return null;
            } catch (SQLException e) {
                return e;
            } finally {
                holder.rollback();
            }
        }
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    @DisplayName("鎖順序（遷移前，作為對照）：刪除 series 會因 ON DELETE SET NULL 去鎖成員文章的列——"
            + "文章列被別的交易鎖住時，刪除會等待（本測試以 lock_timeout 觀察到 55P03）")
    void beforeMigration_deleteSeriesWaitsForMemberArticleRowLock() throws SQLException {
        Series alpha = insertSeries("alpha");
        long article = insertArticle("member", alpha.id(), 1);

        SQLException failure = deleteSeriesWhileMemberArticleRowIsLocked(alpha.id(), article);

        assertThat((Throwable) failure).as("V31 的 FK 動作需要成員文章列的鎖，刪除應被擋住").isNotNull();
        assertThat(failure.getSQLState()).isEqualTo("55P03");
    }

    @Test
    @DisplayName("鎖順序（遷移後）：FK 已移除，刪除 series 不再觸碰 articles——文章列被別的交易鎖住時刪除也立即完成，"
            + "「deleteSeries 與 add／remove 鎖順序相反」的死鎖條件不再成立")
    void afterMigration_deleteSeriesNeverTouchesArticleRows() throws SQLException {
        Series alpha = insertSeries("alpha");
        long article = insertArticle("member", alpha.id(), 1);
        runV32();

        SQLException failure = deleteSeriesWhileMemberArticleRowIsLocked(alpha.id(), article);

        assertThat((Throwable) failure).as("V32 起刪除 series 不需要任何 articles 列的鎖").isNull();
        assertThat(queryLong("SELECT COUNT(*) FROM series WHERE id = ?", alpha.id())).isZero();
        assertThat(queryUuid("SELECT series_uuid FROM articles WHERE id = ?", article)).isEqualTo(alpha.uuid());
    }

    // ─────────────────────────── 前後對照（供 PR 說明引用） ───────────────────────────

    @Test
    @DisplayName("遷移前後的 pg_indexes／pg_constraint 對照（印到 log）：只有系列相關的索引與 FK 變動")
    void indexAndConstraintComparison() throws SQLException {
        List<String> indexesBefore = queryStrings(INDEXES_SQL);
        List<String> constraintsBefore = queryStrings(CONSTRAINTS_SQL);
        printSection("V31（遷移前）pg_indexes", indexesBefore);
        printSection("V31（遷移前）pg_constraint", constraintsBefore);

        assertThat(indexesBefore).contains(SERIES_INDEX_V31);
        assertThat(constraintsBefore).contains(SERIES_FK_V31);

        runV32();

        List<String> indexesAfter = queryStrings(INDEXES_SQL);
        List<String> constraintsAfter = queryStrings(CONSTRAINTS_SQL);
        printSection("V32（遷移後）pg_indexes", indexesAfter);
        printSection("V32（遷移後）pg_constraint", constraintsAfter);

        List<String> expectedIndexes = new ArrayList<>(indexesBefore);
        expectedIndexes.replaceAll(row -> row.equals(SERIES_INDEX_V31) ? SERIES_INDEX_V32 : row);
        assertThat(indexesAfter).containsExactlyElementsOf(expectedIndexes);

        List<String> expectedConstraints = new ArrayList<>(constraintsBefore);
        expectedConstraints.remove(SERIES_FK_V31);
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

    private void runV32() {
        flyway("32").migrate();
    }

    // ─────────────────────────── 測試資料組裝（V31 的欄位） ───────────────────────────

    private record Series(long id, UUID uuid) {
    }

    private Series insertSeries(String slug) throws SQLException {
        UUID uuid = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO series (uuid, title, slug, author_uuid) VALUES (?, ?, ?, ?) RETURNING id")) {
            ps.setObject(1, uuid);
            ps.setString(2, "系列-" + slug);
            ps.setString(3, slug);
            ps.setObject(4, UUID.randomUUID());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Series(rs.getLong(1), uuid);
            }
        }
    }

    private long insertArticle(String slug, Long seriesId, Integer position) throws SQLException {
        return queryLong("INSERT INTO articles (uuid, author_uuid, title, slug, content_md, content_html, status, "
                + "view_count, like_count, comment_count, series_id, series_position) "
                + "VALUES (?, ?, ?, ?, 'md', '<p>html</p>', 'PUBLISHED', 5, 3, 2, ?, ?) RETURNING id",
                UUID.randomUUID(), UUID.randomUUID(), "標題-" + slug, slug, seriesId, position);
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
