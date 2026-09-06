package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
