package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.reading.mapper.ReadingProgressMapper;
import dowob.xyz.blog.module.reading.model.UserReadingProgress;
import dowob.xyz.blog.module.reading.repository.UserReadingProgressRepository;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadingProgressServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private SetOperations<String, String> setOps;
    @Mock private ArticleFacade articleFacade;
    @Mock private ReadingProgressMapper progressMapper;
    @Mock private UserReadingProgressRepository progressRepo;
    @InjectMocks private ReadingProgressService service;

    private final Long userId = 1L;
    private final UUID articleUuid = UUID.randomUUID();
    private final Long articleId = 100L;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        // 用 lenient() — 部分測試（如 unauth path）不會用到 Redis stubs
        org.mockito.Mockito.lenient().when(redisTemplate.opsForHash()).thenReturn((HashOperations) hashOps);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    @Test
    void update_progressBelowThreshold_writesRedisAndAddsDirty() {
        when(articleFacade.findIdByUuid(articleUuid)).thenReturn(articleId);

        service.update(userId, articleUuid, new BigDecimal("0.50"), "intro");

        verify(hashOps).putAll(eq(RedisKeyConstant.READING_PROGRESS_PREFIX + userId + ":" + articleUuid), any(Map.class));
        verify(setOps).add(RedisKeyConstant.READING_DIRTY_KEY, userId + ":" + articleUuid);
    }

    @Test
    void update_progressBelowThreshold_setsTTL() {
        when(articleFacade.findIdByUuid(articleUuid)).thenReturn(articleId);

        service.update(userId, articleUuid, new BigDecimal("0.50"), null);

        verify(redisTemplate).expire(any(String.class),
                eq(RedisKeyConstant.READING_PROGRESS_TTL_DAYS),
                eq(TimeUnit.DAYS));
    }

    @Test
    void update_progressAboveThreshold_deletesRedisAndUpsertsDb() {
        when(articleFacade.findIdByUuid(articleUuid)).thenReturn(articleId);

        service.update(userId, articleUuid, new BigDecimal("0.98"), "end");

        verify(redisTemplate).delete(RedisKeyConstant.READING_PROGRESS_PREFIX + userId + ":" + articleUuid);
        verify(progressMapper).upsert(eq(userId), eq(articleId), eq(new BigDecimal("0.98")), eq("end"));
        verify(hashOps, never()).putAll(any(), any());
    }

    @Test
    void get_redisHit_returnsFromRedis() {
        Map<Object, Object> hash = new HashMap<>();
        hash.put("progress", "0.612");
        hash.put("lastHeading", "intro");
        hash.put("updatedAt", "1730438400000");
        when(hashOps.entries(any(String.class))).thenReturn(hash);

        Optional<?> result = service.get(userId, articleUuid);

        assertThat(result).isPresent();
        verify(progressRepo, never()).findByUserIdAndArticleId(any(), any());
    }

    @Test
    void get_redisMiss_fallsBackToDbAndCachesBack() {
        when(hashOps.entries(any(String.class))).thenReturn(Map.of());
        UserReadingProgress dbVal = new UserReadingProgress();
        dbVal.setProgress(new BigDecimal("0.50"));
        dbVal.setLastHeadingAnchor("intro");
        dbVal.setUpdatedAt(LocalDateTime.now());
        when(articleFacade.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(progressRepo.findByUserIdAndArticleId(userId, articleId))
                .thenReturn(Optional.of(dbVal));

        Optional<?> result = service.get(userId, articleUuid);

        assertThat(result).isPresent();
        verify(hashOps).putAll(any(String.class), any(Map.class));
    }

    @Test
    void batchGetProgress_unauthenticated_returnsEmpty() {
        var result = service.batchGetProgress(null, java.util.List.of(1L, 2L));
        assertThat(result).isEmpty();
    }

    @Test
    void batchGetProgress_emptyArticleIds_returnsEmpty() {
        var result = service.batchGetProgress(userId, java.util.List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void update_lastHeadingNull_storesEmptyString() {
        when(articleFacade.findIdByUuid(articleUuid)).thenReturn(articleId);

        service.update(userId, articleUuid, new BigDecimal("0.50"), null);

        verify(hashOps).putAll(any(String.class),
                argThat((Map<?, ?> m) -> "".equals(m.get("lastHeading"))));
    }
}
