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
 * <p><b>綁定作業身分</b>（審查 M2）：標記的值是 jobId；續期、取出、結束都以 Lua 先比對 jobId。鎖遺失的舊作業無法再續期
 * （因而中止、不切換）、取不走新作業的變動、收尾也刪不掉新作業的標記與集合。</p>
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

    /**
     * 只有標記仍是本作業才續期：KEYS[1] 標記；ARGV[1] jobId、ARGV[2] TTL 秒數。回 1 為已續期，0 為不是本作業或已到期。
     */
    private static final RedisScript<Long> KEEP_ALIVE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], ARGV[2]) end return 0",
            Long.class);

    /**
     * 只有標記仍是本作業才刪除標記與集合：KEYS[1] 標記、KEYS[2..] 集合；ARGV[1] jobId。
     */
    private static final RedisScript<Long> END_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', unpack(KEYS)) end return 0",
            Long.class);

    /**
     * 只有標記仍是本作業才取出（同時續期）：KEYS[1] 標記、KEYS[2] 集合；ARGV[1] jobId、ARGV[2] 筆數、ARGV[3] TTL 秒數。
     * 不是本作業時回 Redis 錯誤（呼叫端收到例外即中止重建），不取走任何成員。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final RedisScript<List> DRAIN_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) ~= ARGV[1] then return redis.error_reply('REINDEX_JOURNAL_NOT_OWNED') end "
                    + "redis.call('expire', KEYS[1], ARGV[3]) "
                    + "return redis.call('spop', KEYS[2], ARGV[2])",
            List.class);

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
     * 重建開始：清掉上一次殘留的集合，再設定進行中標記（值為本作業的 jobId，附 TTL）。
     *
     * <p>順序不可顛倒：先設標記再清集合，會把標記設定後才記下的變動一起刪掉。標記一律覆寫：呼叫端已持有重建鎖，
     * 若有前一個作業的殘留標記，代表那個作業的鎖已遺失，它之後的續期、取出、結束都會因 jobId 不符而失效（審查 M2）。</p>
     *
     * @param jobId 本作業 id
     */
    public void begin(UUID jobId) {
        redis.delete(List.of(RedisKeyConstant.SEARCH_REINDEX_DIRTY_ARTICLES_KEY,
                RedisKeyConstant.SEARCH_REINDEX_DIRTY_AUTHORS_KEY));
        redis.opsForValue().set(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY, jobId.toString(), ACTIVE_TTL);
    }

    /**
     * 延長進行中標記的 TTL；只有標記仍是本作業時才續期（compare-and-expire）。
     *
     * <p>回 false 代表標記已被別的作業接手、或已到期——此時 listener 的變動可能記到別的作業名下或根本沒記，
     * 呼叫端必須中止重建且<b>不切換</b>（審查 M2）。</p>
     *
     * @param jobId 本作業 id
     * @return 仍屬於本作業並已續期時 true
     */
    public boolean keepAlive(UUID jobId) {
        Long renewed = redis.execute(KEEP_ALIVE_IF_OWNER, List.of(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY),
                jobId.toString(), String.valueOf(ACTIVE_TTL.toSeconds()));
        return renewed != null && renewed == 1L;
    }

    /**
     * 重建結束（成功或失敗）：只有標記仍是本作業時，才一併刪除標記與兩個集合（compare-and-delete）。
     *
     * <p>鎖遺失的舊作業在收尾時呼叫，不會刪掉已接手的新作業的標記與變動（審查 M2）。</p>
     *
     * @param jobId 本作業 id
     */
    public void end(UUID jobId) {
        redis.execute(END_IF_OWNER, List.of(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY,
                RedisKeyConstant.SEARCH_REINDEX_DIRTY_ARTICLES_KEY, RedisKeyConstant.SEARCH_REINDEX_DIRTY_AUTHORS_KEY),
                jobId.toString());
    }

    /**
     * 重建進行中時記下這篇文章有變動（沒有重建時不寫入任何東西）。
     *
     * <p>必須在寫入 alias <b>之前</b>呼叫。Redis 故障時例外原樣往外拋，由呼叫端決定處置：
     * {@code SearchServiceImpl} 依決策 D1-1 記 WARN、累加 {@code search.reindex.journal.failed} 後照常寫 ES
     * （重建期間遇到 Redis 抖動的那一筆可能沒進新索引，再跑一次重建補回）。</p>
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
     * 取出（並移除）至多 {@code max} 篇有變動的文章；只有標記仍是本作業時才取出，並同時續期。
     *
     * @param jobId 本作業 id
     * @param max   上限
     * @return 文章 UUID 字串；沒有時為空清單
     * @throws RuntimeException 標記已不屬於本作業（不取走任何成員，呼叫端應中止重建）
     */
    public List<String> drainArticles(UUID jobId, int max) {
        return pop(jobId, RedisKeyConstant.SEARCH_REINDEX_DIRTY_ARTICLES_KEY, max);
    }

    /**
     * 取出（並移除）至多 {@code max} 位暱稱被改寫的作者；只有標記仍是本作業時才取出，並同時續期。
     *
     * @param jobId 本作業 id
     * @param max   上限
     * @return 作者 UUID 字串；沒有時為空清單
     * @throws RuntimeException 標記已不屬於本作業
     */
    public List<String> drainAuthors(UUID jobId, int max) {
        return pop(jobId, RedisKeyConstant.SEARCH_REINDEX_DIRTY_AUTHORS_KEY, max);
    }

    /**
     * 比對 jobId 後續期並 SPOP count（Lua，原子）。
     *
     * @param jobId 本作業 id
     * @param key   集合 key
     * @param max   上限
     * @return 取出的成員
     */
    @SuppressWarnings("unchecked")
    private List<String> pop(UUID jobId, String key, int max) {
        List<String> popped = redis.execute(DRAIN_IF_OWNER, List.of(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY, key),
                jobId.toString(), String.valueOf(max), String.valueOf(ACTIVE_TTL.toSeconds()));
        return popped == null ? List.of() : popped;
    }
}
