package dowob.xyz.blog.module.reading.job;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.reading.mapper.ReadingProgressMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reading Progress Redis → DB 定期 flush 任務。
 *
 * <p>每 5 分鐘掃 reading:dirty Set，把待持久化的 progress 寫到 DB。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadingProgressFlushJob {

    private final StringRedisTemplate redisTemplate;
    private final ArticleFacade articleFacade;
    private final ReadingProgressMapper progressMapper;

    @Scheduled(fixedDelayString = "${reading.progress.flush-interval-ms:300000}")
    public void flush() {
        Set<String> dirtyEntries = redisTemplate.opsForSet().members(RedisKeyConstant.READING_DIRTY_KEY);
        if (dirtyEntries == null || dirtyEntries.isEmpty()) return;

        for (String entry : dirtyEntries) {
            try {
                String[] parts = entry.split(":", 2);
                Long userId = Long.parseLong(parts[0]);
                UUID articleUuid = UUID.fromString(parts[1]);
                String key = RedisKeyConstant.READING_PROGRESS_PREFIX + entry;

                Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);
                if (hash.isEmpty()) {
                    redisTemplate.opsForSet().remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
                    continue;
                }

                Long articleId = articleFacade.findIdByUuid(articleUuid);
                if (articleId == null) {
                    redisTemplate.delete(key);
                    redisTemplate.opsForSet().remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
                    continue;
                }

                BigDecimal progress = new BigDecimal(hash.get("progress").toString());
                Object hVal = hash.get("lastHeading");
                String lastHeading = (hVal != null && !hVal.toString().isEmpty()) ? hVal.toString() : null;

                progressMapper.upsert(userId, articleId, progress, lastHeading);
                redisTemplate.opsForSet().remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
            } catch (Exception e) {
                log.warn("flush 跳過無效 entry，下次重試 - {}", entry, e);
            }
        }
    }
}
