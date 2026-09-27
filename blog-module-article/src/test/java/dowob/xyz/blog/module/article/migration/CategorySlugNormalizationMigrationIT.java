package dowob.xyz.blog.module.article.migration;

import dowob.xyz.blog.module.article.model.Category;
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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V23 分類 slug 正規化遷移整合測試。
 *
 * <p>V23 會<b>改寫既有分類的 slug</b>（即改變分類網址，且不留轉址——Yuan 2026-09-27 決定），
 * 改寫規則寫錯的後果不是報錯，而是網址被改成預期外的樣子或遷移中途撞到 UNIQUE 而整批失敗。
 * 故以程式化操作 Flyway 重現正式環境的升級時序：先 migrate 到 V22、塞入 V22 時代允許的
 * 不合格 slug、再單獨跑 V23（同 {@code LegacyMinioUrlMigrationIT} 的做法）。</p>
 *
 * <p>規則：轉小寫 → 非 {@code [a-z0-9]} 的連續字元換成單一連字號 → 去除頭尾連字號；
 * 結果為空改用 {@code category-{id}}；與其他分類撞名時附加 {@code -{id}}；總長不超過 60。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Testcontainers
@DisplayName("V23 分類 slug 正規化遷移")
class CategorySlugNormalizationMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 集中式 migration 位置（blog-db-migration 模組，test scope 依賴） */
    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    /** 直接操作資料庫的連線 */
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
     * 每個測試都從乾淨的 V22 狀態開始。
     */
    @BeforeEach
    void migrateToV22BeforeSeeding() {
        flyway("22").clean();
        flyway("22").migrate();
    }

    @Test
    @DisplayName("已合格的 slug 原封不動")
    void conformingSlugs_areUntouched() throws SQLException {
        long backend = insertCategory("後端", "backend");
        long frontEnd = insertCategory("前端開發", "front-end");

        runV23();

        assertThat(slugOf(backend)).isEqualTo("backend");
        assertThat(slugOf(frontEnd)).isEqualTo("front-end");
    }

    @Test
    @DisplayName("大寫轉小寫，名稱等其他欄位不受影響")
    void uppercase_isLowercased() throws SQLException {
        long id = insertCategory("Backend", "Backend");

        runV23();

        assertThat(slugOf(id)).isEqualTo("backend");
        assertThat(queryString("SELECT name FROM categories WHERE id = ?", id)).isEqualTo("Backend");
    }

    @Test
    @DisplayName("逗號、空白、符號的連續字元換成單一連字號，並去除頭尾連字號")
    void symbolsAndSpaces_collapseToSingleHyphens() throws SQLException {
        long comma = insertCategory("逗號", "a,b");
        long mixed = insertCategory("混合", "  Web Dev,2024! ");

        runV23();

        assertThat(slugOf(comma)).isEqualTo("a-b");
        assertThat(slugOf(mixed)).isEqualTo("web-dev-2024");
    }

    @Test
    @DisplayName("完全沒有英數字（如純中文）→ category-{id}")
    void noAlphanumeric_fallsBackToCategoryId() throws SQLException {
        long id = insertCategory("前端", "前端");

        runV23();

        assertThat(slugOf(id)).isEqualTo("category-" + id);
    }

    @Test
    @DisplayName("與既有合格 slug 撞名 → 附加 -{id}，既有的不動")
    void collisionWithExistingSlug_appendsId() throws SQLException {
        long existing = insertCategory("後端", "backend");
        long legacy = insertCategory("Backend 舊", "Backend");

        runV23();

        assertThat(slugOf(existing)).isEqualTo("backend");
        assertThat(slugOf(legacy)).isEqualTo("backend-" + legacy);
    }

    @Test
    @DisplayName("兩個不合格 slug 正規化後彼此撞名 → id 較小者取得原名，較大者附加 -{id}")
    void collisionBetweenNormalizedSlugs_laterOneGetsSuffix() throws SQLException {
        long cpp = insertCategory("C++", "C++");
        long csharp = insertCategory("C#", "C#");

        runV23();

        assertThat(slugOf(cpp)).isEqualTo("c");
        assertThat(slugOf(csharp)).isEqualTo("c-" + csharp);
    }

    @Test
    @DisplayName("撞名且已達 60 字 → 截短後附加 -{id}，總長仍不超過 60 且合格")
    void longSlugWithCollision_truncatedToFit() throws SQLException {
        String sixty = "a".repeat(60);
        insertCategory("長名稱一", sixty);
        long legacy = insertCategory("長名稱二", sixty.toUpperCase());

        runV23();

        String slug = slugOf(legacy);
        assertThat(slug).hasSizeLessThanOrEqualTo(60).endsWith("-" + legacy).matches(Category.SLUG_PATTERN);
    }

    @Test
    @DisplayName("遷移後所有 slug 皆合格，且 CHECK 約束拒絕之後寫入的不合格 slug")
    void afterMigration_allSlugsConformAndCheckRejectsViolations() throws SQLException {
        insertCategory("大寫", "Mixed Case");
        insertCategory("符號", "--weird__slug--");
        insertCategory("中文", "中文分類");

        runV23();

        for (String slug : allSlugs()) {
            assertThat(slug).matches(Category.SLUG_PATTERN);
        }
        assertThatThrownBy(() -> insertCategory("之後寫入", "Bad Slug"))
                .isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).as("check_violation").isEqualTo("23514"));
    }

    /**
     * 建立指定目標版本的 Flyway。
     *
     * @param target 目標版本
     * @return Flyway 實例
     */
    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(MIGRATION_LOCATION)
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    /** 單獨執行 V23——模擬正式環境「舊資料已存在，然後升級」的時序 */
    private void runV23() {
        flyway("23").migrate();
    }

    /**
     * 直接插入一筆分類（繞過 API 驗證，模擬 V23 之前允許的資料）。
     *
     * @param name 分類名稱（UNIQUE）
     * @param slug 分類 slug
     * @return 分類主鍵
     * @throws SQLException 資料庫錯誤（含 CHECK 約束違反）
     */
    private long insertCategory(String name, String slug) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO categories (name, slug) VALUES (?, ?) RETURNING id")) {
            ps.setString(1, name);
            ps.setString(2, slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * 查詢分類目前的 slug。
     *
     * @param id 分類主鍵
     * @return slug
     * @throws SQLException 資料庫錯誤
     */
    private String slugOf(long id) throws SQLException {
        return queryString("SELECT slug FROM categories WHERE id = ?", id);
    }

    /**
     * 執行單一參數、單一字串結果的查詢。
     *
     * @param sql SQL
     * @param id  參數
     * @return 查詢結果
     * @throws SQLException 資料庫錯誤
     */
    private String queryString(String sql, long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    /**
     * 取出所有分類的 slug。
     *
     * @return slug 清單
     * @throws SQLException 資料庫錯誤
     */
    private List<String> allSlugs() throws SQLException {
        List<String> slugs = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT slug FROM categories")) {
            while (rs.next()) {
                slugs.add(rs.getString(1));
            }
        }
        return slugs;
    }
}
