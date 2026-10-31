package dowob.xyz.blog.architecture;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 資料庫內不得有跨模組外鍵的守衛（設計 {@code 2026-10-24-cross-module-uuid-design.md} §6 全域守衛 ④）。
 *
 * <p>跨模組 UUID 重構後，模組之間只以 UUID 邏輯參照，<b>資料庫層不得有指向他模組的 FK</b>
 * （刪除連動靠事件＋{@code OrphanReconciliationJob}）。本 IT 以 Testcontainers Postgres 跑完
 * {@code blog-db-migration} 的全部 Flyway migration（直接用 Flyway API，不啟 Spring context），
 * 再查 {@code pg_constraint} 取出所有 FK 的（子表, 父表），逐一比對 {@link ModuleTableOwnership#OWNER_BY_TABLE}
 * 的 owner 模組，必須相同。</p>
 *
 * <p><b>對照表防過期</b>：另斷言 migration 後 public schema 的每一張表都登記在對照表（或
 * {@link ModuleTableOwnership#IGNORED_TABLES}），且對照表的每一項都真的存在——
 * 新表沒登記、或表被刪了條目還在，都會紅。</p>
 *
 * <p><b>反向驗證</b>：在同一個資料庫內實際植入跨模組 FK
 * （{@code ALTER TABLE comments ADD CONSTRAINT … REFERENCES articles}）與未登記的新表，
 * 證明判定邏輯抓得到，之後 DROP 還原。</p>
 *
 * <p>需要 Docker；無 Docker 的環境此 IT 無法執行，以 CI 結果為準。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Testcontainers
@DisplayName("資料庫不得有跨模組外鍵（Flyway 全部 migration 後）")
class NoCrossModuleForeignKeyIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Flyway migration 位置（blog-db-migration 的 resources） */
    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    /** 取出 public schema 所有 FK 的（子表, 父表, 約束名）；排除 Flyway 自己的表由呼叫端處理 */
    private static final String FOREIGN_KEYS_SQL = """
            SELECT child.relname AS child_table, parent.relname AS parent_table, c.conname AS constraint_name
              FROM pg_constraint c
              JOIN pg_class child ON child.oid = c.conrelid
              JOIN pg_class parent ON parent.oid = c.confrelid
              JOIN pg_namespace child_ns ON child_ns.oid = child.relnamespace
             WHERE c.contype = 'f' AND child_ns.nspname = 'public'
             ORDER BY child.relname, parent.relname, c.conname
            """;

    /** public schema 的所有一般表與分割表 */
    private static final String TABLES_SQL = """
            SELECT c.relname
              FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
             ORDER BY c.relname
            """;

    private static Connection connection;

    private static MigrateResult migrateResult;

    /**
     * 一次性跑完全部 migration（所有測試共用同一個資料庫；植入探針的測試必須在 finally 還原）。
     */
    @BeforeAll
    static void migrateAll() throws SQLException {
        migrateResult = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(MIGRATION_LOCATION)
                .load()
                .migrate();
        connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @AfterAll
    static void closeConnection() throws SQLException {
        connection.close();
    }

    @Test
    @DisplayName("所有外鍵的子表與父表必須屬於同一模組")
    void everyForeignKeyMustStayWithinOneModule() throws SQLException {
        List<ForeignKey> foreignKeys = readForeignKeys();
        foreignKeys.forEach(fk -> System.out.println("[NoCrossModuleForeignKeyIT] FK " + fk.describe()));

        assertThat(findCrossModuleForeignKeys(foreignKeys, ModuleTableOwnership.OWNER_BY_TABLE))
                .as("跨模組不得有資料庫 FK：模組間以 UUID 邏輯參照，刪除連動靠事件＋OrphanReconciliationJob。"
                  + "請新增 migration 移除該 FK（並同步 ai-docs/schema.md），不要放行。")
                .isEmpty();
    }

    @Test
    @DisplayName("migration 後 public schema 的每一張表都必須登記在表→模組對照表，且對照表沒有已不存在的表")
    void everyTableMustBeRegisteredInOwnershipMap() throws SQLException {
        List<String> tables = readTables();

        assertThat(findUnregisteredTables(tables, ModuleTableOwnership.OWNER_BY_TABLE))
                .as("新增資料表時必須同步登記到 ModuleTableOwnership.OWNER_BY_TABLE（決定它屬於哪個模組），"
                  + "否則 FK 守衛與 SQL 守衛都不知道這張表。")
                .isEmpty();
        assertThat(ModuleTableOwnership.OWNER_BY_TABLE.keySet())
                .as("對照表的每一項都必須是實際存在的表；表被 DROP 後請同步移除條目")
                .allSatisfy(table -> assertThat(tables).contains(table));
    }

    @Test
    @DisplayName("防假陰性：全部 migration 都已執行，且讀得到表與模組內 FK（否則上面兩條是空轉的）")
    void scanMustActuallySeeSchemaAndForeignKeys() throws SQLException {
        long migrationFiles = countMigrationFiles();

        assertThat(migrateResult.migrationsExecuted)
                .as("Flyway 執行的 migration 數必須等於 classpath 上的 V*.sql 數；少了代表只驗了部分 schema")
                .isEqualTo((int) migrationFiles)
                .isGreaterThan(0);
        assertThat(readTables())
                .as("public schema 的表（含 Flyway 的 flyway_schema_history）")
                .contains("flyway_schema_history", "articles", "comments", "users");
        assertThat(readForeignKeys())
                .as("目前仍存在的模組內 FK；若讀到 0 筆，代表 FK 查詢失效，跨模組檢查恆綠")
                .extracting(ForeignKey::describe)
                .contains("article_categories -> articles", "comments -> comments", "article_tags -> tags");
    }

    @Test
    @DisplayName("反向驗證：植入 comments → articles 跨模組 FK 後守衛抓得到，還原後恢復乾淨")
    void guardDetectsInjectedCrossModuleForeignKey() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE comments ADD CONSTRAINT probe_fk "
                    + "FOREIGN KEY (article_uuid) REFERENCES articles(uuid)");
            try {
                assertThat(findCrossModuleForeignKeys(readForeignKeys(), ModuleTableOwnership.OWNER_BY_TABLE))
                        .as("守衛若抓不到已知違規，「正向掃描為空」就沒有意義")
                        .containsExactly("comments (comment) -> articles (article) [probe_fk]");
            } finally {
                statement.execute("ALTER TABLE comments DROP CONSTRAINT probe_fk");
            }
        }

        assertThat(findCrossModuleForeignKeys(readForeignKeys(), ModuleTableOwnership.OWNER_BY_TABLE))
                .as("探針 FK 已 DROP，不得殘留")
                .isEmpty();
    }

    @Test
    @DisplayName("反向驗證：FK 一端是對照表沒登記的表時，同樣被判為違規而非被忽略")
    void guardFlagsForeignKeyToUnregisteredTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE probe_unregistered (article_uuid UUID REFERENCES articles(uuid))");
            try {
                assertThat(findCrossModuleForeignKeys(readForeignKeys(), ModuleTableOwnership.OWNER_BY_TABLE))
                        .containsExactly("probe_unregistered (<unregistered>) -> articles (article) "
                                + "[probe_unregistered_article_uuid_fkey]");
            } finally {
                statement.execute("DROP TABLE probe_unregistered");
            }
        }
    }

    @Test
    @DisplayName("反向驗證：新表沒登記到對照表時守衛抓得到，DROP 後恢復乾淨")
    void guardDetectsUnregisteredTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE probe_unregistered (id INT)");
            try {
                assertThat(findUnregisteredTables(readTables(), ModuleTableOwnership.OWNER_BY_TABLE))
                        .as("守衛若抓不到未登記的新表，「對照表防過期」就是空話")
                        .containsExactly("probe_unregistered");
            } finally {
                statement.execute("DROP TABLE probe_unregistered");
            }
        }

        assertThat(findUnregisteredTables(readTables(), ModuleTableOwnership.OWNER_BY_TABLE)).isEmpty();
    }

    @Test
    @DisplayName("反向驗證：同模組的 FK 與 Flyway 自己的表不會被誤判")
    void guardDoesNotFlagSameModuleForeignKey() {
        Map<String, String> map = new HashMap<>(ModuleTableOwnership.OWNER_BY_TABLE);
        List<ForeignKey> sameModule = List.of(
                new ForeignKey("article_categories", "articles", "fk_a"),
                new ForeignKey("verification_tokens", "users", "fk_b"));

        assertThat(findCrossModuleForeignKeys(sameModule, map)).isEmpty();
        assertThat(findUnregisteredTables(List.of("flyway_schema_history", "articles"), map)).isEmpty();
    }

    // ─── 判定邏輯 ───

    /**
     * 一條外鍵。
     *
     * @param child      子表（持有 FK 欄位的表）
     * @param parent     父表（被參照的表）
     * @param constraint 約束名稱
     */
    private record ForeignKey(String child, String parent, String constraint) {

        String describe() {
            return child + " -> " + parent;
        }
    }

    /**
     * 找出子表與父表不屬於同一模組的 FK。任一端不在對照表內者，以 {@code <unregistered>} 視為不同模組而判違規。
     *
     * @param foreignKeys 資料庫中的 FK
     * @param ownership   表 → 模組對照表
     * @return 違規描述 {@code 子表 (模組) -> 父表 (模組) [約束名]}
     */
    private static List<String> findCrossModuleForeignKeys(List<ForeignKey> foreignKeys,
                                                           Map<String, String> ownership) {
        List<String> violations = new ArrayList<>();
        for (ForeignKey fk : foreignKeys) {
            String childModule = ownership.getOrDefault(fk.child(), "<unregistered>");
            String parentModule = ownership.getOrDefault(fk.parent(), "<unregistered>");
            if (!childModule.equals(parentModule) || "<unregistered>".equals(childModule)) {
                violations.add(fk.child() + " (" + childModule + ") -> " + fk.parent()
                        + " (" + parentModule + ") [" + fk.constraint() + "]");
            }
        }
        return violations;
    }

    /**
     * 找出資料庫中存在、卻沒登記到對照表的表（{@link ModuleTableOwnership#IGNORED_TABLES} 除外）。
     *
     * @param tables    資料庫中的表
     * @param ownership 表 → 模組對照表
     * @return 未登記的表名（排序）
     */
    private static List<String> findUnregisteredTables(List<String> tables, Map<String, String> ownership) {
        return tables.stream()
                .filter(t -> !ModuleTableOwnership.IGNORED_TABLES.contains(t))
                .filter(t -> !ownership.containsKey(t))
                .sorted()
                .toList();
    }

    // ─── 資料庫讀取 ───

    private static List<ForeignKey> readForeignKeys() throws SQLException {
        List<ForeignKey> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(FOREIGN_KEYS_SQL)) {
            while (rs.next()) {
                result.add(new ForeignKey(rs.getString("child_table"), rs.getString("parent_table"),
                        rs.getString("constraint_name")));
            }
        }
        return result;
    }

    private static List<String> readTables() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(TABLES_SQL)) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return new ArrayList<>(new TreeSet<>(result));
    }

    private static long countMigrationFiles() {
        try {
            return java.util.Arrays.stream(
                            new PathMatchingResourcePatternResolver().getResources(MIGRATION_LOCATION + "/V*__*.sql"))
                    .count();
        } catch (IOException e) {
            throw new UncheckedIOException("無法列出 migration 檔", e);
        }
    }
}
