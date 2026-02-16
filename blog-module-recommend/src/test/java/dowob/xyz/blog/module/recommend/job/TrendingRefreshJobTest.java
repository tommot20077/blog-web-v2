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
import org.springframework.data.redis.core.ZSetOperations;

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

    private TrendingRefreshJob job;

    @BeforeEach
    void setUp() {
        job = new TrendingRefreshJob(articleFacade, stringRedisTemplate);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
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
        @DisplayName("有文章時使用 tmp key 並 RENAME 原子切換")
        void whenArticlesExist_usesTmpKeyAndRenamesAtomically() {
            UUID uuid = UUID.randomUUID();
            ArticleTrendingData data = new ArticleTrendingData(
                    uuid, 100L, 10L, LocalDateTime.now().minusDays(1));

            when(articleFacade.getArticlesPublishedAfter(any())).thenReturn(List.of(data));

            job.refreshPeriod("7d", 7L, 4L);

            String tmpKey = "recommend:trending:7d:tmp";
            String key = "recommend:trending:7d";

            /** 應先清除 tmp key，寫入 tmp key，最後 rename 到正式 key */
            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(stringRedisTemplate, zSetOperations);
            inOrder.verify(stringRedisTemplate).delete(tmpKey);
            inOrder.verify(zSetOperations).add(eq(tmpKey), anySet());
            inOrder.verify(stringRedisTemplate).rename(tmpKey, key);
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
}
