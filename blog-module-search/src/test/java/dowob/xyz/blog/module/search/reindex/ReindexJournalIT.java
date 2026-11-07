package dowob.xyz.blog.module.search.reindex;

import com.redis.testcontainers.RedisContainer;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ReindexJournal} 的真 Redis 整合測試（審查 M2：變動紀錄必須綁定作業身分）。
 *
 * <p>時序：作業 A 的鎖遺失 → 作業 B 執行 {@code begin} → A 的收尾若無條件刪除標記與集合，B 之後的增量就不再被記錄，
 * B 照常切換、這段期間的變動遺失。A 若還在取空集合，也會把屬於 B 的變動取走、寫進 A 自己（不會被切換）的索引。
 * 這裡以真 Redis 驗證：只有標記仍是自己 jobId 的作業才能續期、取出、結束。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ReindexJournal 整合測試（真 Redis）")
class ReindexJournalIT {

    private static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private final UUID jobA = UUID.randomUUID();
    private final UUID jobB = UUID.randomUUID();
    private ReindexJournal journal;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        redis.execute((RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
        journal = new ReindexJournal(redis);
    }

    @Test
    @DisplayName("沒有重建進行中時不記錄；begin 之後記錄，取出即移除")
    void record_onlyWhileActive() {
        journal.recordArticle("before-begin");
        assertThat(redis.hasKey(RedisKeyConstant.SEARCH_REINDEX_DIRTY_ARTICLES_KEY)).isFalse();

        journal.begin(jobA);
        journal.recordArticle("x");
        journal.recordAuthor(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));

        assertThat(journal.drainArticles(jobA, 200)).containsExactly("x");
        assertThat(journal.drainArticles(jobA, 200)).isEmpty();
        assertThat(journal.drainAuthors(jobA, 200)).containsExactly("00000000-0000-0000-0000-0000000000aa");
    }

    @Test
    @DisplayName("M2：A 的鎖遺失、B 已 begin 之後，A 的 end 不得刪掉 B 的標記與集合")
    void end_byStaleJob_keepsNewJobsJournal() {
        journal.begin(jobA);
        journal.begin(jobB);
        journal.recordArticle("recorded-for-b");

        journal.end(jobA);

        assertThat(redis.opsForValue().get(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY)).isEqualTo(jobB.toString());
        assertThat(redis.opsForSet().members(RedisKeyConstant.SEARCH_REINDEX_DIRTY_ARTICLES_KEY)).containsExactly("recorded-for-b");
        journal.end(jobB);
        assertThat(redis.hasKey(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY)).isFalse();
        assertThat(redis.hasKey(RedisKeyConstant.SEARCH_REINDEX_DIRTY_ARTICLES_KEY)).isFalse();
    }

    @Test
    @DisplayName("M2：標記已被 B 接手時 A 的 keepAlive 回 false（A 必須中止、不切換）；B 的 keepAlive 回 true 並續期")
    void keepAlive_byStaleJob_returnsFalse() {
        journal.begin(jobA);
        journal.begin(jobB);
        redis.expire(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY, java.time.Duration.ofSeconds(5));

        assertThat(journal.keepAlive(jobA)).isFalse();
        assertThat(journal.keepAlive(jobB)).isTrue();
        assertThat(redis.getExpire(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY)).isGreaterThan(5L);
    }

    @Test
    @DisplayName("M2：標記已到期（不存在）時 keepAlive 回 false")
    void keepAlive_afterExpiry_returnsFalse() {
        assertThat(journal.keepAlive(jobA)).isFalse();
    }

    @Test
    @DisplayName("M2：A 不得取走屬於 B 的變動——A 的取出失敗、集合原封不動，B 取得到")
    void drain_byStaleJob_failsWithoutPopping() {
        journal.begin(jobA);
        journal.begin(jobB);
        journal.recordArticle("recorded-for-b");

        assertThatThrownBy(() -> journal.drainArticles(jobA, 200)).isInstanceOf(RuntimeException.class);

        assertThat(journal.drainArticles(jobB, 200)).containsExactly("recorded-for-b");
    }

    @Test
    @DisplayName("取空期間也會續期（drain 同時延長標記 TTL）")
    void drain_extendsActiveTtl() {
        journal.begin(jobA);
        redis.expire(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY, java.time.Duration.ofSeconds(5));

        journal.drainArticles(jobA, 200);

        assertThat(redis.getExpire(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY)).isGreaterThan(5L);
    }
}
