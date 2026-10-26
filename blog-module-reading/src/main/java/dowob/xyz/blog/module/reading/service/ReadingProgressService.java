package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
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

/**
 * 閱讀進度 Service：Redis 主、DB 備份。
 *
 * <p>progress &gt;= 0.95 視為已讀完（DEL Redis + UPSERT DB）。其他情況走 Redis HSET +
 * 加入 dirty Set，由 ReadingProgressFlushJob 5 分鐘 flush 到 DB。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadingProgressService {

    private final StringRedisTemplate redisTemplate;
    private final ArticleFacade articleFacade;
    private final ReadingProgressMapper progressMapper;
    private final UserReadingProgressRepository progressRepo;

    /**
     * 更新閱讀進度。
     *
     * <p>文章不存在（不分狀態）時靜默略過：不寫 Redis、不加 dirty、不落 DB（進度表已無指向 articles 的 FK，
     * 這個檢查是「不寫出孤兒列」的防線）。Redis key 為 {@code reading:progress:{userUuid}:{articleUuid}}，
     * dirty set 成員為 {@code {userUuid}:{articleUuid}}。</p>
     *
     * @param userUuid    讀者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @param progress    進度（0.00 ~ 1.00）
     * @param lastHeading 最後閱讀的標題錨點（可為 null）
     */
    @Transactional
    public void update(UUID userUuid, UUID articleUuid, BigDecimal progress, String lastHeading) {
        if (!articleFacade.existsByUuid(articleUuid)) return;

        String key = RedisKeyConstant.READING_PROGRESS_PREFIX + userUuid + ":" + articleUuid;

        if (progress.compareTo(RedisKeyConstant.READING_PROGRESS_COMPLETED_THRESHOLD) >= 0) {
            redisTemplate.delete(key);
            progressMapper.upsert(userUuid, articleUuid, progress, lastHeading);
        } else {
            Map<String, String> hash = new HashMap<>();
            hash.put("progress", progress.toPlainString());
            hash.put("lastHeading", lastHeading != null ? lastHeading : "");
            hash.put("updatedAt", String.valueOf(System.currentTimeMillis()));
            redisTemplate.opsForHash().putAll(key, hash);
            redisTemplate.expire(key, RedisKeyConstant.READING_PROGRESS_TTL_DAYS, TimeUnit.DAYS);
            redisTemplate.opsForSet().add(RedisKeyConstant.READING_DIRTY_KEY, userUuid + ":" + articleUuid);
        }
    }

    /**
     * 查詢閱讀進度（Redis 優先，沒有時讀 DB 並回填 Redis）。
     *
     * <p>Redis 沒有且文章不存在時回 empty（不查 DB）。</p>
     *
     * @param userUuid    讀者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @return 閱讀進度；無紀錄為 empty
     */
    public Optional<ProgressResponse> get(UUID userUuid, UUID articleUuid) {
        String key = RedisKeyConstant.READING_PROGRESS_PREFIX + userUuid + ":" + articleUuid;
        Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);
        if (!hash.isEmpty()) {
            return Optional.of(fromHash(hash));
        }
        if (!articleFacade.existsByUuid(articleUuid)) return Optional.empty();

        Optional<UserReadingProgress> dbVal = progressRepo.findByUserUuidAndArticleUuid(userUuid, articleUuid);
        dbVal.ifPresent(p -> cacheToRedis(key, p));
        return dbVal.map(this::toResponse);
    }

    /**
     * 批次查詢閱讀進度：Redis 命中的直接用，沒命中的以單一 DB 查詢補齊。
     *
     * <p>以呼叫端給的文章 UUID 直接組 Redis key 與查 DB，不經 {@link ArticleFacade}（不再做 id↔uuid 轉換）。
     * 呼叫端傳入的本來就是已存在的文章；不存在的文章只會是「沒有進度」，結果中不含該 key。</p>
     *
     * @param userUuid     讀者公開 UUID；null 回空 Map（未登入）
     * @param articleUuids 文章公開 UUID 列表
     * @return articleUuid → progress（無紀錄則不含該 key）
     */
    public Map<UUID, BigDecimal> batchGetProgress(UUID userUuid, List<UUID> articleUuids) {
        if (userUuid == null || articleUuids == null || articleUuids.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<UUID, BigDecimal> result = new HashMap<>();
        List<UUID> missingUuids = new ArrayList<>();
        for (UUID articleUuid : articleUuids) {
            String key = RedisKeyConstant.READING_PROGRESS_PREFIX + userUuid + ":" + articleUuid;
            Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);
            if (!hash.isEmpty()) {
                Object pVal = hash.get("progress");
                if (pVal != null) {
                    result.put(articleUuid, new BigDecimal(pVal.toString()));
                }
            } else {
                missingUuids.add(articleUuid);
            }
        }

        if (!missingUuids.isEmpty()) {
            List<UserReadingProgress> dbRows = progressRepo.findByUserUuidAndArticleUuidIn(userUuid, missingUuids);
            dbRows.forEach(p -> result.put(p.getArticleUuid(), p.getProgress()));
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
            r.setUpdatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.valueOf(uVal.toString())), ZoneId.systemDefault()));
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
