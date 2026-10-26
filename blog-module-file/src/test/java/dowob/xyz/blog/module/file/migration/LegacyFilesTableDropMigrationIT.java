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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V30 遷移整合測試：整張 DROP 舊的 {@code files} 表（V1 建立，已被 {@code file_metadata} 取代）。
 *
 * <h2>測試方式</h2>
 * <p>與 {@link LegacyMinioUrlMigrationIT} 同理，程式化操作 Flyway：先 migrate 到 V29（V30 之前的狀態）、
 * 塞入舊資料、再單獨跑 V30，重現正式環境升級的時序。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Testcontainers
@DisplayName("V30 DROP 舊 files 表")
class LegacyFilesTableDropMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    private static Connection connection;

    @BeforeAll
    static void openConnection() throws SQLException {
        connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /** 每個測試都從乾淨的 V29 狀態開始 */
    @BeforeEach
    void migrateToV29BeforeSeeding() {
        flywayUpTo("29").clean();
        flywayUpTo("29").migrate();
    }

    private Flyway flywayUpTo(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(MIGRATION_LOCATION)
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    /** 單獨跑到 V30——模擬正式環境「舊資料已存在，然後升級」的時序 */
    private void runV30() {
        flywayUpTo("30").migrate();
    }

    private boolean tableExists(String table) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) == 1;
            }
        }
    }

    private int indexCountLike(String pattern) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname LIKE ?")) {
            ps.setString(1, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void insertLegacyFile() throws SQLException {
        long userId;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users (uuid, email, password_hash, nickname, username, role, status) "
                        + "VALUES (?, 'legacy@test.local', 'x', 'nick', 'legacy', 'USER', 'ACTIVE') RETURNING id")) {
            ps.setObject(1, UUID.randomUUID());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                userId = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO files (uploader_id, original_name, storage_key, content_type, size_bytes, category) "
                        + "VALUES (?, 'old.png', 'legacy/old.png', 'image/png', 10, 'ATTACHMENT')")) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    private UUID insertFileMetadata() throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO file_metadata (id, original_name, storage_path, content_type, size, usage_type, uploader_id) "
                        + "VALUES (?, 'a.png', 'articles/a.png', 'image/png', 100, 'ARTICLE_CONTENT', ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, UUID.randomUUID());
            ps.executeUpdate();
        }
        return id;
    }

    @Test
    @DisplayName("舊 files 表（含資料）被整張 DROP，連帶移除 idx_files_* 索引")
    void migrate_dropsLegacyFilesTableAndItsIndexes() throws SQLException {
        insertLegacyFile();
        assertThat(tableExists("files")).as("前置條件：V29 時 files 表仍存在").isTrue();
        assertThat(indexCountLike("idx_files_%")).as("前置條件：V1 建立的兩個索引仍存在").isEqualTo(2);

        runV30();

        assertThat(tableExists("files")).as("V30 之後 files 表不存在").isFalse();
        assertThat(indexCountLike("idx_files_%")).as("索引隨表一併移除").isZero();
    }

    @Test
    @DisplayName("file_metadata 不受影響：資料保留、article_uuid 欄位與索引仍在")
    void migrate_leavesFileMetadataUntouched() throws SQLException {
        UUID fileId = insertFileMetadata();

        runV30();

        assertThat(tableExists("file_metadata")).isTrue();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM file_metadata WHERE id = ?")) {
            ps.setObject(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).as("file_metadata 資料保留").isEqualTo(1);
            }
        }
        assertThat(indexCountLike("idx_file_metadata_article_uuid")).as("V20 的索引仍在").isEqualTo(1);
    }
}
