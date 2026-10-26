package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.reading.mapper.ReadingProgressMapper;
import dowob.xyz.blog.module.reading.model.UserReadingProgress;
import dowob.xyz.blog.module.reading.repository.UserReadingProgressRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ReadingProgressService} 單元測試（M2-c 起使用者與文章一律以 UUID 識別）。
 *
 * @author Yuan
 * @version 3.0
 */
@ExtendWith(MockitoExtension.class)
class ReadingProgressServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private SetOperations<String, String> setOps;
    @Mock private ArticleFacade articleFacade;
    @Mock private ReadingProgressMapper progressMapper;
    @Mock private UserReadingProgressRepository progressRepo;
    @InjectMocks private ReadingProgressService service;

    private final UUID userUuid = UUID.randomUUID();
    private final UUID articleUuid = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        // 用 lenient() — 部分測試（如 unauth path）不會用到 Redis stubs
        org.mockito.Mockito.lenient().when(redisTemplate.opsForHash()).thenReturn((HashOperations) hashOps);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    private String keyOf() {
        return RedisKeyConstant.READING_PROGRESS_PREFIX + userUuid + ":" + articleUuid;
    }

    private static LocalDateTime at(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    @Test
    @DisplayName("update：進度 < 0.95 → 只呼叫一次 Redis（WRITE_IF_NEWER Lua，帶 ts、TTL 秒數、dirty 成員），不再有 putAll／expire／add 三個獨立指令")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void update_progressBelowThreshold_callsRedisExactlyOnceWithLua() {
        when(articleFacade.existsByUuid(articleUuid)).thenReturn(true);

        service.update(userUuid, articleUuid, new BigDecimal("0.50"), "intro", 1730438400000L);

        verify(redisTemplate).execute(any(RedisScript.class),
                eq(List.of(keyOf(), RedisKeyConstant.READING_DIRTY_KEY)),
                eq("0.50"), eq("intro"), eq("1730438400000"),
                eq(String.valueOf(TimeUnit.DAYS.toSeconds(RedisKeyConstant.READING_PROGRESS_TTL_DAYS))),
                eq(userUuid + ":" + articleUuid));
        verifyNoMoreInteractions(redisTemplate);
        verifyNoInteractions(hashOps, setOps, progressMapper);
    }

    @Test
    @DisplayName("update：四參數版本以收到請求當下的 System.currentTimeMillis() 作為 ts")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void update_withoutExplicitTs_usesCurrentTimeMillis() {
        when(articleFacade.existsByUuid(articleUuid)).thenReturn(true);
        long before = System.currentTimeMillis();

        service.update(userUuid, articleUuid, new BigDecimal("0.50"), null);

        long after = System.currentTimeMillis();
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), args.capture(), args.capture(),
                args.capture(), args.capture(), args.capture());
        long ts = Long.parseLong(args.getAllValues().get(2).toString());
        assertThat(ts).isBetween(before, after);
    }

    @Test
    @DisplayName("update：進度 >= 0.95 → 先以 ts 對應的 updatedAt 單調 upsert DB，再 WRITE_IF_NEWER 把快取設成完讀值（不帶 dirty 成員）；不再 DEL hash")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void update_progressAboveThreshold_upsertsDbThenWritesCacheWithoutDirtyAndWithoutDelete() {
        when(articleFacade.existsByUuid(articleUuid)).thenReturn(true);

        service.update(userUuid, articleUuid, new BigDecimal("0.98"), "end", 1730438400000L);

        InOrder order = inOrder(progressMapper, redisTemplate);
        order.verify(progressMapper).upsert(userUuid, articleUuid, new BigDecimal("0.98"), "end", at(1730438400000L));
        order.verify(redisTemplate).execute(any(RedisScript.class),
                eq(List.of(keyOf(), RedisKeyConstant.READING_DIRTY_KEY)),
                eq("0.98"), eq("end"), eq("1730438400000"),
                eq(String.valueOf(TimeUnit.DAYS.toSeconds(RedisKeyConstant.READING_PROGRESS_TTL_DAYS))),
                eq(""));
        verify(redisTemplate, never()).delete(anyString());
        verify(redisTemplate, never()).delete(anyCollection());
        verifyNoInteractions(hashOps, setOps);
    }

    @Test
    @DisplayName("update：lastHeading 為 null → 以空字串寫入 Lua 參數")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void update_lastHeadingNull_passesEmptyString() {
        when(articleFacade.existsByUuid(articleUuid)).thenReturn(true);

        service.update(userUuid, articleUuid, new BigDecimal("0.50"), null, 5L);

        verify(redisTemplate).execute(any(RedisScript.class), anyList(),
                eq("0.50"), eq(""), eq("5"), anyString(), anyString());
    }

    @Test
    @DisplayName("update：不得標註 @Transactional（只剩一條 DB 語句；Redis 寫入在 DB 之後，security.md 原則 10）")
    void update_isNotTransactional() throws Exception {
        for (Method m : ReadingProgressService.class.getDeclaredMethods()) {
            if (m.getName().equals("update")) {
                assertThat(m.isAnnotationPresent(Transactional.class))
                        .as("update 簽名 %s 不得有 @Transactional", m).isFalse();
            }
        }
        assertThat(ReadingProgressService.class.isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("update：文章不存在 → 靜默略過，不寫 Redis、不加 dirty、不 upsert（與改造前相同）")
    void update_articleNotFound_silentlySkips() {
        when(articleFacade.existsByUuid(articleUuid)).thenReturn(false);

        service.update(userUuid, articleUuid, new BigDecimal("0.50"), "intro");
        service.update(userUuid, articleUuid, new BigDecimal("0.99"), "end");

        verifyNoInteractions(redisTemplate, progressMapper);
    }

    @Test
    void get_redisHit_returnsFromRedis() {
        Map<Object, Object> hash = new HashMap<>();
        hash.put("progress", "0.612");
        hash.put("lastHeading", "intro");
        hash.put("updatedAt", "1730438400000");
        when(hashOps.entries(RedisKeyConstant.READING_PROGRESS_PREFIX + userUuid + ":" + articleUuid)).thenReturn(hash);

        Optional<?> result = service.get(userUuid, articleUuid);

        assertThat(result).isPresent();
        verify(progressRepo, never()).findByUserUuidAndArticleUuid(any(), any());
    }

    @Test
    @DisplayName("get：Redis 沒有 → 讀 DB 並以 WRITE_IF_NEWER 回填（ts 為 DB 列 updated_at 的 epoch ms、不帶 dirty 成員），不再無條件 HMSET")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void get_redisMiss_fallsBackToDbAndCachesBackMonotonically() {
        when(hashOps.entries(any(String.class))).thenReturn(Map.of());
        UserReadingProgress dbVal = new UserReadingProgress();
        dbVal.setProgress(new BigDecimal("0.50"));
        dbVal.setLastHeadingAnchor("intro");
        dbVal.setUpdatedAt(at(1730438400000L));
        when(articleFacade.existsByUuid(articleUuid)).thenReturn(true);
        when(progressRepo.findByUserUuidAndArticleUuid(userUuid, articleUuid))
                .thenReturn(Optional.of(dbVal));

        Optional<?> result = service.get(userUuid, articleUuid);

        assertThat(result).isPresent();
        verify(redisTemplate).execute(any(RedisScript.class),
                eq(List.of(keyOf(), RedisKeyConstant.READING_DIRTY_KEY)),
                eq("0.50"), eq("intro"), eq("1730438400000"),
                eq(String.valueOf(TimeUnit.DAYS.toSeconds(RedisKeyConstant.READING_PROGRESS_TTL_DAYS))),
                eq(""));
        verify(hashOps, never()).putAll(any(), any());
    }

    @Test
    @DisplayName("get：Redis 沒有且文章不存在 → empty，不查 DB（與改造前相同）")
    void get_redisMissAndArticleNotFound_returnsEmpty() {
        when(hashOps.entries(any(String.class))).thenReturn(Map.of());
        when(articleFacade.existsByUuid(articleUuid)).thenReturn(false);

        assertThat(service.get(userUuid, articleUuid)).isEmpty();
        verify(progressRepo, never()).findByUserUuidAndArticleUuid(any(), any());
    }

    @Test
    void batchGetProgress_unauthenticated_returnsEmpty() {
        var result = service.batchGetProgress(null, List.of(UUID.randomUUID(), UUID.randomUUID()));
        assertThat(result).isEmpty();
    }

    @Test
    void batchGetProgress_emptyArticleUuids_returnsEmpty() {
        var result = service.batchGetProgress(userUuid, List.of());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("batchGetProgress：Redis 命中的直接用，沒命中的只用一次 DB 查詢補齊；全程不呼叫 ArticleFacade")
    void batchGetProgress_mixesRedisAndDb_withoutCallingArticleFacade() {
        UUID cached = UUID.randomUUID();
        UUID inDb = UUID.randomUUID();
        UUID none = UUID.randomUUID();
        Map<Object, Object> hash = new HashMap<>();
        hash.put("progress", "0.30");
        when(hashOps.entries(RedisKeyConstant.READING_PROGRESS_PREFIX + userUuid + ":" + cached)).thenReturn(hash);
        when(hashOps.entries(RedisKeyConstant.READING_PROGRESS_PREFIX + userUuid + ":" + inDb)).thenReturn(Map.of());
        when(hashOps.entries(RedisKeyConstant.READING_PROGRESS_PREFIX + userUuid + ":" + none)).thenReturn(Map.of());
        UserReadingProgress row = new UserReadingProgress();
        row.setUserUuid(userUuid);
        row.setArticleUuid(inDb);
        row.setProgress(new BigDecimal("0.97"));
        when(progressRepo.findByUserUuidAndArticleUuidIn(eq(userUuid), argThat(l -> l.size() == 2
                && l.contains(inDb) && l.contains(none)))).thenReturn(List.of(row));

        Map<UUID, BigDecimal> result = service.batchGetProgress(userUuid, List.of(cached, inDb, none));

        assertThat(result).containsOnlyKeys(cached, inDb);
        assertThat(result.get(cached)).isEqualByComparingTo("0.30");
        assertThat(result.get(inDb)).isEqualByComparingTo("0.97");
        verifyNoInteractions(articleFacade);
    }
}
