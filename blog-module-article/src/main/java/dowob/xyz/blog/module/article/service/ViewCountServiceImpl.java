package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文章瀏覽計數服務實作
 *
 * <p>
 * 採用 Redis 暫存增量 + 定期批次刷入 DB 的策略，
 * 避免每次瀏覽都直接寫入 DB 造成的效能瓶頸。
 * 使用 SCAN 取代 KEYS 以避免 Redis 阻塞；
 * 批次刷入時先寫 DB 再刪 Redis key，確保資料不遺失；
 * 每個 key 的處理包覆 try-catch，確保單一錯誤不中斷整批刷新。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViewCountServiceImpl implements ViewCountService {

    /** 文章 MyBatis Mapper */
    private final ArticleMapper articleMapper;

    /** Redis String 操作模板 */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * {@inheritDoc}
     *
     * <p>
     * 先從 DB 取得基礎值，再加上 Redis 中的暫存增量。
     * 若 Redis 值無法解析為數字，則視增量為 0。
     * </p>
     *
     * @param articleUuid 文章公開 UUID
     * @return 總瀏覽數（DB 值 + Redis 增量）
     */
    @Override
    public long getViewCount(UUID articleUuid) {
        Long dbCount = articleMapper.findViewCountByUuid(articleUuid);
        String redisVal = stringRedisTemplate.opsForValue()
                .get(RedisKeyConstant.ARTICLE_VIEWS_PREFIX + articleUuid);
        return combine(articleUuid, dbCount, redisVal);
    }

    /**
     * 合併 DB 基礎值與 Redis 暫存增量——單篇（{@link #getViewCount}）與批次（{@link #addRedisDeltas}）
     * 共用的<b>唯一</b>一份規則：DB 為 null 視為 0；Redis 缺值視為 0；非數字記警告並視為 0。
     *
     * @param articleUuid 文章公開 UUID（僅供警告日誌）
     * @param dbCount     DB 的 {@code view_count}（可為 null）
     * @param redisVal    Redis 暫存增量的字串值（可為 null）
     * @return 總瀏覽數
     */
    private static long combine(UUID articleUuid, Long dbCount, String redisVal) {
        long base = (dbCount != null) ? dbCount : 0L;
        long delta;
        try {
            delta = (redisVal != null) ? Long.parseLong(redisVal) : 0L;
        } catch (NumberFormatException e) {
            log.warn("Redis 瀏覽計數值非數字，視為 0 - uuid={}, value={}", articleUuid, redisVal);
            delta = 0L;
        }
        return base + delta;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Redis 以 {@code MGET} 批次取回，每 {@link BatchedQuery#BATCH_SIZE} 個 key 一批
     * （搜尋頁上限 {@code PageQuery.MAX_SIZE} = 100，實務上為單次 MGET；切批是對任意呼叫端的保險）。</p>
     */
    @Override
    public Map<UUID, Long> addRedisDeltas(Map<UUID, Long> dbViewCounts) {
        if (dbViewCounts == null || dbViewCounts.isEmpty()) {
            return Map.of();
        }
        List<UUID> uuids = new ArrayList<>(dbViewCounts.keySet());
        List<String> keys = uuids.stream().map(u -> RedisKeyConstant.ARTICLE_VIEWS_PREFIX + u).toList();
        /* 與 DB 查詢一致依 BATCH_SIZE 切批；MGET 回傳與 key 同序，批次串接後仍與 uuids 對齊 */
        List<String> redisVals = BatchedQuery.queryInBatches(keys, batch -> {
            List<String> vals = stringRedisTemplate.opsForValue().multiGet(batch);
            return vals != null ? vals : Collections.<String>nCopies(batch.size(), null);
        });
        Map<UUID, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < uuids.size(); i++) {
            UUID uuid = uuids.get(i);
            String redisVal = i < redisVals.size() ? redisVals.get(i) : null;
            result.put(uuid, combine(uuid, dbViewCounts.get(uuid), redisVal));
        }
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * 對 Redis 對應 key 執行原子性自增操作。
     * </p>
     *
     * @param articleUuid 文章公開 UUID
     */
    @Override
    public void incrementRedisViewCount(UUID articleUuid) {
        String key = RedisKeyConstant.ARTICLE_VIEWS_PREFIX + articleUuid;
        stringRedisTemplate.opsForValue().increment(key);
        stringRedisTemplate.expire(key, RedisKeyConstant.ARTICLE_VIEWS_TTL_HOURS, java.util.concurrent.TimeUnit.HOURS);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * 使用非阻塞的 SCAN 命令掃描所有 {@code article:views:*} key；
     * 對每個 key：先從 Redis 讀取增量，寫入 DB 成功後才刪除 Redis key，
     * 確保 DB 失敗時資料不遺失。
     * 每個 key 的處理若發生任何例外（非法 UUID、非數字值、DB 錯誤），
     * 僅記錄警告並繼續處理下一個 key。
     * </p>
     */
    @Override
    public void flushViewCounts() {
        String pattern = RedisKeyConstant.ARTICLE_VIEWS_PREFIX + "*";
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                try {
                    String suffix = key.substring(RedisKeyConstant.ARTICLE_VIEWS_PREFIX.length());
                    UUID uuid = UUID.fromString(suffix);
                    String val = stringRedisTemplate.opsForValue().getAndDelete(key);
                    if (val == null) {
                        continue;
                    }
                    long delta = Long.parseLong(val);
                    if (delta > 0) {
                        try {
                            articleMapper.incrementViewCountBatch(uuid, delta);
                        } catch (Exception e) {
                            log.error("DB 更新失敗，還原 Redis 瀏覽計數 - key={}, delta={}", key, delta, e);
                            stringRedisTemplate.opsForValue().increment(key, delta);
                        }
                    }
                } catch (Exception e) {
                    log.warn("略過無效的瀏覽計數 key，將於下次排程重試 - key={}", key, e);
                }
            }
        }
    }
}
