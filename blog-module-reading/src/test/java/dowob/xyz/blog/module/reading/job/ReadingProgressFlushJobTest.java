package dowob.xyz.blog.module.reading.job;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.reading.mapper.ReadingProgressMapper;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadingProgressFlushJobTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private SetOperations<String, String> setOps;
    @Mock private ArticleFacade articleFacade;
    @Mock private ReadingProgressMapper progressMapper;
    @InjectMocks private ReadingProgressFlushJob job;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        org.mockito.Mockito.lenient().when(redisTemplate.opsForHash()).thenReturn((HashOperations) hashOps);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    @Test
    void flush_dirtyEntries_upsertedAndRemovedFromDirty() {
        UUID articleUuid = UUID.randomUUID();
        String entry = "1:" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));

        Map<Object, Object> hash = new HashMap<>();
        hash.put("progress", "0.6");
        hash.put("lastHeading", "intro");
        when(hashOps.entries(any(String.class))).thenReturn(hash);
        when(articleFacade.findIdByUuid(articleUuid)).thenReturn(100L);

        job.flush();

        verify(progressMapper).upsert(eq(1L), eq(100L), eq(new BigDecimal("0.6")), eq("intro"));
        verify(setOps).remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
    }

    @Test
    void flush_redisKeyExpiredButDirtyExists_removesDirtyEntry() {
        String entry = "1:" + UUID.randomUUID();
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(hashOps.entries(any(String.class))).thenReturn(Map.of());

        job.flush();

        verify(progressMapper, never()).upsert(any(), any(), any(), any());
        verify(setOps).remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
    }

    @Test
    void flush_dbErrorOnSingleEntry_keepsItInDirtyForRetry() {
        UUID articleUuid = UUID.randomUUID();
        String entry = "1:" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        Map<Object, Object> hash = new HashMap<>();
        hash.put("progress", "0.6");
        when(hashOps.entries(any(String.class))).thenReturn(hash);
        when(articleFacade.findIdByUuid(articleUuid)).thenReturn(100L);
        when(progressMapper.upsert(any(), any(), any(), any())).thenThrow(new RuntimeException("DB error"));

        job.flush();

        verify(setOps, never()).remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
    }

    @Test
    void flush_articleAlreadyDeleted_removesDirtyAndKey() {
        UUID articleUuid = UUID.randomUUID();
        String entry = "1:" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        Map<Object, Object> hash = new HashMap<>();
        hash.put("progress", "0.6");
        when(hashOps.entries(any(String.class))).thenReturn(hash);
        when(articleFacade.findIdByUuid(articleUuid)).thenReturn(null);

        job.flush();

        verify(redisTemplate).delete(RedisKeyConstant.READING_PROGRESS_PREFIX + entry);
        verify(setOps).remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
        verify(progressMapper, never()).upsert(any(), any(), any(), any());
    }
}
