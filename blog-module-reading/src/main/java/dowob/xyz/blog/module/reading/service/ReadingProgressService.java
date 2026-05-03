package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.module.reading.mapper.ReadingProgressMapper;
import dowob.xyz.blog.module.reading.model.UserReadingProgress;
import dowob.xyz.blog.module.reading.model.dto.response.ProgressResponse;
import dowob.xyz.blog.module.reading.repository.UserReadingProgressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 閱讀進度 Service：Redis 主、DB 備份。
 *
 * <p>progress &gt;= 0.95 視為已讀完（DEL Redis + UPSERT DB）。其他情況走 Redis HSET +
 * 加入 dirty Set，由 ReadingProgressFlushJob 5 分鐘 flush 到 DB。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadingProgressService {

    private final StringRedisTemplate redisTemplate;
    private final ArticleFacade articleFacade;
    private final ReadingProgressMapper progressMapper;
    private final UserReadingProgressRepository progressRepo;

    @Transactional
    public void update(Long userId, UUID articleUuid, BigDecimal progress, String lastHeading) {
        Long articleId = articleFacade.findIdByUuid(articleUuid);
        if (articleId == null) return;

        String key = RedisKeyConstant.READING_PROGRESS_PREFIX + userId + ":" + articleUuid;

        if (progress.compareTo(RedisKeyConstant.READING_PROGRESS_COMPLETED_THRESHOLD) >= 0) {
            redisTemplate.delete(key);
            progressMapper.upsert(userId, articleId, progress, lastHeading);
        } else {
            Map<String, String> hash = new HashMap<>();
            hash.put("progress", progress.toPlainString());
            hash.put("lastHeading", lastHeading != null ? lastHeading : "");
            hash.put("updatedAt", String.valueOf(System.currentTimeMillis()));
            redisTemplate.opsForHash().putAll(key, hash);
            redisTemplate.expire(key, RedisKeyConstant.READING_PROGRESS_TTL_DAYS, TimeUnit.DAYS);
            redisTemplate.opsForSet().add(RedisKeyConstant.READING_DIRTY_KEY, userId + ":" + articleUuid);
        }
    }

    public Optional<ProgressResponse> get(Long userId, UUID articleUuid) {
        String key = RedisKeyConstant.READING_PROGRESS_PREFIX + userId + ":" + articleUuid;
        Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);
        if (!hash.isEmpty()) {
            return Optional.of(fromHash(hash));
        }
        Long articleId = articleFacade.findIdByUuid(articleUuid);
        if (articleId == null) return Optional.empty();

        Optional<UserReadingProgress> dbVal = progressRepo.findByUserIdAndArticleId(userId, articleId);
        dbVal.ifPresent(p -> cacheToRedis(key, p));
        return dbVal.map(this::toResponse);
    }

    public Map<Long, BigDecimal> batchGetProgress(Long userId, List<Long> articleIds) {
        if (userId == null || articleIds == null || articleIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<ArticleData> articles = articleFacade.findByIds(articleIds);
        Map<Long, UUID> idToUuid = articles.stream()
                .collect(Collectors.toMap(ArticleData::id, ArticleData::uuid));

        Map<Long, BigDecimal> result = new HashMap<>();
        List<Long> missingIds = new ArrayList<>();
        for (Map.Entry<Long, UUID> entry : idToUuid.entrySet()) {
            String key = RedisKeyConstant.READING_PROGRESS_PREFIX + userId + ":" + entry.getValue();
            Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);
            if (!hash.isEmpty()) {
                Object pVal = hash.get("progress");
                if (pVal != null) {
                    result.put(entry.getKey(), new BigDecimal(pVal.toString()));
                }
            } else {
                missingIds.add(entry.getKey());
            }
        }

        if (!missingIds.isEmpty()) {
            List<UserReadingProgress> dbRows = progressRepo.findByUserIdAndArticleIdIn(userId, missingIds);
            dbRows.forEach(p -> result.put(p.getArticleId(), p.getProgress()));
        }
        return result;
    }

    private void cacheToRedis(String key, UserReadingProgress p) {
        Map<String, String> hash = new HashMap<>();
        hash.put("progress", p.getProgress().toPlainString());
        hash.put("lastHeading", p.getLastHeadingAnchor() != null ? p.getLastHeadingAnchor() : "");
        hash.put("updatedAt", String.valueOf(p.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
        redisTemplate.opsForHash().putAll(key, hash);
        redisTemplate.expire(key, RedisKeyConstant.READING_PROGRESS_TTL_DAYS, TimeUnit.DAYS);
    }

    private ProgressResponse fromHash(Map<Object, Object> hash) {
        ProgressResponse r = new ProgressResponse();
        Object pVal = hash.get("progress");
        if (pVal != null) r.setProgress(new BigDecimal(pVal.toString()));
        Object hVal = hash.get("lastHeading");
        r.setLastHeading(hVal != null && !hVal.toString().isEmpty() ? hVal.toString() : null);
        Object uVal = hash.get("updatedAt");
        if (uVal != null) {
            r.setUpdatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(uVal.toString())), ZoneId.systemDefault()));
        }
        return r;
    }

    private ProgressResponse toResponse(UserReadingProgress p) {
        ProgressResponse r = new ProgressResponse();
        r.setProgress(p.getProgress());
        r.setLastHeading(p.getLastHeadingAnchor());
        r.setUpdatedAt(p.getUpdatedAt());
        return r;
    }
}
