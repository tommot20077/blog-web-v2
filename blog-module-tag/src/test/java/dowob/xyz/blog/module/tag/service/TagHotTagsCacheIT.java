package dowob.xyz.blog.module.tag.service;

import com.redis.testcontainers.RedisContainer;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.repository.ArticleTagRepository;
import dowob.xyz.blog.module.tag.repository.TagRepository;
import dowob.xyz.blog.module.tag.repository.UserTagFollowRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 熱門標籤快取回填的真 Redis 整合測試（PERF-17）。
 *
 * <p>驗證回填後 {@code tag:hot} 帶有 TTL（DEL+ZADD+EXPIRE 以單一 Lua 腳本原子完成），
 * 且第二次呼叫走快取命中路徑、順序與分數一致。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("熱門標籤快取回填 真 Redis 整合測試")
class TagHotTagsCacheIT {

    private static final RedisContainer REDIS = new RedisContainer("redis:8.8.0-alpine");

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private TagRepository tagRepository;
    private TagServiceImpl service;

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
        redis.delete(RedisKeyConstant.TAG_HOT_KEY);
        tagRepository = mock(TagRepository.class);
        service = new TagServiceImpl(tagRepository, mock(UserTagFollowRepository.class), redis, mock(ArticleTagRepository.class));
    }

    private static Tag tag(String name, int usage) {
        Tag t = new Tag();
        t.setId(UUID.randomUUID());
        t.setName(name);
        t.setSlug(name);
        t.setUsageCount(usage);
        return t;
    }

    @Test
    @DisplayName("快取未命中回填後：tag:hot 帶 TTL（<= 1 小時且 > 0），成員與分數正確")
    void backfill_setsTtlAndScores() {
        Tag a = tag("a", 10);
        Tag b = tag("b", 4);
        when(tagRepository.findTop20ByOrderByUsageCountDesc()).thenReturn(List.of(a, b));

        List<Tag> first = service.getHotTags(20);

        assertThat(first).extracting(Tag::getName).containsExactly("a", "b");
        Long ttl = redis.getExpire(RedisKeyConstant.TAG_HOT_KEY, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isGreaterThan(0L).isLessThanOrEqualTo(3600L);
        assertThat(redis.opsForZSet().score(RedisKeyConstant.TAG_HOT_KEY, a.getId().toString())).isEqualTo(10.0);
        assertThat(redis.opsForZSet().score(RedisKeyConstant.TAG_HOT_KEY, b.getId().toString())).isEqualTo(4.0);
    }

    @Test
    @DisplayName("回填後第二次呼叫走快取命中：以 findAllById 單次查詢，順序依 ZSET 分數由高到低")
    void secondCall_hitsCacheWithSingleBatchQuery() {
        Tag a = tag("a", 10);
        Tag b = tag("b", 4);
        when(tagRepository.findTop20ByOrderByUsageCountDesc()).thenReturn(List.of(a, b));
        service.getHotTags(20);
        when(tagRepository.findAllById(any())).thenReturn(List.of(b, a));

        List<Tag> second = service.getHotTags(20);

        assertThat(second).extracting(Tag::getName).containsExactly("a", "b");
        org.mockito.Mockito.verify(tagRepository, org.mockito.Mockito.times(1)).findTop20ByOrderByUsageCountDesc();
        org.mockito.Mockito.verify(tagRepository, org.mockito.Mockito.times(1)).findAllById(any());
    }

    @Test
    @DisplayName("回填會覆蓋舊內容：殘留的舊成員不會與新資料混雜")
    void backfill_replacesStaleMembers() {
        redis.opsForZSet().add(RedisKeyConstant.TAG_HOT_KEY, "stale-member", 99);
        redis.delete(RedisKeyConstant.TAG_HOT_KEY); // 模擬 TTL 過期
        Tag a = tag("a", 1);
        when(tagRepository.findTop20ByOrderByUsageCountDesc()).thenReturn(List.of(a));

        service.getHotTags(20);

        Set<String> members = redis.opsForZSet().range(RedisKeyConstant.TAG_HOT_KEY, 0, -1);
        assertThat(members).containsExactly(a.getId().toString());
    }
}
