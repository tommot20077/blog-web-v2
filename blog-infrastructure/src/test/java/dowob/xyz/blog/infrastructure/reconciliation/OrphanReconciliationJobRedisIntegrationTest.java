package dowob.xyz.blog.infrastructure.reconciliation;

import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob.Result;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OrphanReconciliationJob} 對真實 Redis 的整合測試。
 *
 * <p>驗證 Mockito 替身無法證明的語意：Lua compare-and-delete 真的不會刪到他人的鎖、
 * 鎖被他人持有時真的跳過、游標真的跨次執行保存。命名為 {@code *IntegrationTest}
 * 是因為 blog-infrastructure 的 surefire 沒有 {@code *IT} includes。需要 Docker。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("OrphanReconciliationJob Redis 整合測試")
class OrphanReconciliationJobRedisIntegrationTest {

    private static final String LOCK_KEY = "lock:reconciliation:stub:stub_table";
    private static final String CURSOR_KEY = "reconciliation:cursor:stub:stub_table";

    private static GenericContainer<?> redisContainer;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void startRedis() {
        redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        redisContainer.start();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redisContainer.getHost(), redisContainer.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        redisContainer.stop();
    }

    @BeforeEach
    void flush() {
        redis.delete(List.of(LOCK_KEY, CURSOR_KEY));
    }

    private static UUID uuid(int n) {
        return new UUID(0L, n);
    }

    @Test
    @DisplayName("他人持有的鎖不會被解鎖刪除，且本次跳過不處理")
    void run_whenLockHeldByOther_skipsAndDoesNotDeleteTheirLock() {
        redis.opsForValue().set(LOCK_KEY, "someone-else");
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, new SimpleMeterRegistry());
        job.referenced.add(uuid(1));

        Result result = job.run();

        assertThat(result).isEqualTo(Result.SKIPPED);
        assertThat(job.removeCalls).isZero();
        assertThat(redis.opsForValue().get(LOCK_KEY)).isEqualTo("someone-else");
    }

    @Test
    @DisplayName("執行中鎖被他人取代（TTL 到期後被別的實例取得）時，解鎖不會刪掉他人的鎖")
    void run_whenLockTakenOverDuringRun_unlockLeavesOtherLockIntact() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, new SimpleMeterRegistry());
        job.referenced.add(uuid(1));
        job.beforeFetchHook = call -> redis.opsForValue().set(LOCK_KEY, "taken-over-by-other");

        job.run();

        assertThat(redis.opsForValue().get(LOCK_KEY)).isEqualTo("taken-over-by-other");
    }

    @Test
    @DisplayName("正常結束時釋放自己的鎖，鎖 key 帶有 TTL")
    void run_onNormalCompletion_releasesOwnLock() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, new SimpleMeterRegistry());
        job.referenced.add(uuid(1));
        job.beforeFetchHook = call -> {
            assertThat(redis.opsForValue().get(LOCK_KEY)).isNotBlank();
            assertThat(redis.getExpire(LOCK_KEY)).isPositive();
        };

        Result result = job.run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(redis.hasKey(LOCK_KEY)).isFalse();
    }

    @Test
    @DisplayName("頁數上限觸發的游標真的保存到 Redis，下次執行續跑並於掃完後清除")
    void run_cursorPersistsAcrossRunsAndClearedWhenDone() {
        StubOrphanReconciliationJob job = new StubOrphanReconciliationJob(redis, new SimpleMeterRegistry(), 2, 1);
        for (int i = 1; i <= 4; i++) {
            job.referenced.add(uuid(i));
        }
        job.existingInOwner.addAll(List.of(uuid(1), uuid(2), uuid(3)));

        assertThat(job.run()).isEqualTo(Result.PARTIAL);
        assertThat(redis.opsForValue().get(CURSOR_KEY)).isEqualTo(uuid(2).toString());

        assertThat(job.run()).isEqualTo(Result.PARTIAL); // 第 2 頁（3、4）滿頁且已達上限
        assertThat(job.removed).containsExactly(uuid(4));
        assertThat(redis.opsForValue().get(CURSOR_KEY)).isEqualTo(uuid(4).toString());

        assertThat(job.run()).isEqualTo(Result.COMPLETED); // 空頁 → 掃完
        assertThat(redis.hasKey(CURSOR_KEY)).isFalse();
    }
}
