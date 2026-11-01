package dowob.xyz.blog.module.tag.job;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.tag.repository.TagRepository;
import dowob.xyz.blog.module.tag.service.TagUsageRecalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TagUsageReconciliationJob} 單元測試（DATA-01 每日全量校正）：
 * 以 keyset 分頁掃過所有標籤、每頁交給 {@link TagUsageRecalculator} 重算；Redis 鎖保證單實例執行。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("TagUsageReconciliationJob 單元測試")
class TagUsageReconciliationJobTest {

    private static final UUID NIL = new UUID(0L, 0L);
    private static final String LOCK_KEY = RedisKeyConstant.getReconciliationLockKey("tag", "tags.usage_count");

    private TagRepository tagRepository;
    private TagUsageRecalculator recalculator;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private TagUsageReconciliationJob job;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        tagRepository = mock(TagRepository.class);
        recalculator = mock(TagUsageRecalculator.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        job = new TagUsageReconciliationJob(tagRepository, recalculator, redis, 2);
    }

    @Test
    @DisplayName("run：以 keyset 游標分頁掃過所有標籤，每頁各重算一次，回報掃描數與修正數")
    void run_pagesThroughAllTagsAndRecalculatesEachPage() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(tagRepository.findIdsAfter(NIL, 2)).thenReturn(List.of(a, b));
        when(tagRepository.findIdsAfter(b, 2)).thenReturn(List.of(c));
        when(recalculator.recalculate(List.of(a, b))).thenReturn(1);
        when(recalculator.recalculate(List.of(c))).thenReturn(1);

        TagUsageReconciliationJob.Result result = job.run();

        assertThat(result.executed()).isTrue();
        assertThat(result.scanned()).isEqualTo(3);
        assertThat(result.corrected()).isEqualTo(2);
        assertThat(result.failedPages()).isZero();
        verify(recalculator).recalculate(List.of(a, b));
        verify(recalculator).recalculate(List.of(c));
    }

    @Test
    @DisplayName("run：一頁剛好滿 pageSize 時會再查下一頁，直到查到空頁")
    void run_fullPage_triggersNextPageUntilEmpty() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(tagRepository.findIdsAfter(NIL, 2)).thenReturn(List.of(a, b));
        when(tagRepository.findIdsAfter(b, 2)).thenReturn(List.of());

        TagUsageReconciliationJob.Result result = job.run();

        assertThat(result.scanned()).isEqualTo(2);
        verify(tagRepository).findIdsAfter(b, 2);
    }

    @Test
    @DisplayName("run：沒有任何標籤 → 不重算，回報 0")
    void run_noTags_doesNothing() {
        when(tagRepository.findIdsAfter(NIL, 2)).thenReturn(List.of());

        TagUsageReconciliationJob.Result result = job.run();

        assertThat(result.executed()).isTrue();
        assertThat(result.scanned()).isZero();
        verify(recalculator, never()).recalculate(anyCollection());
    }

    @Test
    @DisplayName("run：取不到分散式鎖（別的實例正在跑）→ 跳過，不查標籤也不重算")
    void run_lockNotAcquired_skips() {
        when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        TagUsageReconciliationJob.Result result = job.run();

        assertThat(result.executed()).isFalse();
        verify(tagRepository, never()).findIdsAfter(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(recalculator, never()).recalculate(anyCollection());
    }

    @Test
    @DisplayName("run：某一頁重算失敗 → 記為失敗頁、繼續處理後面的頁，不外拋")
    void run_pageFailure_isCountedAndRemainingPagesStillProcessed() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(tagRepository.findIdsAfter(NIL, 2)).thenReturn(List.of(a, b));
        when(tagRepository.findIdsAfter(b, 2)).thenReturn(List.of(c));
        when(recalculator.recalculate(List.of(a, b))).thenThrow(new RuntimeException("DB down"));
        when(recalculator.recalculate(List.of(c))).thenReturn(1);

        TagUsageReconciliationJob.Result result = job.run();

        assertThat(result.failedPages()).isEqualTo(1);
        assertThat(result.corrected()).isEqualTo(1);
        verify(recalculator).recalculate(List.of(c));
    }

    @Test
    @DisplayName("run：結束後（含失敗）以 token compare-and-delete 釋放鎖")
    void run_releasesLockAfterwards() {
        when(tagRepository.findIdsAfter(NIL, 2)).thenThrow(new RuntimeException("DB down"));

        try {
            job.run();
        } catch (RuntimeException ignored) {
            // 查詢標籤清單本身失敗可往外拋；此測試只關心鎖一定被釋放
        }

        verify(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                eq(List.of(LOCK_KEY)), anyString());
    }

    @Test
    @DisplayName("排程：每日固定 cron，且不與既有的孤兒校正排程時刻相同")
    void scheduledMethod_usesDailyCronDistinctFromOtherJobs() {
        List<Method> scheduled = Arrays.stream(TagUsageReconciliationJob.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class))
                .toList();

        assertThat(scheduled).hasSize(1);
        assertThat(scheduled.get(0).getAnnotation(Scheduled.class).cron()).isEqualTo(TagUsageReconciliationJob.CRON);
        assertThat(TagUsageReconciliationJob.CRON).isEqualTo("0 11 4 * * *");
        assertThat(TagUsageReconciliationJob.CRON).isNotEqualTo(ArticleTagOrphanReconciliationJob.CRON);
    }
}
