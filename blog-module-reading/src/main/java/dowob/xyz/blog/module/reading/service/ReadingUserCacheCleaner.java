package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 使用者刪除帳號（DATA-02）時，清除該使用者在 Redis 的閱讀進度快取。
 *
 * <p>閱讀進度先寫 Redis hash（{@code reading:progress:{userUuid}:{articleUuid}}，TTL 3 天），再由
 * {@code ReadingProgressFlushJob} 定期 flush 進資料庫，待 flush 的成員記在 {@code reading:dirty} Set
 * （成員 {@code {userUuid}:{articleUuid}}）。只刪資料庫列不夠：殘留的 hash 在 TTL 內仍可被讀到，
 * dirty 成員則會讓 flush job 把進度再寫回資料庫，等於刪不乾淨。</p>
 *
 * <p>以 {@code SCAN}（非阻塞，不用 {@code KEYS}）依前綴比對該使用者的 key；{@code uuid} 的字元只有十六進位與連字號，
 * 不含任何 glob 特殊字元，不需跳脫。整個 keyspace 掃描一次的成本由「刪帳」這種罕見事件承擔。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadingUserCacheCleaner {

    /** 每次 SCAN 的批次大小提示 */
    private static final int SCAN_COUNT = 500;

    /** Redis 操作模板 */
    private final StringRedisTemplate redisTemplate;

    /**
     * 刪除某位使用者的閱讀進度 hash，並把屬於該使用者的 dirty 成員從待 flush Set 移除。
     *
     * <p>天然冪等：已無 key 或成員時什麼都不做。先清 hash 再清 dirty 成員，兩者之間若 flush job 恰好讀到成員，
     * 它讀到的是已不存在的 hash（視為過期），不會寫出新資料。</p>
     *
     * @param userUuid 使用者公開 UUID
     */
    public void clearProgressCache(UUID userUuid) {
        String memberPrefix = userUuid + ":";

        List<String> hashKeys = scanKeys(RedisKeyConstant.READING_PROGRESS_PREFIX + memberPrefix + "*");
        if (!hashKeys.isEmpty()) {
            redisTemplate.delete(hashKeys);
        }

        List<String> dirtyMembers = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(memberPrefix + "*").count(SCAN_COUNT).build();
        try (Cursor<String> cursor = redisTemplate.opsForSet().scan(RedisKeyConstant.READING_DIRTY_KEY, options)) {
            cursor.forEachRemaining(dirtyMembers::add);
        }
        if (!dirtyMembers.isEmpty()) {
            redisTemplate.opsForSet().remove(RedisKeyConstant.READING_DIRTY_KEY, dirtyMembers.toArray());
        }
        log.info("使用者刪除連動：已清除 Redis 閱讀進度快取 userUuid={} hashes={} dirtyMembers={}",
                userUuid, hashKeys.size(), dirtyMembers.size());
    }

    /**
     * 以 SCAN 收集符合樣式的所有 key。
     *
     * @param pattern glob 樣式
     * @return 符合的 key
     */
    private List<String> scanKeys(String pattern) {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(SCAN_COUNT).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }
}
