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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ReadingProgressService} 單元測試（M2-c 起使用者與文章一律以 UUID 識別）。
 *
 * @author Yuan
 * @version 2.0
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

    @Test
    @DisplayName("update：進度 < 0.95 → 寫 Redis hash（key 為 reading:progress:{userUuid}:{articleUuid}）並加入 dirty set")
    void update_progressBelowThreshold_writesRedisAndAddsDirty() {
        when(articleFacade.existsByUuid(articleUuid)).thenReturn(true);

        service.update(userUuid, articleUuid, new BigDecimal("0.50"), "intro");

        verify(hashOps).putAll(eq(RedisKeyConstant.READING_PROGRESS_PREFIX + userUuid + ":" + articleUuid), any(Map.class));
        verify(setOps).add(RedisKeyConstant.READING_DIRTY_KEY, userUuid + ":" + articleUuid);
    }

    @Test
    void update_progressBelowThreshold_setsTTL() {
        when(articleFacade.existsByUuid(articleUuid)).thenReturn(true);

        service.update(userUuid, articleUuid, new BigDecimal("0.50"), null);

        verify(redisTemplate).expire(any(String.class),
                eq(RedisKeyConstant.READING_PROGRESS_TTL_DAYS),
                eq(TimeUnit.DAYS));
    }

    @Test
    @DisplayName("update：進度 >= 0.95 → 刪 Redis key 並以 uuid upsert DB")
    void update_progressAboveThreshold_deletesRedisAndUpsertsDb() {
        when(articleFacade.existsByUuid(articleUuid)).thenReturn(true);

        service.update(userUuid, articleUuid, new BigDecimal("0.98"), "end");

        verify(redisTemplate).delete(RedisKeyConstant.READING_PROGRESS_PREFIX + userUuid + ":" + articleUuid);
        verify(progressMapper).upsert(eq(userUuid), eq(articleUuid), eq(new BigDecimal("0.98")), eq("end"));
        verify(hashOps, never()).putAll(any(), any());
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
    void get_redisMiss_fallsBackToDbAndCachesBack() {
        when(hashOps.entries(any(String.class))).thenReturn(Map.of());
        UserReadingProgress dbVal = new UserReadingProgress();
        dbVal.setProgress(new BigDecimal("0.50"));
        dbVal.setLastHeadingAnchor("intro");
        dbVal.setUpdatedAt(LocalDateTime.now());
        when(articleFacade.existsByUuid(articleUuid)).thenReturn(true);
        when(progressRepo.findByUserUuidAndArticleUuid(userUuid, articleUuid))
                .thenReturn(Optional.of(dbVal));

        Optional<?> result = service.get(userUuid, articleUuid);

        assertThat(result).isPresent();
        verify(hashOps).putAll(any(String.class), any(Map.class));
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

    @Test
    void update_lastHeadingNull_storesEmptyString() {
        when(articleFacade.existsByUuid(articleUuid)).thenReturn(true);

        service.update(userUuid, articleUuid, new BigDecimal("0.50"), null);

        verify(hashOps).putAll(any(String.class),
                argThat((Map<?, ?> m) -> "".equals(m.get("lastHeading"))));
    }
}
