package dowob.xyz.blog.infrastructure.mq;

import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ConsumerExecutor#executeIdempotent} 對真實 Postgres 的整合測試。
 *
 * <p>驗證 Mockito 替身無法證明的語意：{@code processed_events} 的標記與業務寫入真的在同一個資料庫交易內
 * （業務失敗 → 標記一起消失、重送能真的重做）、並發同一 eventId 只執行一次。
 * 使用正式 Flyway migration 建表（含 V17 的 {@code processed_events}），業務列用測試專用表
 * {@code it_idem_business}。命名為 {@code *IntegrationTest} 是因為 blog-infrastructure 的 surefire
 * 沒有 {@code *IT} includes。需要 Docker。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ConsumerExecutor 冪等交易整合測試（真實 Postgres）")
class ConsumerExecutorIdempotencyIntegrationTest {

    /** 測試用 consumer 名稱 */
    private static final String CONSUMER = "it-idempotent-consumer";

    /** Postgres 容器 */
    private static PostgreSQLContainer<?> postgres;

    /** 資料來源（每次 getConnection 取得獨立實體連線，供並發測試使用） */
    private static DriverManagerDataSource dataSource;

    /** JDBC 範本 */
    private static JdbcTemplate jdbc;

    /** 被測物件（真實交易管理員＋真實 IdempotencyService） */
    private static ConsumerExecutor executor;

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
        dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS it_idem_business (id BIGSERIAL PRIMARY KEY, note VARCHAR(100) NOT NULL)");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        executor = new ConsumerExecutor(tx, proxyWithTransactions(new IdempotencyService(jdbc), tx));
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @BeforeEach
    void cleanUp() {
        jdbc.execute("TRUNCATE it_idem_business");
        jdbc.update("DELETE FROM processed_events WHERE consumer_name = ?", CONSUMER);
    }

    /**
     * 查詢指定事件在測試 consumer 下的標記列數。
     *
     * @param eventId 事件 ID
     * @return 標記列數
     */
    private int markCount(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ? AND consumer_name = ?",
                Integer.class, eventId, CONSUMER);
    }

    /**
     * 查詢業務表列數。
     *
     * @return 列數
     */
    private int businessCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM it_idem_business", Integer.class);
    }

    @Test
    @DisplayName("(a) body 成功：processed_events 有一列、業務列存在、ack")
    void bodySucceeds_marksOneRow() throws Exception {
        UUID eventId = UUID.randomUUID();

        executor.executeIdempotent(CONSUMER, null, eventId,
                () -> jdbc.update("INSERT INTO it_idem_business (note) VALUES ('ok')"));

        assertThat(markCount(eventId)).isEqualTo(1);
        assertThat(businessCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("(b) body 寫入業務列後拋例外：業務列與標記都不存在，例外往外拋")
    void bodyThrowsAfterWrite_rollsBackBusinessRowAndMark() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> executor.executeIdempotent(CONSUMER, null, eventId, () -> {
            jdbc.update("INSERT INTO it_idem_business (note) VALUES ('will-vanish')");
            throw new IllegalStateException("boom after write");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom after write");

        assertThat(businessCount()).isZero();
        assertThat(markCount(eventId)).isZero();
    }

    @Test
    @DisplayName("(c) (b) 之後以同一 eventId 再處理且成功：body 真的執行（不是被冪等跳過），最終業務列一筆、標記一列")
    void retryAfterRollback_runsBodyAgain() throws Exception {
        UUID eventId = UUID.randomUUID();
        assertThatThrownBy(() -> executor.executeIdempotent(CONSUMER, null, eventId, () -> {
            jdbc.update("INSERT INTO it_idem_business (note) VALUES ('first-attempt')");
            throw new IllegalStateException("boom after write");
        })).isInstanceOf(IllegalStateException.class);
        AtomicInteger runs = new AtomicInteger();

        executor.executeIdempotent(CONSUMER, null, eventId, () -> {
            runs.incrementAndGet();
            jdbc.update("INSERT INTO it_idem_business (note) VALUES ('second-attempt')");
        });

        assertThat(runs).hasValue(1);
        assertThat(businessCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT note FROM it_idem_business", String.class)).isEqualTo("second-attempt");
        assertThat(markCount(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("成功處理後同一 eventId 重送：body 不再執行，仍 ack（冪等跳過）")
    void redeliveryAfterSuccess_skipsBody() throws Exception {
        UUID eventId = UUID.randomUUID();
        executor.executeIdempotent(CONSUMER, null, eventId,
                () -> jdbc.update("INSERT INTO it_idem_business (note) VALUES ('once')"));
        AtomicInteger runs = new AtomicInteger();

        executor.executeIdempotent(CONSUMER, null, eventId, runs::incrementAndGet);

        assertThat(runs).hasValue(0);
        assertThat(businessCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("同一 eventId、不同 consumer 各自獨立冪等")
    void sameEventDifferentConsumers_areIndependent() throws Exception {
        UUID eventId = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        try {
            executor.executeIdempotent(CONSUMER, null, eventId, runs::incrementAndGet);
            executor.executeIdempotent(CONSUMER + "-other", null, eventId, runs::incrementAndGet);

            assertThat(runs).hasValue(2);
        } finally {
            jdbc.update("DELETE FROM processed_events WHERE consumer_name = ?", CONSUMER + "-other");
        }
    }

    @Test
    @DisplayName("(d) 兩條執行緒以同一 eventId 並發處理：body 恰好執行一次，業務列一筆、標記一列")
    void concurrentSameEventId_bodyRunsExactlyOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?>[] futures = new Future<?>[2];
            for (int i = 0; i < 2; i++) {
                long tag = 10L + i;
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    executor.executeIdempotent(CONSUMER, null, eventId, () -> {
                        runs.incrementAndGet();
                        jdbc.update("INSERT INTO it_idem_business (note) VALUES ('concurrent')");
                        Thread.sleep(300);
                    });
                    return null;
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(runs).hasValue(1);
        assertThat(businessCount()).isEqualTo(1);
        assertThat(markCount(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("並發同一 eventId 且先進入者失敗 rollback：後進入者插入標記成功並執行，不會兩者都跳過")
    void concurrentSameEventId_firstFails_secondRuns() throws Exception {
        UUID eventId = UUID.randomUUID();
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger secondRuns = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> {
                executor.executeIdempotent(CONSUMER, null, eventId, () -> {
                    firstInside.countDown();
                    secondStarted.await();
                    Thread.sleep(300);
                    throw new IllegalStateException("first fails");
                });
                return null;
            });
            assertThat(firstInside.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(() -> {
                secondStarted.countDown();
                executor.executeIdempotent(CONSUMER, null, eventId, () -> {
                    secondRuns.incrementAndGet();
                    jdbc.update("INSERT INTO it_idem_business (note) VALUES ('second')");
                });
                return null;
            });
            assertThatThrownBy(() -> first.get(30, TimeUnit.SECONDS))
                    .as("先進入者的失敗現在往外拋（交給容器重試）")
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasRootCauseMessage("first fails");
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(secondRuns).hasValue(1);
        assertThat(businessCount()).isEqualTo(1);
        assertThat(markCount(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("IdempotencyService.tryMark 在沒有既有交易時拒絕執行（MANDATORY），不會獨立提交標記")
    void tryMarkOutsideTransaction_isRejected() {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        IdempotencyService proxied = proxyWithTransactions(new IdempotencyService(jdbc), tx);
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> proxied.tryMark(eventId, CONSUMER))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(markCount(eventId)).isZero();
    }

    /**
     * 以 Spring 的 {@code @Transactional} 攔截器包裝 {@link IdempotencyService}，
     * 重現正式環境中 bean 被代理後的行為。
     *
     * @param target 原始服務
     * @param tx     提供交易管理員的範本
     * @return 已套用 {@code @Transactional} 的代理
     */
    private static IdempotencyService proxyWithTransactions(IdempotencyService target, TransactionTemplate tx) {
        org.springframework.aop.framework.ProxyFactory factory = new org.springframework.aop.framework.ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                tx.getTransactionManager(),
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (IdempotencyService) factory.getProxy();
    }
}
