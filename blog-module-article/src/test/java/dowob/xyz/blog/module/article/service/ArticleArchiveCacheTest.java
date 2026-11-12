package dowob.xyz.blog.module.article.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.article.model.dto.response.ArticleArchiveResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleArchiveCache} 單元測試（PERF-04）。
 *
 * <p>快取只是優化：Redis 讀、寫、刪任何一步失敗都不得讓歸檔端點失敗，讀取失敗與壞資料一律視為未命中。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleArchiveCache 單元測試")
class ArticleArchiveCacheTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private ObjectMapper objectMapper;

    private ArticleArchiveCache cache;

    @BeforeEach
    void setUp() {
        // 與 Spring Boot 預設一致：java.time 以 ISO-8601 字串輸出
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        when(redis.opsForValue()).thenReturn(valueOps);
        cache = new ArticleArchiveCache(redis, objectMapper);
    }

    private static ArticleArchiveResponse row(String title) {
        return ArticleArchiveResponse.builder()
                .uuid(UUID.randomUUID())
                .title(title)
                .slug("slug-" + title)
                .publishedAt(LocalDateTime.of(2026, 5, 6, 7, 8, 9))
                .tags(List.of("Java", "Spring"))
                .build();
    }

    @Test
    @DisplayName("get：Redis 無此鍵 → 未命中（Optional.empty）")
    void get_missingKey_returnsEmpty() {
        when(valueOps.get(RedisKeyConstant.ARTICLE_ARCHIVE_KEY)).thenReturn(null);

        assertThat(cache.get()).isEmpty();
    }

    @Test
    @DisplayName("put 後 get：以固定鍵、TTL 10 分鐘寫入整份 JSON，讀回與原物件相等")
    void put_thenGet_roundTripsWithTtl() throws Exception {
        List<ArticleArchiveResponse> archive = List.of(row("A"), row("B"));

        cache.put(archive);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(RedisKeyConstant.ARTICLE_ARCHIVE_KEY), json.capture(),
                eq(Duration.ofMinutes(RedisKeyConstant.ARTICLE_ARCHIVE_TTL_MINUTES)));
        assertThat(RedisKeyConstant.ARTICLE_ARCHIVE_TTL_MINUTES).isEqualTo(10L);

        when(valueOps.get(RedisKeyConstant.ARTICLE_ARCHIVE_KEY)).thenReturn(json.getValue());
        Optional<List<ArticleArchiveResponse>> hit = cache.get();
        assertThat(hit).isPresent();
        assertThat(hit.get()).isEqualTo(archive);
    }

    @Test
    @DisplayName("put 空清單也要快取（空站台每次請求不應都打 DB），讀回為空清單而非未命中")
    void put_emptyList_isCachedAndReadBackAsEmptyList() {
        cache.put(List.of());

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(RedisKeyConstant.ARTICLE_ARCHIVE_KEY), json.capture(), any(Duration.class));
        when(valueOps.get(RedisKeyConstant.ARTICLE_ARCHIVE_KEY)).thenReturn(json.getValue());

        assertThat(cache.get()).contains(List.of());
    }

    @Test
    @DisplayName("get：快取內容不是合法 JSON → 視為未命中，不拋例外（由呼叫端重查 DB 並覆寫）")
    void get_corruptedJson_treatedAsMiss() {
        when(valueOps.get(RedisKeyConstant.ARTICLE_ARCHIVE_KEY)).thenReturn("{not json");

        assertThat(cache.get()).isEmpty();
    }

    @Test
    @DisplayName("get：Redis 例外 → 視為未命中，不拋例外")
    void get_redisThrows_treatedAsMiss() {
        when(valueOps.get(any())).thenThrow(new RuntimeException("redis down"));

        assertThat(cache.get()).isEmpty();
    }

    @Test
    @DisplayName("put：Redis 例外 → 吞掉，歸檔端點仍可回應")
    void put_redisThrows_isSwallowed() {
        doThrow(new RuntimeException("redis down")).when(valueOps).set(any(), any(), any(Duration.class));

        assertThatCode(() -> cache.put(List.of(row("A")))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("evict：刪除固定鍵")
    void evict_deletesTheKey() {
        cache.evict();

        verify(redis).delete(RedisKeyConstant.ARTICLE_ARCHIVE_KEY);
    }

    @Test
    @DisplayName("evict：Redis 例外 → 吞掉不外拋（已 commit 的寫入不能因失效失敗而回報失敗），陳舊上限由 TTL 保證")
    void evict_redisThrows_isSwallowed() {
        when(redis.delete(RedisKeyConstant.ARTICLE_ARCHIVE_KEY)).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> cache.evict()).doesNotThrowAnyException();
        verify(valueOps, never()).set(any(), any(), any(Duration.class));
    }
}
