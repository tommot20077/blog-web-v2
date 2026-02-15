package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link ViewCountServiceImpl} 單元測試
 *
 * <p>驗證瀏覽計數服務的核心邏輯，包含：
 * DB + Redis 合計取值、Redis 增量、批次刷入 DB。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ViewCountService 單元測試")
class ViewCountServiceTest {

    /** 文章 Mapper（Mock） */
    @Mock
    private ArticleMapper articleMapper;

    /** Redis 模板（Mock） */
    @Mock
    private StringRedisTemplate stringRedisTemplate;

    /** Value Operations（Mock） */
    @Mock
    private ValueOperations<String, String> valueOps;

    /** 待測服務 */
    @InjectMocks
    private ViewCountServiceImpl viewCountService;

    /**
     * 每個測試前注入 valueOps mock
     */
    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    /**
     * 情境一：DB 有資料、Redis 無資料 → 回傳 DB 值
     */
    @Test
    @DisplayName("getViewCount：DB 有值，Redis 無值 → 回傳 DB 計數")
    void getViewCount_dbOnlyNoRedis_returnsDbCount() {
        UUID uuid = UUID.randomUUID();
        when(articleMapper.findViewCountByUuid(uuid)).thenReturn(10L);
        when(valueOps.get("article:views:" + uuid)).thenReturn(null);

        long result = viewCountService.getViewCount(uuid);

        assertThat(result).isEqualTo(10L);
    }

    /**
     * 情境二：DB 有資料、Redis 有增量 → 回傳兩者合計
     */
    @Test
    @DisplayName("getViewCount：DB 有值，Redis 有值 → 回傳合計")
    void getViewCount_dbPlusRedis_returnsCombined() {
        UUID uuid = UUID.randomUUID();
        when(articleMapper.findViewCountByUuid(uuid)).thenReturn(10L);
        when(valueOps.get("article:views:" + uuid)).thenReturn("5");

        long result = viewCountService.getViewCount(uuid);

        assertThat(result).isEqualTo(15L);
    }

    /**
     * 情境三：DB 回傳 null（新文章尚無記錄）、Redis 有值 → null 視為 0
     */
    @Test
    @DisplayName("getViewCount：DB 回傳 null，Redis 有值 → null 視為 0")
    void getViewCount_nullDb_treatsAsZero() {
        UUID uuid = UUID.randomUUID();
        when(articleMapper.findViewCountByUuid(uuid)).thenReturn(null);
        when(valueOps.get("article:views:" + uuid)).thenReturn("3");

        long result = viewCountService.getViewCount(uuid);

        assertThat(result).isEqualTo(3L);
    }

    /**
     * 情境四：呼叫 incrementRedisViewCount → 應呼叫 Redis increment
     */
    @Test
    @DisplayName("incrementRedisViewCount：應呼叫 Redis increment 對應 key")
    void incrementRedisViewCount_callsRedisIncrement() {
        UUID uuid = UUID.randomUUID();

        viewCountService.incrementRedisViewCount(uuid);

        verify(valueOps).increment("article:views:" + uuid);
    }

    /**
     * 情境五：flushViewCounts 無任何 key → 不呼叫 DB
     */
    @Test
    @DisplayName("flushViewCounts：Redis 無 key → 不寫入 DB")
    void flushViewCounts_noKeys_doesNothing() {
        when(stringRedisTemplate.keys("article:views:*")).thenReturn(Set.of());

        viewCountService.flushViewCounts();

        verify(articleMapper, never()).incrementViewCountBatch(any(), anyLong());
    }

    /**
     * 情境六：flushViewCounts 有 key → 正確刷入 DB 並清除 Redis
     */
    @Test
    @DisplayName("flushViewCounts：Redis 有 key → 正確寫入 DB")
    void flushViewCounts_withKeys_flushesToDb() {
        UUID uuid1 = UUID.randomUUID();
        String key = "article:views:" + uuid1;
        when(stringRedisTemplate.keys("article:views:*")).thenReturn(Set.of(key));
        when(valueOps.getAndDelete(key)).thenReturn("3");

        viewCountService.flushViewCounts();

        verify(articleMapper).incrementViewCountBatch(uuid1, 3L);
    }
}
