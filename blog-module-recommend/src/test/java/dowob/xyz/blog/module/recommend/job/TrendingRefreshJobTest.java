package dowob.xyz.blog.module.recommend.job;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleTrendingData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.concurrent.TimeUnit;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TrendingRefreshJob 單元測試
 *
 * <p>驗證時間衰減分數計算邏輯與 Redis ZSet 更新行為。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrendingRefreshJobTest {

    @Mock
    private ArticleFacade articleFacade;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TrendingRefreshJob job;

    @BeforeEach
    void setUp() {
        job = new TrendingRefreshJob(articleFacade, stringRedisTemplate);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Nested
    class RefreshPeriod {

        @Test
        @DisplayName("無文章時不更新 Redis")
        void whenNoArticles_skipsRedisUpdate() {
            when(articleFacade.getArticlesPublishedAfter(any())).thenReturn(List.of());

            job.refreshPeriod("7d", 7L, 4L);

            verify(stringRedisTemplate, never()).delete(anyString());
            verify(zSetOperations, never()).add(anyString(), anySet());
        }

        @Test
        @DisplayName("有文章時寫入「每次執行唯一」的 tmp key 並 RENAME 原子切換到正式 key")
        void whenArticlesExist_usesTmpKeyAndRenamesAtomically() {
            UUID uuid = UUID.randomUUID();
            ArticleTrendingData data = new ArticleTrendingData(
                    uuid, 100L, 10L, LocalDateTime.now().minusDays(1));

            when(articleFacade.getArticlesPublishedAfter(any())).thenReturn(List.of(data));

            job.refreshPeriod("7d", 7L, 4L);

            String key = "recommend:trending:7d";
            ArgumentCaptor<String> tmpKey = ArgumentCaptor.forClass(String.class);

            /** 寫入 tmp key，最後 rename 到正式 key（tmp 名稱每次唯一，不再需要先 delete 舊的共用 tmp） */
            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(stringRedisTemplate, zSetOperations);
            inOrder.verify(zSetOperations).add(tmpKey.capture(), anySet());
            inOrder.verify(stringRedisTemplate).rename(tmpKey.getValue(), key);
            assertThat(tmpKey.getValue()).startsWith(key + ":tmp:");
        }

        @Test
        @DisplayName("RACE-17：兩次執行使用不同的 tmp key（不共用固定 :tmp，避免兩個執行互相覆蓋）")
        void twoRuns_useDifferentTmpKeys() {
            ArticleTrendingData data = new ArticleTrendingData(
                    UUID.randomUUID(), 100L, 10L, LocalDateTime.now().minusDays(1));
            when(articleFacade.getArticlesPublishedAfter(any())).thenReturn(List.of(data));

            job.refreshPeriod("7d", 7L, 4L);
            job.refreshPeriod("7d", 7L, 4L);

            ArgumentCaptor<String> tmpKeys = ArgumentCaptor.forClass(String.class);
            verify(zSetOperations, times(2)).add(tmpKeys.capture(), anySet());
            assertThat(tmpKeys.getAllValues()).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("RACE-17：執行中拋例外時 finally 清掉 tmp key，例外照常往外傳")
        void whenWriteFails_tmpKeyIsDeletedAndExceptionPropagates() {
            ArticleTrendingData data = new ArticleTrendingData(
                    UUID.randomUUID(), 100L, 10L, LocalDateTime.now().minusDays(1));
            when(articleFacade.getArticlesPublishedAfter(any())).thenReturn(List.of(data));
            when(zSetOperations.add(anyString(), anySet())).thenThrow(new IllegalStateException("redis down"));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> job.refreshPeriod("7d", 7L, 4L))
                    .isInstanceOf(IllegalStateException.class);

            /* 清除必須發生在寫入失敗「之後」（finally），而不是寫入前的預先清除 */
            ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(stringRedisTemplate, zSetOperations);
            inOrder.verify(zSetOperations).add(written.capture(), anySet());
            inOrder.verify(stringRedisTemplate).delete(written.getValue());
            verify(stringRedisTemplate, never()).rename(anyString(), anyString());
        }

        @Test
        @DisplayName("有文章時寫入 ZSet")
        void whenArticlesExist_writesToZSet() {
            UUID uuid = UUID.randomUUID();
            ArticleTrendingData data = new ArticleTrendingData(
                    uuid, 100L, 10L, LocalDateTime.now().minusDays(1));

            when(articleFacade.getArticlesPublishedAfter(any())).thenReturn(List.of(data));

            job.refreshPeriod("7d", 7L, 4L);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Set<ZSetOperations.TypedTuple<String>>> captor =
                    ArgumentCaptor.forClass(Set.class);
            verify(zSetOperations).add(anyString(), captor.capture());

            Set<ZSetOperations.TypedTuple<String>> tuples = captor.getValue();
            assertThat(tuples).hasSize(1);
            ZSetOperations.TypedTuple<String> tuple = tuples.iterator().next();
            assertThat(tuple.getValue()).isEqualTo(uuid.toString());
            assertThat(tuple.getScore()).isPositive();
        }

        @Test
        @DisplayName("較新文章分數高於較舊文章")
        void newerArticleHasHigherScoreThanOlder() {
            UUID newUuid = UUID.randomUUID();
            UUID oldUuid = UUID.randomUUID();

            ArticleTrendingData newArticle = new ArticleTrendingData(
                    newUuid, 100L, 10L, LocalDateTime.now().minusHours(1));
            ArticleTrendingData oldArticle = new ArticleTrendingData(
                    oldUuid, 100L, 10L, LocalDateTime.now().minusDays(6));

            when(articleFacade.getArticlesPublishedAfter(any()))
                    .thenReturn(List.of(newArticle, oldArticle));

            job.refreshPeriod("7d", 7L, 4L);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Set<ZSetOperations.TypedTuple<String>>> captor =
                    ArgumentCaptor.forClass(Set.class);
            verify(zSetOperations).add(anyString(), captor.capture());

            Set<ZSetOperations.TypedTuple<String>> tuples = captor.getValue();
            double newScore = tuples.stream()
                    .filter(t -> newUuid.toString().equals(t.getValue()))
                    .mapToDouble(ZSetOperations.TypedTuple::getScore)
                    .findFirst().orElseThrow();
            double oldScore = tuples.stream()
                    .filter(t -> oldUuid.toString().equals(t.getValue()))
                    .mapToDouble(ZSetOperations.TypedTuple::getScore)
                    .findFirst().orElseThrow();

            assertThat(newScore).isGreaterThan(oldScore);
        }

        @Test
        @DisplayName("按讚多的文章分數加成正確")
        void articleWithMoreLikesGetsHigherScore() {
            UUID highLikeUuid = UUID.randomUUID();
            UUID lowLikeUuid = UUID.randomUUID();

            LocalDateTime sameTime = LocalDateTime.now().minusHours(12);
            ArticleTrendingData highLike = new ArticleTrendingData(highLikeUuid, 100L, 50L, sameTime);
            ArticleTrendingData lowLike  = new ArticleTrendingData(lowLikeUuid, 100L, 5L, sameTime);

            when(articleFacade.getArticlesPublishedAfter(any()))
                    .thenReturn(List.of(highLike, lowLike));

            job.refreshPeriod("24h", 1L, 1L);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Set<ZSetOperations.TypedTuple<String>>> captor =
                    ArgumentCaptor.forClass(Set.class);
            verify(zSetOperations).add(anyString(), captor.capture());

            Set<ZSetOperations.TypedTuple<String>> tuples = captor.getValue();
            double highScore = tuples.stream()
                    .filter(t -> highLikeUuid.toString().equals(t.getValue()))
                    .mapToDouble(ZSetOperations.TypedTuple::getScore)
                    .findFirst().orElseThrow();
            double lowScore = tuples.stream()
                    .filter(t -> lowLikeUuid.toString().equals(t.getValue()))
                    .mapToDouble(ZSetOperations.TypedTuple::getScore)
                    .findFirst().orElseThrow();

            assertThat(highScore).isGreaterThan(lowScore);
        }
    }

    @Nested
    @DisplayName("refreshTrending 分散式鎖測試 (R-2)")
    class RefreshTrendingLockTests {

        @Test
        @DisplayName("未取得分散式鎖時跳過業務邏輯")
        void whenLockNotAcquired_skipsExecution() {
            when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                    .thenReturn(false);

            job.refreshTrending();

            verify(articleFacade, never()).getArticlesPublishedAfter(any());
        }

        @Test
        @DisplayName("取得分散式鎖後正常執行並在 finally 以 Lua script 原子釋放鎖")
        void whenLockAcquired_executesAndReleasesLockWithLuaScript() {
            when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                    .thenReturn(true);
            when(articleFacade.getArticlesPublishedAfter(any())).thenReturn(List.of());

            job.refreshTrending();

            verify(stringRedisTemplate).execute(any(RedisScript.class), anyList(), (Object) any());
        }

        @Test
        @DisplayName("RACE-17：鎖 TTL 至少 10 分鐘（遠大於三個週期計算的最壞耗時，避免執行中鎖過期）")
        void lockTtl_isAtLeastTenMinutes() {
            when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                    .thenReturn(true);
            when(articleFacade.getArticlesPublishedAfter(any())).thenReturn(List.of());

            job.refreshTrending();

            ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<TimeUnit> unit = ArgumentCaptor.forClass(TimeUnit.class);
            verify(valueOperations).setIfAbsent(anyString(), anyString(), ttl.capture(), unit.capture());
            assertThat(unit.getValue().toSeconds(ttl.getValue())).isGreaterThanOrEqualTo(600L);
        }

        @Test
        @DisplayName("RACE-17：釋放鎖時帶的 token 就是取得鎖時寫入的 token（只刪自己的鎖）")
        void release_usesSameTokenAsAcquire() {
            when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                    .thenReturn(true);
            when(articleFacade.getArticlesPublishedAfter(any())).thenReturn(List.of());

            job.refreshTrending();

            ArgumentCaptor<String> acquired = ArgumentCaptor.forClass(String.class);
            verify(valueOperations).setIfAbsent(anyString(), acquired.capture(), anyLong(), any(TimeUnit.class));
            verify(stringRedisTemplate).execute(any(RedisScript.class), anyList(), eq((Object) acquired.getValue()));
        }

        @Test
        @DisplayName("setIfAbsent 回傳 null 時當作未取得鎖，跳過業務邏輯")
        void whenLockReturnNull_skipsExecution() {
            when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                    .thenReturn(null);

            job.refreshTrending();

            verify(articleFacade, never()).getArticlesPublishedAfter(any());
        }
    }
}
