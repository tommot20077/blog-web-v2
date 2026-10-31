package dowob.xyz.blog.module.reading.job;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.reading.mapper.ReadingProgressMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
 * <p>flush 的順序是「讀 hash（記下 updatedAt）→ upsert → Lua compare-and-remove」（RACE-13／DATA-08，at-least-once）：
 * 失敗時不動 dirty set（成員本來就還在），不需要補回；整輪文章存在性只用一次
 * {@code filterExistingUuids} 批次檢查（PERF-31）。真實 Redis 下的交錯與 Lua 行為見 {@code ReadingProgressFlushJobIT}。</p>
 *
 * @author Yuan
 * @version 4.0
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

    private SimpleMeterRegistry meters;

    @AfterEach
    void removeMeterRegistry() {
        Metrics.removeRegistry(meters);
    }

    private final UUID userUuid = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        lenient().when(redisTemplate.opsForHash()).thenReturn((HashOperations) hashOps);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
        // 預設 DB upsert 成功（影響列數 1）；mock 的 int 預設 0 會被當成「被單調守衛擋下」
        lenient().when(progressMapper.upsert(any(), any(), any(), any(), any())).thenReturn(1);
        meters = new SimpleMeterRegistry();
        Metrics.addRegistry(meters);
        lenient().when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
    }

    private static Map<Object, Object> hashOf(String progress, String lastHeading, String updatedAt) {
        Map<Object, Object> hash = new HashMap<>();
        hash.put("progress", progress);
        if (lastHeading != null) {
            hash.put("lastHeading", lastHeading);
        }
        if (updatedAt != null) {
            hash.put("updatedAt", updatedAt);
        }
        return hash;
    }

    /** 與 flush 相同的轉換：epoch ms → 系統時區的 LocalDateTime。 */
    private static LocalDateTime at(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    private String keyOf(String entry) {
        return RedisKeyConstant.READING_PROGRESS_PREFIX + entry;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void verifyCompareAndRemove(String entry, String progress, String lastHeading, String updatedAt) {
        verify(redisTemplate).execute(any(RedisScript.class),
                eq(List.of(keyOf(entry), RedisKeyConstant.READING_DIRTY_KEY)),
                eq(progress), eq(lastHeading), eq(updatedAt), eq(entry));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void verifyNoCompareAndRemove() {
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("flush：順序為 讀 hash → upsert → compare-and-remove（以讀到的 progress、lastHeading、updatedAt 為期望值），不使用無條件 SREM")
    void flush_dirtyEntry_readsThenUpsertsThenCompareAndRemoves() {
        UUID articleUuid = UUID.randomUUID();
        String entry = userUuid + ":" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(articleUuid));
        when(hashOps.entries(keyOf(entry))).thenReturn(hashOf("0.6", "intro", "1730438400000"));

        job.flush();

        InOrder order = inOrder(hashOps, progressMapper, redisTemplate);
        order.verify(hashOps).entries(keyOf(entry));
        order.verify(progressMapper).upsert(userUuid, articleUuid, new BigDecimal("0.6"), "intro", at(1730438400000L));
        order.verify(redisTemplate).execute(any(RedisScript.class),
                eq(List.of(keyOf(entry), RedisKeyConstant.READING_DIRTY_KEY)),
                eq("0.6"), eq("intro"), eq("1730438400000"), eq(entry));
        verify(setOps, never()).remove(anyString(), any(Object[].class));
        verify(setOps, never()).add(anyString(), any(String[].class));
    }

    @Test
    @DisplayName("flush：lastHeading 為空字串時以 null upsert")
    void flush_emptyLastHeading_upsertsNull() {
        UUID articleUuid = UUID.randomUUID();
        String entry = userUuid + ":" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(articleUuid));
        when(hashOps.entries(keyOf(entry))).thenReturn(hashOf("0.6", "", "1"));

        job.flush();

        verify(progressMapper).upsert(userUuid, articleUuid, new BigDecimal("0.6"), null, at(1L));
    }

    @Test
    @DisplayName("flush：hash 缺少 lastHeading 時，期望值為空字串")
    void flush_hashWithoutLastHeading_usesEmptyExpectedValue() {
        UUID articleUuid = UUID.randomUUID();
        String entry = userUuid + ":" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(articleUuid));
        when(hashOps.entries(keyOf(entry))).thenReturn(hashOf("0.6", null, "5"));

        job.flush();

        verify(progressMapper).upsert(userUuid, articleUuid, new BigDecimal("0.6"), null, at(5L));
        verifyCompareAndRemove(entry, "0.6", "", "5");
    }

    @Test
    @DisplayName("flush：dirty 沒有成員 → 什麼都不做，也不查文章存在性")
    void flush_emptyDirtySet_doesNothing() {
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of());

        job.flush();

        verifyNoInteractions(articleFacade, progressMapper, hashOps);
    }

    @Test
    @DisplayName("flush：hash 已過期 → 不 upsert，三個欄位都以空字串為期望值做 compare-and-remove（僅在 hash 仍不存在時才移除）")
    void flush_redisKeyExpiredButDirtyExists_compareAndRemovesWithEmptyExpectation() {
        UUID articleUuid = UUID.randomUUID();
        String entry = userUuid + ":" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(articleUuid));
        when(hashOps.entries(keyOf(entry))).thenReturn(Map.of());

        job.flush();

        verify(progressMapper, never()).upsert(any(), any(), any(), any(), any());
        verifyCompareAndRemove(entry, "", "", "");
        verify(setOps, never()).remove(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("flush：upsert 失敗 → 不做 compare-and-remove、不 SREM、不 SADD（成員本來就還在，下輪重試），hash 保留")
    void flush_upsertFails_leavesDirtySetUntouched() {
        UUID articleUuid = UUID.randomUUID();
        String entry = userUuid + ":" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(articleUuid));
        when(hashOps.entries(keyOf(entry))).thenReturn(hashOf("0.6", null, "1"));
        when(progressMapper.upsert(any(), any(), any(), any(), any())).thenThrow(new RuntimeException("DB error"));

        job.flush();

        verifyNoCompareAndRemove();
        verify(setOps, never()).remove(anyString(), any(Object[].class));
        verify(setOps, never()).add(anyString(), any(String[].class));
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("flush：讀 hash 失敗 → 不 upsert、不動 dirty set")
    void flush_redisReadError_leavesDirtySetUntouched() {
        UUID articleUuid = UUID.randomUUID();
        String entry = userUuid + ":" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(articleUuid));
        when(hashOps.entries(keyOf(entry))).thenThrow(new RuntimeException("redis down"));

        job.flush();

        verify(progressMapper, never()).upsert(any(), any(), any(), any(), any());
        verifyNoCompareAndRemove();
        verify(setOps, never()).add(anyString(), any(String[].class));
    }

    @Test
    @DisplayName("flush：compare-and-remove 本身失敗 → 例外被吞掉、不 SADD，同一輪其他成員照常 flush")
    void flush_compareAndRemoveFails_doesNotAbortRound() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String firstEntry = userUuid + ":" + first;
        String secondEntry = userUuid + ":" + second;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY))
                .thenReturn(new LinkedHashSet<>(List.of(firstEntry, secondEntry)));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(first, second));
        when(hashOps.entries(keyOf(firstEntry))).thenReturn(hashOf("0.1", null, "1"));
        when(hashOps.entries(keyOf(secondEntry))).thenReturn(hashOf("0.2", null, "2"));
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(keyOf(firstEntry), RedisKeyConstant.READING_DIRTY_KEY)),
                anyString(), anyString(), anyString(), anyString())).thenThrow(new RuntimeException("redis down"));

        job.flush();

        verify(progressMapper).upsert(userUuid, second, new BigDecimal("0.2"), null, at(2L));
        verifyCompareAndRemove(secondEntry, "0.2", "", "2");
        verify(setOps, never()).add(anyString(), any(String[].class));
    }

    @Test
    @DisplayName("flush：單一成員 upsert 失敗不影響同一輪其他成員")
    void flush_oneEntryFails_otherEntriesStillFlushed() {
        UUID failing = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        String failingEntry = userUuid + ":" + failing;
        String healthyEntry = userUuid + ":" + healthy;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY))
                .thenReturn(new LinkedHashSet<>(List.of(failingEntry, healthyEntry)));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(failing, healthy));
        when(hashOps.entries(keyOf(failingEntry))).thenReturn(hashOf("0.1", null, "1"));
        when(hashOps.entries(keyOf(healthyEntry))).thenReturn(hashOf("0.2", null, "2"));
        when(progressMapper.upsert(eq(userUuid), eq(failing), any(), any(), any())).thenThrow(new RuntimeException("boom"));

        job.flush();

        verify(progressMapper).upsert(userUuid, healthy, new BigDecimal("0.2"), null, at(2L));
        verifyCompareAndRemove(healthyEntry, "0.2", "", "2");
        verify(redisTemplate, times(1)).execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("flush：文章已被刪除 → 先 DEL hash 再 SREM、不 upsert（FK 已移除，不得寫出孤兒列）")
    void flush_articleAlreadyDeleted_deletesKeyThenRemovesDirtyWithoutUpsert() {
        UUID articleUuid = UUID.randomUUID();
        String entry = userUuid + ":" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of());

        job.flush();

        InOrder order = inOrder(redisTemplate, setOps);
        order.verify(redisTemplate).delete(keyOf(entry));
        order.verify(setOps).remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
        verify(progressMapper, never()).upsert(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("flush：文章已刪除但 DEL hash 拋例外 → 不 SREM（成員留在 dirty set，「下輪再試」名實相符），整輪不中斷")
    void flush_articleDeletedButDelFails_keepsMemberInDirtySet() {
        UUID gone = UUID.randomUUID();
        UUID alive = UUID.randomUUID();
        String goneEntry = userUuid + ":" + gone;
        String aliveEntry = userUuid + ":" + alive;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY))
                .thenReturn(new LinkedHashSet<>(List.of(goneEntry, aliveEntry)));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(alive));
        doThrow(new RuntimeException("redis down")).when(redisTemplate).delete(keyOf(goneEntry));
        when(hashOps.entries(keyOf(aliveEntry))).thenReturn(hashOf("0.5", null, "9"));

        job.flush();

        verify(setOps, never()).remove(RedisKeyConstant.READING_DIRTY_KEY, goneEntry);
        verify(progressMapper).upsert(userUuid, alive, new BigDecimal("0.5"), null, at(9L));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void verifyCompareAndDiscard(String entry, String progress, String lastHeading, String updatedAt) {
        verify(redisTemplate).execute(eq(ReadingProgressFlushJob.COMPARE_AND_DISCARD_SCRIPT),
                eq(List.of(keyOf(entry), RedisKeyConstant.READING_DIRTY_KEY)),
                eq(progress), eq(lastHeading), eq(updatedAt), eq(entry));
    }

    @Test
    @DisplayName("flush：hash 缺 progress（poison pill）→ 不 upsert、不拋例外，以讀到的內容做 compare-and-discard（移除成員並 DEL hash），同輪其他成員照常 flush")
    void flush_hashWithoutProgress_discardsBadDataAndFlushesOthers() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        String badEntry = userUuid + ":" + bad;
        String goodEntry = userUuid + ":" + good;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY))
                .thenReturn(new LinkedHashSet<>(List.of(badEntry, goodEntry)));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(bad, good));
        Map<Object, Object> badHash = new HashMap<>();
        badHash.put("lastHeading", "h");
        badHash.put("updatedAt", "7");
        when(hashOps.entries(keyOf(badEntry))).thenReturn(badHash);
        when(hashOps.entries(keyOf(goodEntry))).thenReturn(hashOf("0.2", null, "2"));

        job.flush();

        verify(progressMapper, never()).upsert(eq(userUuid), eq(bad), any(), any(), any());
        verifyCompareAndDiscard(badEntry, "", "h", "7");
        verify(progressMapper).upsert(userUuid, good, new BigDecimal("0.2"), null, at(2L));
        verifyCompareAndRemove(goodEntry, "0.2", "", "2");
    }

    @Test
    @DisplayName("flush：hash 缺 updatedAt → 視為壞資料，不 upsert，compare-and-discard")
    void flush_hashWithoutUpdatedAt_discardsBadData() {
        UUID articleUuid = UUID.randomUUID();
        String entry = userUuid + ":" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(articleUuid));
        when(hashOps.entries(keyOf(entry))).thenReturn(hashOf("0.6", "intro", null));

        job.flush();

        verify(progressMapper, never()).upsert(any(), any(), any(), any(), any());
        verifyCompareAndDiscard(entry, "0.6", "intro", "");
        verify(redisTemplate, never()).execute(eq(ReadingProgressFlushJob.COMPARE_AND_REMOVE_SCRIPT), anyList(),
                any(Object[].class));
    }

    @Test
    @DisplayName("flush：updatedAt 無法解析 → 視為壞資料，不 upsert，以原始字串 compare-and-discard")
    void flush_unparsableUpdatedAt_discardsBadData() {
        UUID articleUuid = UUID.randomUUID();
        String entry = userUuid + ":" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(articleUuid));
        when(hashOps.entries(keyOf(entry))).thenReturn(hashOf("0.6", null, "not-a-number"));

        job.flush();

        verify(progressMapper, never()).upsert(any(), any(), any(), any(), any());
        verifyCompareAndDiscard(entry, "0.6", "", "not-a-number");
    }

    @Test
    @DisplayName("flush：progress 無法解析 → 同樣視為壞資料（避免每輪例外、永不移除）")
    void flush_unparsableProgress_discardsBadData() {
        UUID articleUuid = UUID.randomUUID();
        String entry = userUuid + ":" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(articleUuid));
        when(hashOps.entries(keyOf(entry))).thenReturn(hashOf("abc", null, "3"));

        job.flush();

        verify(progressMapper, never()).upsert(any(), any(), any(), any(), any());
        verifyCompareAndDiscard(entry, "abc", "", "3");
    }

    @Test
    @DisplayName("flush：upsert 回 0（DB 守衛判定 hash 比 DB 舊）→ 不 compare-and-remove，改 compare-and-discard（內容相同才移除成員並刪 hash），counter path=db_flush 加 1")
    void flush_upsertRejectedByDbGuard_discardsStaleHashAndCounts() {
        UUID articleUuid = UUID.randomUUID();
        String entry = userUuid + ":" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(articleUuid));
        when(hashOps.entries(keyOf(entry))).thenReturn(hashOf("0.3", "old", "100"));
        when(progressMapper.upsert(any(), any(), any(), any(), any())).thenReturn(0);

        job.flush();

        verifyCompareAndDiscard(entry, "0.3", "old", "100");
        verify(redisTemplate, never()).execute(eq(ReadingProgressFlushJob.COMPARE_AND_REMOVE_SCRIPT), anyList(),
                any(Object[].class));
        Counter c = meters.find("reading.progress.write_rejected").tag("path", "db_flush").counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("PERF-31：整輪的文章存在性只呼叫一次 filterExistingUuids（去重後的全部 article uuid），不逐筆查")
    void flush_manyEntries_checksExistenceInOneBatch() {
        UUID shared = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        Set<String> entries = new LinkedHashSet<>(List.of(
                userUuid + ":" + shared, otherUser + ":" + shared, userUuid + ":" + second, userUuid + ":" + third));
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(entries);
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(shared, second, third));
        lenient().when(hashOps.entries(anyString())).thenReturn(hashOf("0.5", null, "1"));

        job.flush();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(articleFacade, times(1)).filterExistingUuids(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(shared, second, third);
        verify(articleFacade, never()).existsByUuid(any());
        verify(progressMapper, times(4)).upsert(any(), any(), any(), any(), any());
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
                .thenReturn(new LinkedHashSet<>(List.of(legacy, garbage, badArticle, valid)));
        when(articleFacade.filterExistingUuids(any())).thenReturn(Set.of(articleUuid));
        when(hashOps.entries(keyOf(valid))).thenReturn(hashOf("0.5", null, "1"));

        job.flush();

        verify(setOps).remove(RedisKeyConstant.READING_DIRTY_KEY, legacy);
        verify(setOps).remove(RedisKeyConstant.READING_DIRTY_KEY, garbage);
        verify(setOps).remove(RedisKeyConstant.READING_DIRTY_KEY, badArticle);
        verify(setOps, never()).add(anyString(), any(String[].class));
        verify(hashOps, never()).entries(keyOf(legacy));
        verify(progressMapper, times(1)).upsert(any(), any(), any(), any(), any());
        verify(progressMapper).upsert(userUuid, articleUuid, new BigDecimal("0.5"), null, at(1L));
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
        verifyNoCompareAndRemove();
        verify(redisTemplate, never()).delete(anyString());
    }
}
