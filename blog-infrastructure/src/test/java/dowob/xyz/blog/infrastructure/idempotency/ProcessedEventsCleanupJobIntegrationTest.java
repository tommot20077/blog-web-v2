package dowob.xyz.blog.infrastructure.idempotency;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProcessedEventsCleanupJob} 對真實 Postgres 的整合測試（PERF-19）。
 *
 * <p>使用正式 Flyway migration 建表。命名為 {@code *IntegrationTest} 是因為 blog-infrastructure 的
 * surefire 沒有 {@code *IT} includes。需要 Docker。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ProcessedEventsCleanupJob 整合測試（真實 Postgres）")
class ProcessedEventsCleanupJobIntegrationTest {

    /** 固定的「現在」，讓邊界測試不受時鐘影響 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 4, 37, 0);

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("blog_test").withUsername("test").withPassword("test");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @BeforeEach
    void cleanUp() {
        jdbc.execute("TRUNCATE processed_events");
    }

    private ProcessedEventsCleanupJob job(int retentionDays, int batchSize, int maxBatches) {
        return new ProcessedEventsCleanupJob(jdbc, tx, new IdempotencyProperties(retentionDays, batchSize, maxBatches));
    }

    private UUID seed(LocalDateTime processedAt) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO processed_events (event_id, consumer_name, processed_at) VALUES (?, 'it-cleanup', ?)",
                eventId, processedAt);
        return eventId;
    }

    private List<UUID> remaining() {
        return jdbc.queryForList("SELECT event_id FROM processed_events", UUID.class);
    }

    @Test
    @DisplayName("31 天前的列被刪、29 天前的列保留")
    void deletesOlderThanRetention_keepsNewer() {
        UUID old = seed(NOW.minusDays(31));
        UUID recent = seed(NOW.minusDays(29));

        ProcessedEventsCleanupJob.Result result = job(30, 5000, 200).run(NOW);

        assertThat(remaining()).containsExactly(recent).doesNotContain(old);
        assertThat(result.deleted()).isEqualTo(1);
    }

    @Test
    @DisplayName("邊界：processed_at 恰為 now-30 天 → 保留（嚴格小於才刪）；早 1 秒 → 刪除")
    void boundary_exactlyRetentionAgeIsKept_oneSecondOlderIsDeleted() {
        UUID exactly = seed(NOW.minusDays(30));
        UUID oneSecondOlder = seed(NOW.minusDays(30).minusSeconds(1));

        job(30, 5000, 200).run(NOW);

        assertThat(remaining()).containsExactly(exactly).doesNotContain(oneSecondOlder);
    }

    @Test
    @DisplayName("分批：批次大小 2、5 筆過期列 → 3 批全部刪完")
    void batching_batchSizeTwo_deletesAllFiveInThreeBatches() {
        for (int i = 0; i < 5; i++) {
            seed(NOW.minusDays(40).plusMinutes(i));
        }
        UUID recent = seed(NOW.minusDays(1));

        ProcessedEventsCleanupJob.Result result = job(30, 2, 200).run(NOW);

        assertThat(remaining()).containsExactly(recent);
        assertThat(result.deleted()).isEqualTo(5);
        assertThat(result.batches()).isEqualTo(3);
    }

    @Test
    @DisplayName("單次上限：max-batches=2、批次大小 2、5 筆過期列 → 只刪 4 筆，下次執行再清")
    void batching_respectsMaxBatchesPerRun() {
        for (int i = 0; i < 5; i++) {
            seed(NOW.minusDays(40).plusMinutes(i));
        }

        ProcessedEventsCleanupJob.Result first = job(30, 2, 2).run(NOW);
        assertThat(first.deleted()).isEqualTo(4);
        assertThat(remaining()).hasSize(1);

        job(30, 2, 2).run(NOW);
        assertThat(remaining()).isEmpty();
    }

    @Test
    @DisplayName("沒有過期列 → 不報錯、刪 0 筆；重複執行冪等")
    void nothingToDelete_isNoOp_andRepeatable() {
        UUID recent = seed(NOW.minusDays(1));

        ProcessedEventsCleanupJob.Result r1 = job(30, 5000, 200).run(NOW);
        ProcessedEventsCleanupJob.Result r2 = job(30, 5000, 200).run(NOW);

        assertThat(r1.deleted()).isZero();
        assertThat(r2.deleted()).isZero();
        assertThat(remaining()).containsExactly(recent);
    }

    @Test
    @DisplayName("兩個實例同時清理 → 不拋例外、總刪除數等於過期列數")
    void concurrentRuns_doNotFail() throws Exception {
        for (int i = 0; i < 50; i++) {
            seed(NOW.minusDays(40).plusSeconds(i));
        }
        seed(NOW.minusDays(1));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var f1 = pool.submit(() -> job(30, 5, 200).run(NOW));
            var f2 = pool.submit(() -> job(30, 5, 200).run(NOW));
            int total = f1.get(30, java.util.concurrent.TimeUnit.SECONDS).deleted()
                    + f2.get(30, java.util.concurrent.TimeUnit.SECONDS).deleted();
            assertThat(total).isEqualTo(50);
        } finally {
            pool.shutdownNow();
        }
        assertThat(remaining()).hasSize(1);
    }
}
