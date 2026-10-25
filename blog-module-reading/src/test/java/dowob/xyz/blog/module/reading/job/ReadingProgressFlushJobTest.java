package dowob.xyz.blog.module.reading.job;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.reading.mapper.ReadingProgressMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ReadingProgressFlushJob} 單元測試。
 *
 * <p>flush 的順序是「先 SREM 該成員，再讀 hash 與 upsert；任何失敗時 SADD 回去」（RACE-13／DATA-08），
 * 以及「整輪文章存在性只用一次 {@code filterExistingUuids} 批次檢查」（PERF-31）。
 * 真實 Redis 下的交錯情境見 {@code ReadingProgressFlushJobIT}。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReadingProgressFlushJob 單元測試")
class ReadingProgressFlushJobTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private SetOperations<String, String> setOps;
    @Mock private ArticleFacade articleFacade;
    @Mock private ReadingProgressMapper progressMapper;
    @InjectMocks private ReadingProgressFlushJob job;

    private final UUID userUuid = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        lenient().when(redisTemplate.opsForHash()).thenReturn((HashOperations) hashOps);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
        lenient().when(setOps.remove(eq(RedisKeyConstant.READING_DIRTY_KEY), anyString())).thenReturn(1L);
    }

    private static Map<Object, Object> hashOf(String progress, String lastHeading) {
        Map<Object, Object> hash = new HashMap<>();
        hash.put("progress", progress);
        if (lastHeading != null) {
            hash.put("lastHeading", lastHeading);
        }
        return hash;
    }

    private String keyOf(String entry) {
        return RedisKeyConstant.READING_PROGRESS_PREFIX + entry;
    }

    @Test
    @DisplayName("flush：dirty 成員先 SREM，再讀 hash 並以 uuid upsert（順序：remove → entries → upsert）")
    void flush_dirtyEntry_removesBeforeReadingHashThenUpserts() {
        UUID articleUuid = UUID.randomUUID();
        String entry = userUuid + ":" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(articleUuid));
        when(hashOps.entries(keyOf(entry))).thenReturn(hashOf("0.6", "intro"));

        job.flush();

        InOrder order = inOrder(setOps, hashOps, progressMapper);
        order.verify(setOps).remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
        order.verify(hashOps).entries(keyOf(entry));
        order.verify(progressMapper).upsert(userUuid, articleUuid, new BigDecimal("0.6"), "intro");
        verify(setOps, never()).add(anyString(), any(String[].class));
    }

    @Test
    @DisplayName("flush：lastHeading 為空字串時以 null upsert")
    void flush_emptyLastHeading_upsertsNull() {
        UUID articleUuid = UUID.randomUUID();
        String entry = userUuid + ":" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(articleUuid));
        when(hashOps.entries(keyOf(entry))).thenReturn(hashOf("0.6", ""));

        job.flush();

        verify(progressMapper).upsert(userUuid, articleUuid, new BigDecimal("0.6"), null);
    }

    @Test
    @DisplayName("flush：dirty 沒有成員 → 什麼都不做，也不查文章存在性")
    void flush_emptyDirtySet_doesNothing() {
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of());

        job.flush();

        verifyNoInteractions(articleFacade, progressMapper, hashOps);
    }

    @Test
    @DisplayName("flush：Redis hash 已過期但 dirty 還在 → 成員已被 SREM，不 upsert、不補回")
    void flush_redisKeyExpiredButDirtyExists_removesDirtyEntryWithoutUpsert() {
        UUID articleUuid = UUID.randomUUID();
        String entry = userUuid + ":" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(articleUuid));
        when(hashOps.entries(keyOf(entry))).thenReturn(Map.of());

        job.flush();

        verify(setOps).remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
        verify(progressMapper, never()).upsert(any(), any(), any(), any());
        verify(setOps, never()).add(anyString(), any(String[].class));
    }

    @Test
    @DisplayName("flush：upsert 失敗 → 成員 SADD 回 dirty set（下一輪重試），hash 保留")
    void flush_dbErrorOnSingleEntry_restoresItInDirtyForRetry() {
        UUID articleUuid = UUID.randomUUID();
        String entry = userUuid + ":" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(articleUuid));
        when(hashOps.entries(keyOf(entry))).thenReturn(hashOf("0.6", null));
        when(progressMapper.upsert(any(), any(), any(), any())).thenThrow(new RuntimeException("DB error"));

        job.flush();

        InOrder order = inOrder(setOps);
        order.verify(setOps).remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
        order.verify(setOps).add(RedisKeyConstant.READING_DIRTY_KEY, entry);
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("flush：讀 hash 失敗 → 成員 SADD 回 dirty set")
    void flush_redisReadError_restoresItInDirty() {
        UUID articleUuid = UUID.randomUUID();
        String entry = userUuid + ":" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(articleUuid));
        when(hashOps.entries(keyOf(entry))).thenThrow(new RuntimeException("redis down"));

        job.flush();

        verify(setOps).add(RedisKeyConstant.READING_DIRTY_KEY, entry);
        verify(progressMapper, never()).upsert(any(), any(), any(), any());
    }

    @Test
    @DisplayName("flush：單一成員失敗不影響同一輪其他成員")
    void flush_oneEntryFails_otherEntriesStillFlushed() {
        UUID failing = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        String failingEntry = userUuid + ":" + failing;
        String healthyEntry = userUuid + ":" + healthy;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY))
                .thenReturn(new LinkedHashSet<>(java.util.List.of(failingEntry, healthyEntry)));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(failing, healthy));
        when(hashOps.entries(keyOf(failingEntry))).thenReturn(hashOf("0.1", null));
        when(hashOps.entries(keyOf(healthyEntry))).thenReturn(hashOf("0.2", null));
        when(progressMapper.upsert(eq(userUuid), eq(failing), any(), any())).thenThrow(new RuntimeException("boom"));

        job.flush();

        verify(setOps).add(RedisKeyConstant.READING_DIRTY_KEY, failingEntry);
        verify(progressMapper).upsert(userUuid, healthy, new BigDecimal("0.2"), null);
        verify(setOps, never()).add(RedisKeyConstant.READING_DIRTY_KEY, healthyEntry);
    }

    @Test
    @DisplayName("flush：文章已被刪除 → 刪 hash、成員移出 dirty、不 upsert（FK 已移除，不得寫出孤兒列）")
    void flush_articleAlreadyDeleted_removesDirtyAndKeyWithoutUpsert() {
        UUID articleUuid = UUID.randomUUID();
        String entry = userUuid + ":" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of());

        job.flush();

        verify(redisTemplate).delete(keyOf(entry));
        verify(setOps).remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
        verify(progressMapper, never()).upsert(any(), any(), any(), any());
        verify(setOps, never()).add(anyString(), any(String[].class));
    }

    @Test
    @DisplayName("PERF-31：整輪的文章存在性只呼叫一次 filterExistingUuids（去重後的全部 article uuid），不逐筆查")
    void flush_manyEntries_checksExistenceInOneBatch() {
        UUID shared = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        Set<String> entries = new LinkedHashSet<>(java.util.List.of(
                userUuid + ":" + shared, otherUser + ":" + shared, userUuid + ":" + second, userUuid + ":" + third));
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(entries);
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(shared, second, third));
        lenient().when(hashOps.entries(anyString())).thenReturn(hashOf("0.5", null));

        job.flush();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Collection<UUID>> captor = org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(articleFacade, times(1)).filterExistingUuids(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(shared, second, third);
        verify(articleFacade, never()).existsByUuid(any());
        verify(articleFacade, never()).findIdByUuid(any());
        verify(progressMapper, times(4)).upsert(any(), any(), any(), any());
    }

    @Test
    @DisplayName("無法解析的成員（舊格式 {Long}:{uuid}、亂碼）→ 從 dirty set 移除、不 upsert、不重試；其他成員照常 flush")
    void flush_unparsableMembers_areRemovedAndNotRetried() {
        UUID articleUuid = UUID.randomUUID();
        String valid = userUuid + ":" + articleUuid;
        String legacy = "1:" + UUID.randomUUID();
        String garbage = "garbage";
        String badArticle = userUuid + ":not-a-uuid";
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY))
                .thenReturn(new LinkedHashSet<>(java.util.List.of(legacy, garbage, badArticle, valid)));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(articleUuid));
        when(hashOps.entries(keyOf(valid))).thenReturn(hashOf("0.5", null));

        job.flush();

        verify(setOps).remove(RedisKeyConstant.READING_DIRTY_KEY, legacy);
        verify(setOps).remove(RedisKeyConstant.READING_DIRTY_KEY, garbage);
        verify(setOps).remove(RedisKeyConstant.READING_DIRTY_KEY, badArticle);
        verify(setOps, never()).add(anyString(), any(String[].class));
        verify(hashOps, never()).entries(keyOf(legacy));
        verify(progressMapper, times(1)).upsert(any(), any(), any(), any());
        verify(progressMapper).upsert(userUuid, articleUuid, new BigDecimal("0.5"), null);
    }

    @Test
    @DisplayName("文章存在性檢查拋例外 → 整輪放棄，dirty set 一個成員都不動（下一輪重試）")
    void flush_existenceCheckThrows_leavesDirtySetUntouched() {
        UUID articleUuid = UUID.randomUUID();
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(userUuid + ":" + articleUuid));
        when(articleFacade.filterExistingUuids(any())).thenThrow(new IllegalStateException("article service down"));

        job.flush();

        verify(setOps, never()).remove(anyString(), any(Object[].class));
        verify(setOps, never()).add(anyString(), any(String[].class));
        verifyNoInteractions(progressMapper);
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("SREM 回傳 0（成員已被另一個 flush 執行者取走）→ 略過，不讀 hash、不 upsert")
    void flush_entryAlreadyTakenByAnotherFlusher_isSkipped() {
        UUID articleUuid = UUID.randomUUID();
        String entry = userUuid + ":" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(articleUuid));
        when(setOps.remove(RedisKeyConstant.READING_DIRTY_KEY, entry)).thenReturn(0L);

        job.flush();

        verify(hashOps, never()).entries(anyString());
        verify(progressMapper, never()).upsert(any(), any(), any(), any());
    }
}
