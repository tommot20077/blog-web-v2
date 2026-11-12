package dowob.xyz.blog.module.search.reindex;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.search.model.dto.response.SearchReindexJobResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReindexJobStore} 單元測試：作業狀態存在 Redis（多實例共享）、同時只能有一個重建（分散式鎖）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ReindexJobStore 單元測試")
class ReindexJobStoreTest {

    private static final UUID JOB = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final LocalDateTime STARTED = LocalDateTime.of(2026, 11, 7, 12, 0, 0);

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private HashOperations<String, Object, Object> hashOps;
    private ReindexJobStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        hashOps = mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForHash()).thenReturn(hashOps);
        store = new ReindexJobStore(redis);
    }

    @Test
    @DisplayName("tryLock：SET NX ＋ TTL，值為 jobId；已被持有時回 false（第二個觸發被擋）")
    void tryLock_setIfAbsentWithTtl() {
        when(valueOps.setIfAbsent(RedisKeyConstant.LOCK_SEARCH_REINDEX, JOB.toString(),
                ReindexJobStore.LOCK_TTL_SECONDS, TimeUnit.SECONDS)).thenReturn(true, false);

        assertThat(store.tryLock(JOB)).isTrue();
        assertThat(store.tryLock(JOB)).isFalse();
    }

    @Test
    @DisplayName("renewLock：Lua compare-and-expire，仍是自己的鎖才續期；回 0（鎖已過期或被別人取得）時為 false")
    void renewLock_onlyWhenStillOwner() {
        when(redis.execute(any(RedisScript.class), eq(List.of(RedisKeyConstant.LOCK_SEARCH_REINDEX)),
                eq(JOB.toString()), eq(String.valueOf(ReindexJobStore.LOCK_TTL_SECONDS)))).thenReturn(1L, 0L);

        assertThat(store.renewLock(JOB)).isTrue();
        assertThat(store.renewLock(JOB)).isFalse();
    }

    @Test
    @DisplayName("unlock：Lua compare-and-delete；Redis 故障只記 warn 不拋例外（鎖有 TTL 會自行到期）")
    void unlock_bestEffort() {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenThrow(new IllegalStateException("redis down"));

        assertThatCode(() -> store.unlock(JOB)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("start：先刪除上一個作業的狀態，再整份寫入新作業（null 欄位不寫）")
    @SuppressWarnings("unchecked")
    void start_replacesPreviousJob() {
        store.start(SearchReindexJobResponse.builder().jobId(JOB).state(ReindexJobState.RUNNING)
                .processed(0).startedAt(STARTED).build());

        var order = inOrder(redis, hashOps);
        order.verify(redis).delete(RedisKeyConstant.SEARCH_REINDEX_JOB_KEY);
        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        order.verify(hashOps).putAll(eq(RedisKeyConstant.SEARCH_REINDEX_JOB_KEY), fields.capture());
        assertThat(fields.getValue()).containsOnly(
                Map.entry("jobId", JOB.toString()), Map.entry("state", "RUNNING"),
                Map.entry("processed", "0"), Map.entry("startedAt", STARTED.toString()));
    }

    @Test
    @DisplayName("updateProgress／complete／fail：以 Lua 只在 jobId 相符時寫入，舊作業的遲到更新不會蓋掉新作業")
    void guardedUpdates_passJobIdAsGuard() {
        store.updateTotal(JOB, 42);
        store.updateProgress(JOB, 200);
        store.complete(JOB, 450, STARTED.plusMinutes(1));
        store.fail(JOB, "boom", STARTED.plusMinutes(2));

        verify(redis).execute(any(RedisScript.class), eq(List.of(RedisKeyConstant.SEARCH_REINDEX_JOB_KEY)),
                eq(JOB.toString()), eq("total"), eq("42"));
        verify(redis).execute(any(RedisScript.class), eq(List.of(RedisKeyConstant.SEARCH_REINDEX_JOB_KEY)),
                eq(JOB.toString()), eq("processed"), eq("200"));
        verify(redis).execute(any(RedisScript.class), eq(List.of(RedisKeyConstant.SEARCH_REINDEX_JOB_KEY)),
                eq(JOB.toString()), eq("state"), eq("COMPLETED"), eq("processed"), eq("450"),
                eq("finishedAt"), eq(STARTED.plusMinutes(1).toString()));
        verify(redis).execute(any(RedisScript.class), eq(List.of(RedisKeyConstant.SEARCH_REINDEX_JOB_KEY)),
                eq(JOB.toString()), eq("state"), eq("FAILED"), eq("error"), eq("boom"),
                eq("finishedAt"), eq(STARTED.plusMinutes(2).toString()));
    }

    private Map<Object, Object> storedJob(String state) {
        Map<Object, Object> raw = new HashMap<>();
        raw.put("jobId", JOB.toString());
        raw.put("state", state);
        raw.put("processed", "200");
        raw.put("total", "450");
        raw.put("startedAt", STARTED.toString());
        return raw;
    }

    @Test
    @DisplayName("current：沒有任何作業時回 empty")
    void current_noJob_empty() {
        when(hashOps.entries(RedisKeyConstant.SEARCH_REINDEX_JOB_KEY)).thenReturn(Map.of());

        assertThat(store.current()).isEmpty();
    }

    @Test
    @DisplayName("current：RUNNING 且鎖仍由該作業持有 → 照實回報 RUNNING 與進度")
    void current_runningWithLock_reportsRunning() {
        when(hashOps.entries(RedisKeyConstant.SEARCH_REINDEX_JOB_KEY)).thenReturn(storedJob("RUNNING"));
        when(valueOps.get(RedisKeyConstant.LOCK_SEARCH_REINDEX)).thenReturn(JOB.toString());

        SearchReindexJobResponse job = store.current().orElseThrow();

        assertThat(job.getJobId()).isEqualTo(JOB);
        assertThat(job.getState()).isEqualTo(ReindexJobState.RUNNING);
        assertThat(job.getProcessed()).isEqualTo(200);
        assertThat(job.getTotal()).isEqualTo(450L);
        assertThat(job.getStartedAt()).isEqualTo(STARTED);
        assertThat(job.getFinishedAt()).isNull();
        assertThat(job.getError()).isNull();
    }

    @Test
    @DisplayName("current：RUNNING 但鎖已不在（執行個體中途停止）→ 回報 FAILED 與中斷說明，不會永遠卡在 RUNNING")
    void current_runningWithoutLock_reportsInterrupted() {
        when(hashOps.entries(RedisKeyConstant.SEARCH_REINDEX_JOB_KEY)).thenReturn(storedJob("RUNNING"));
        when(valueOps.get(RedisKeyConstant.LOCK_SEARCH_REINDEX)).thenReturn(null);

        SearchReindexJobResponse job = store.current().orElseThrow();

        assertThat(job.getState()).isEqualTo(ReindexJobState.FAILED);
        assertThat(job.getError()).isEqualTo(ReindexJobStore.INTERRUPTED_ERROR).isNotBlank();
    }

    @Test
    @DisplayName("current：COMPLETED 帶結束時間；FAILED 帶錯誤摘要")
    void current_finishedStates() {
        Map<Object, Object> completed = storedJob("COMPLETED");
        completed.put("finishedAt", STARTED.plusMinutes(3).toString());
        when(hashOps.entries(RedisKeyConstant.SEARCH_REINDEX_JOB_KEY)).thenReturn(completed);

        SearchReindexJobResponse job = store.current().orElseThrow();
        assertThat(job.getState()).isEqualTo(ReindexJobState.COMPLETED);
        assertThat(job.getFinishedAt()).isEqualTo(STARTED.plusMinutes(3));

        Map<Object, Object> failed = storedJob("FAILED");
        failed.put("error", "IllegalStateException: boom");
        when(hashOps.entries(RedisKeyConstant.SEARCH_REINDEX_JOB_KEY)).thenReturn(failed);
        assertThat(store.current().orElseThrow().getError()).isEqualTo("IllegalStateException: boom");
    }

    @Test
    @DisplayName("m5：lockHolderJob：最近一次作業就是持鎖者 → 回傳它（含進度）")
    void lockHolderJob_latestIsHolder_returnsIt() {
        when(valueOps.get(RedisKeyConstant.LOCK_SEARCH_REINDEX)).thenReturn(JOB.toString());
        when(hashOps.entries(RedisKeyConstant.SEARCH_REINDEX_JOB_KEY)).thenReturn(storedJob("RUNNING"));

        SearchReindexJobResponse job = store.lockHolderJob().orElseThrow();

        assertThat(job.getJobId()).isEqualTo(JOB);
        assertThat(job.getProcessed()).isEqualTo(200);
    }

    @Test
    @DisplayName("m5：lockHolderJob：持鎖的新作業尚未寫入狀態（最近一次仍是舊作業）→ 回傳持鎖者的 jobId 與 RUNNING，不回舊作業")
    void lockHolderJob_statusNotYetWritten_returnsHolderId() {
        UUID holder = UUID.randomUUID();
        when(valueOps.get(RedisKeyConstant.LOCK_SEARCH_REINDEX)).thenReturn(holder.toString());
        when(hashOps.entries(RedisKeyConstant.SEARCH_REINDEX_JOB_KEY)).thenReturn(storedJob("COMPLETED"));

        SearchReindexJobResponse job = store.lockHolderJob().orElseThrow();

        assertThat(job.getJobId()).isEqualTo(holder);
        assertThat(job.getState()).isEqualTo(ReindexJobState.RUNNING);
    }

    @Test
    @DisplayName("m5：lockHolderJob：鎖已釋放 → empty")
    void lockHolderJob_noLock_empty() {
        when(valueOps.get(RedisKeyConstant.LOCK_SEARCH_REINDEX)).thenReturn(null);

        assertThat(store.lockHolderJob()).isEmpty();
    }

    @Test
    @DisplayName("current：狀態資料損毀（無法解析）→ 回 empty，不讓狀態端點 500")
    void current_corruptData_empty() {
        Map<Object, Object> corrupt = storedJob("RUNNING");
        corrupt.put("processed", "not-a-number");
        when(hashOps.entries(RedisKeyConstant.SEARCH_REINDEX_JOB_KEY)).thenReturn(corrupt);

        assertThat(store.current()).isEmpty();
    }
}
