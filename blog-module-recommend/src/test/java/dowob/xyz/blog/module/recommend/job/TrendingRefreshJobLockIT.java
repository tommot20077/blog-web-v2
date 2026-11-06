package dowob.xyz.blog.module.recommend.job;

import com.redis.testcontainers.RedisContainer;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleTrendingData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TrendingRefreshJob} 鎖與暫存 key 的真 Redis 整合測試（RACE-17）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("TrendingRefreshJob 鎖與 tmp key 真 Redis 整合測試")
class TrendingRefreshJobLockIT {

    private static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private ArticleFacade articleFacade;
    private TrendingRefreshJob job;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        factory.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        articleFacade = mock(ArticleFacade.class);
        job = new TrendingRefreshJob(articleFacade, redis);
    }

    private static ArticleTrendingData article() {
        return new ArticleTrendingData(UUID.randomUUID(), 10L, 1L, LocalDateTime.now().minusHours(2));
    }

    @Test
    @DisplayName("執行中鎖已過期並被他人取得：結束時只比對 token，不刪別人的鎖")
    void release_doesNotDeleteLockOwnedBySomeoneElse() {
        when(articleFacade.getArticlesPublishedAfter(any())).thenAnswer(inv -> {
            /* 模擬本執行的鎖過期、另一個實例取得新鎖 */
            redis.opsForValue().set(RedisKeyConstant.LOCK_TRENDING_REFRESH, "other-instance-token");
            return List.of(article());
        });

        job.refreshTrending();

        assertThat(redis.opsForValue().get(RedisKeyConstant.LOCK_TRENDING_REFRESH)).isEqualTo("other-instance-token");
    }

    @Test
    @DisplayName("正常執行：寫入正式 ZSet、釋放自己的鎖、不遺留任何 :tmp 殘留 key")
    void normalRun_releasesOwnLockAndLeavesNoTmpKeys() {
        when(articleFacade.getArticlesPublishedAfter(any())).thenReturn(List.of(article()));

        job.refreshTrending();

        assertThat(redis.hasKey(RedisKeyConstant.LOCK_TRENDING_REFRESH)).isFalse();
        assertThat(redis.opsForZSet().zCard(RedisKeyConstant.getTrendingKey("24h"))).isEqualTo(1L);
        Set<String> tmp = redis.keys("*:tmp*");
        assertThat(tmp).isEmpty();
    }

    @Test
    @DisplayName("鎖被他人持有時：本次跳過，且不動別人的鎖")
    void lockHeldByOther_skipsAndKeepsOthersLock() {
        redis.opsForValue().set(RedisKeyConstant.LOCK_TRENDING_REFRESH, "other-instance-token");

        job.refreshTrending();

        assertThat(redis.opsForValue().get(RedisKeyConstant.LOCK_TRENDING_REFRESH)).isEqualTo("other-instance-token");
        org.mockito.Mockito.verifyNoInteractions(articleFacade);
    }

    @Test
    @DisplayName("refreshPeriod：正式 key 沿用原行為（無 TTL），RENAME 不會把 tmp 的短 TTL 帶過去；不留 tmp key")
    void refreshPeriod_formalKeyHasNoTtlAfterRename() {
        when(articleFacade.getArticlesPublishedAfter(any())).thenReturn(List.of(article()));
        String key = RedisKeyConstant.getTrendingKey("7d");

        job.refreshPeriod("7d", 7L, 4L);

        assertThat(redis.getExpire(key)).as("-1 代表無 TTL").isEqualTo(-1L);
        assertThat(redis.opsForZSet().zCard(key)).isEqualTo(1L);
        assertThat(redis.keys("*:tmp*")).isEmpty();
    }

    @Test
    @DisplayName("refreshPeriod：舊的無 TTL 正式 key 被新資料取代後仍無 TTL")
    void refreshPeriod_replacesExistingKeyAndStillNoTtl() {
        String key = RedisKeyConstant.getTrendingKey("7d");
        redis.opsForZSet().add(key, "old-member", 1);
        when(articleFacade.getArticlesPublishedAfter(any())).thenReturn(List.of(article()));

        job.refreshPeriod("7d", 7L, 4L);

        assertThat(redis.getExpire(key)).isEqualTo(-1L);
        assertThat(redis.opsForZSet().score(key, "old-member")).isNull();
    }
}
