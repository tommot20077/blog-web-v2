package dowob.xyz.blog.module.search.reindex;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 全量重建期間的增量變動紀錄（D1／PERF-01：重建期間的增量更新不能遺失）。
 *
 * <p><b>問題</b>：重建在 alias 背後建新的實體索引；切換前 alias 仍指向舊索引，listener 的發布、更新、下架寫入只會進到舊索引，
 * 切換後就消失了。</p>
 *
 * <p><b>選擇的作法（brief 的方案 b，變動集合由 listener 記錄）</b>：重建進行中（Redis 有 {@code search:reindex:active}），
 * listener 寫入 alias <b>之前</b>先把文章（或刪帳作者）記進集合；重建在切換 alias 前後各把集合取空一次，
 * 對每篇回查 DB 現況——仍為 PUBLISHED 的寫進新索引、已下架／退回／刪除的從新索引移除，作者則以當下暱稱改寫。
 * 正確性來自三件事：事件在 DB commit 之後才發出，所以回查一定看得到這次變動或更新的狀態；「先記錄、再寫入」保證
 * 寫入落在舊索引的變動，記錄必然早於切換後那次取空；取空用 SPOP，取空期間新記的會在同一個迴圈被取到。</p>
 *
 * <p><b>沒選的作法</b>：</p>
 * <ul>
 *   <li>(a) listener 雙寫新舊索引：批次讀取在 t1 拿到快照、t3 才 bulk 寫入，中間 t2 listener 寫進新索引的較新版本會被舊快照蓋掉；
 *       先刪後寫更糟（刪除時文件還不在新索引，之後的 bulk 又把它寫回來，變成幽靈）。要擋住需要外部版本號與刪除墓碑，
 *       而 ES 的刪除墓碑只保留 {@code index.gc_deletes}（預設 60 秒）。另外 listener 若在新索引被清理後才寫入實體名稱，
 *       ES 會以 dynamic mapping 自動建出同名索引。</li>
 *   <li>以 {@code updated_at >= 開始時間} 補跑：硬刪除的文章在 DB 已無列可查；{@code updated_at} 由各實例的 JVM 時鐘填寫，
 *       有時鐘偏差，且早於開始時間取得時間戳、晚於開始時間才 commit 的交易會被漏掉。</li>
 * </ul>
 *
 * <p>集合與標記放 Redis 而不是記憶體：listener 可能在任何一個實例上消費，重建只在取得分散式鎖的那一個實例上跑。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
public class ReindexJournal {

    /** 進行中標記的 TTL（與分散式鎖同長，重建每批續期；實例中途停止時自行到期，listener 不會永遠記錄下去） */
    public static final Duration ACTIVE_TTL = Duration.ofMinutes(10);

    /**
     * 進行中才寫入（原子）：標記存在時 SADD 並回傳新增數，否則回 -1。
     *
     * <p>KEYS[1] 進行中標記、KEYS[2] 變動集合；ARGV[1] UUID 字串。</p>
     */
    private static final RedisScript<Long> RECORD_IF_ACTIVE = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return redis.call('sadd', KEYS[2], ARGV[1]) end return -1",
            Long.class);

    /** Redis 操作 */
    private final StringRedisTemplate redis;

    /**
     * 建立變動紀錄元件。
     *
     * @param redis Redis 操作
     */
    public ReindexJournal(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 重建開始：清掉上一次殘留的集合，再設定進行中標記（值為新實體索引名稱，附 TTL）。
     *
     * <p>順序不可顛倒：先設標記再清集合，會把標記設定後才記下的變動一起刪掉。</p>
     *
     * @param targetIndex 本次重建的新實體索引
     */
    public void begin(String targetIndex) {
        redis.delete(List.of(RedisKeyConstant.SEARCH_REINDEX_DIRTY_ARTICLES_KEY,
                RedisKeyConstant.SEARCH_REINDEX_DIRTY_AUTHORS_KEY));
        redis.opsForValue().set(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY, targetIndex, ACTIVE_TTL);
    }

    /**
     * 延長進行中標記的 TTL（重建每批呼叫）。
     */
    public void keepAlive() {
        redis.expire(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY, ACTIVE_TTL);
    }

    /**
     * 重建結束（成功或失敗）：先移除進行中標記，再刪除集合。
     */
    public void end() {
        redis.delete(List.of(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY,
                RedisKeyConstant.SEARCH_REINDEX_DIRTY_ARTICLES_KEY, RedisKeyConstant.SEARCH_REINDEX_DIRTY_AUTHORS_KEY));
    }

    /**
     * 重建進行中時記下這篇文章有變動（沒有重建時不寫入任何東西）。
     *
     * <p>必須在寫入 alias <b>之前</b>呼叫。Redis 故障時例外原樣往外拋：listener 會失敗並由 MQ 容器重試，
     * 而不是在不知道有沒有重建的情況下照寫，讓變動可能隨切換靜默遺失。</p>
     *
     * @param articleUuid 文章 UUID 字串（文件 id）
     */
    public void recordArticle(String articleUuid) {
        redis.execute(RECORD_IF_ACTIVE,
                List.of(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY, RedisKeyConstant.SEARCH_REINDEX_DIRTY_ARTICLES_KEY),
                articleUuid);
    }

    /**
     * 重建進行中時記下這位作者的暱稱被改寫（刪帳匿名化）。語意同 {@link #recordArticle}。
     *
     * @param authorUuid 作者公開 UUID
     */
    public void recordAuthor(UUID authorUuid) {
        redis.execute(RECORD_IF_ACTIVE,
                List.of(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY, RedisKeyConstant.SEARCH_REINDEX_DIRTY_AUTHORS_KEY),
                authorUuid.toString());
    }

    /**
     * 取出（並移除）至多 {@code max} 篇有變動的文章。
     *
     * @param max 上限
     * @return 文章 UUID 字串；沒有時為空清單
     */
    public List<String> drainArticles(int max) {
        return pop(RedisKeyConstant.SEARCH_REINDEX_DIRTY_ARTICLES_KEY, max);
    }

    /**
     * 取出（並移除）至多 {@code max} 位暱稱被改寫的作者。
     *
     * @param max 上限
     * @return 作者 UUID 字串；沒有時為空清單
     */
    public List<String> drainAuthors(int max) {
        return pop(RedisKeyConstant.SEARCH_REINDEX_DIRTY_AUTHORS_KEY, max);
    }

    /**
     * SPOP count。
     *
     * @param key 集合 key
     * @param max 上限
     * @return 取出的成員
     */
    private List<String> pop(String key, int max) {
        List<String> popped = redis.opsForSet().pop(key, max);
        return popped == null ? List.of() : popped;
    }
}
