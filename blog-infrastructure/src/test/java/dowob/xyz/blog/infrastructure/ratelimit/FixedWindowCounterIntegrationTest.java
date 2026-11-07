package dowob.xyz.blog.infrastructure.ratelimit;

import dowob.xyz.blog.infrastructure.ratelimit.FixedWindowCounter.Hit;
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

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FixedWindowCounter} 對真實 Redis 的整合測試（Lua 固定窗口語意）。
 *
 * <p>命名為 {@code *IntegrationTest}：blog-infrastructure 的 surefire 沒有 {@code *IT} includes。需要 Docker。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("FixedWindowCounter Redis 整合測試")
class FixedWindowCounterIntegrationTest {

    private static GenericContainer<?> redisContainer;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private FixedWindowCounter counter;
    private String key;

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
    void setUp() {
        counter = new FixedWindowCounter(redis);
        key = "ratelimit:test:u:" + UUID.randomUUID();
    }

    @Test
    @DisplayName("新 key：計數 1、key 帶 TTL、Retry-After 等於窗口秒數")
    void firstHit_setsTtl() {
        Hit hit = counter.hit(key, Duration.ofMinutes(1));

        assertThat(hit.count()).isEqualTo(1);
        assertThat(hit.retryAfterSeconds()).isBetween(59L, 60L);
        assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("窗口內持續遞增，且 TTL 不被重設（固定窗口）")
    void subsequentHits_incrementWithoutResettingTtl() throws Exception {
        counter.hit(key, Duration.ofSeconds(30));
        Thread.sleep(1100);
        Hit second = counter.hit(key, Duration.ofSeconds(30));

        assertThat(second.count()).isEqualTo(2);
        assertThat(second.retryAfterSeconds()).isLessThanOrEqualTo(29L);
    }

    @Test
    @DisplayName("既有 key 沒有 TTL（先前 EXPIRE 失敗）：下一次計數補上 TTL")
    void keyWithoutTtl_getsTtl() {
        redis.opsForValue().set(key, "5");
        assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isEqualTo(-1L);

        Hit hit = counter.hit(key, Duration.ofMinutes(2));

        assertThat(hit.count()).isEqualTo(6);
        assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 120L);
    }

    @Test
    @DisplayName("窗口過後計數恢復從 1 開始")
    void afterWindow_resets() throws Exception {
        counter.hit(key, Duration.ofSeconds(1));
        counter.hit(key, Duration.ofSeconds(1));

        Thread.sleep(1500);
        Hit hit = counter.hit(key, Duration.ofSeconds(1));

        assertThat(hit.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("不同 key 互不影響")
    void differentKeys_areIndependent() {
        counter.hit(key, Duration.ofMinutes(1));
        counter.hit(key, Duration.ofMinutes(1));

        assertThat(counter.hit(key + ":other", Duration.ofMinutes(1)).count()).isEqualTo(1);
    }
}
