package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * 文章瀏覽計數服務實作
 *
 * <p>
 * 採用 Redis 暫存增量 + 定期批次刷入 DB 的策略，
 * 避免每次瀏覽都直接寫入 DB 造成的效能瓶頸。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
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
     * <p>先從 DB 取得基礎值，再加上 Redis 中的暫存增量。</p>
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
        long delta = (redisVal != null) ? Long.parseLong(redisVal) : 0L;
        return base + delta;
    }

    /**
     * {@inheritDoc}
     *
     * <p>對 Redis 對應 key 執行原子性自增操作。</p>
     *
     * @param articleUuid 文章公開 UUID
     */
    @Override
    public void incrementRedisViewCount(UUID articleUuid) {
        stringRedisTemplate.opsForValue()
                .increment(RedisKeyConstant.ARTICLE_VIEWS_PREFIX + articleUuid);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * 掃描所有 {@code article:views:*} key，使用 getAndDelete 原子性取值並清除，
     * 再將增量以 UPDATE 批次寫入 DB。
     * </p>
     */
    @Override
    public void flushViewCounts() {
        Set<String> keys = stringRedisTemplate.keys(RedisKeyConstant.ARTICLE_VIEWS_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            String val = stringRedisTemplate.opsForValue().getAndDelete(key);
            if (val != null) {
                UUID uuid = UUID.fromString(
                        key.substring(RedisKeyConstant.ARTICLE_VIEWS_PREFIX.length()));
                articleMapper.incrementViewCountBatch(uuid, Long.parseLong(val));
            }
        }
    }
}
