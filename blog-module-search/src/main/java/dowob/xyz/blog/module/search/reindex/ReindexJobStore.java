package dowob.xyz.blog.module.search.reindex;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.search.model.dto.response.SearchReindexJobResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 全量重建作業的狀態與分散式鎖（D1／PERF-01）。
 *
 * <p><b>狀態放 Redis（不放記憶體）</b>：正式環境可能有多個實例，觸發的請求與之後查詢進度的請求不一定落在同一個實例，
 * 記憶體狀態只對執行該作業的實例有效；鎖本來就必須放在共享的地方。代價是 Redis 故障時查不到進度，
 * 狀態端點此時把 {@code reindex} 回成 {@code null}（不 500）。</p>
 *
 * <p><b>鎖</b>：沿用專案既有的 {@code setIfAbsent + TTL} 取鎖、token compare-and-delete 解鎖（{@code OrphanReconciliationJob}），
 * 值為 jobId。重建每批以 compare-and-expire 續期；續期失敗代表鎖已到期或被別人取得，重建必須中止，
 * 否則兩個重建可能同時切換 alias。</p>
 *
 * <p><b>狀態</b>：Hash {@code search:reindex:job} 只保存最近一次作業。除了 {@link #start} 之外的寫入都以 Lua 確認 jobId 相符才寫，
 * 中斷的舊作業遲到的更新不會蓋掉新作業。執行個體中途停止時狀態會停在 RUNNING，但鎖會到期——{@link #current()}
 * 據此回報 FAILED（{@link #INTERRUPTED_ERROR}），不會永遠顯示執行中。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
public class ReindexJobStore {

    /** 分散式鎖 TTL（秒）；重建每批續期，須遠大於單批耗時 */
    public static final long LOCK_TTL_SECONDS = 600L;

    /** 執行個體中斷時對外顯示的錯誤摘要 */
    public static final String INTERRUPTED_ERROR = "重建作業已中斷：執行它的實例停止或失聯（分散式鎖已到期），請重新觸發";

    /** token compare-and-delete：僅當鎖的值等於自己的 jobId 才刪除 */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /** token compare-and-expire：僅當鎖的值等於自己的 jobId 才續期 */
    private static final RedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    /** jobId 相符才寫入欄位：ARGV[1] 為 jobId，其後為 field／value 成對 */
    private static final RedisScript<Long> GUARDED_HSET_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('hget', KEYS[1], 'jobId') == ARGV[1] then "
                    + "for i = 2, #ARGV, 2 do redis.call('hset', KEYS[1], ARGV[i], ARGV[i + 1]) end return 1 end return 0",
            Long.class);

    /** Redis 操作 */
    private final StringRedisTemplate redis;

    /**
     * 建立作業狀態元件。
     *
     * @param redis Redis 操作
     */
    public ReindexJobStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 嘗試取得重建鎖（SET NX ＋ TTL，值為 jobId）。
     *
     * @param jobId 新作業的 id
     * @return 取得時 true；已有作業持有時 false
     */
    public boolean tryLock(UUID jobId) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                RedisKeyConstant.LOCK_SEARCH_REINDEX, jobId.toString(), LOCK_TTL_SECONDS, TimeUnit.SECONDS));
    }

    /**
     * 續期重建鎖（仍是自己的鎖才續期）。
     *
     * @param jobId 作業 id
     * @return 仍持有並已續期時 true；鎖已到期或被別人取得時 false
     */
    public boolean renewLock(UUID jobId) {
        Long renewed = redis.execute(RENEW_SCRIPT, List.of(RedisKeyConstant.LOCK_SEARCH_REINDEX),
                jobId.toString(), String.valueOf(LOCK_TTL_SECONDS));
        return renewed != null && renewed == 1L;
    }

    /**
     * 釋放重建鎖（只刪自己的）；失敗只記 warn——鎖有 TTL，會自行到期。
     *
     * @param jobId 作業 id
     */
    public void unlock(UUID jobId) {
        try {
            redis.execute(UNLOCK_SCRIPT, List.of(RedisKeyConstant.LOCK_SEARCH_REINDEX), jobId.toString());
        } catch (RuntimeException e) {
            log.warn("釋放搜尋索引重建鎖失敗（將於 TTL 到期後自動釋放）jobId={}: {}", jobId, e.getMessage());
        }
    }

    /**
     * 寫入新作業：刪除上一次作業的狀態後整份寫入（{@code null} 欄位不寫）。
     *
     * @param job 新作業
     */
    public void start(SearchReindexJobResponse job) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("jobId", job.getJobId().toString());
        fields.put("state", job.getState().name());
        fields.put("processed", String.valueOf(job.getProcessed()));
        if (job.getTotal() != null) {
            fields.put("total", String.valueOf(job.getTotal()));
        }
        if (job.getStartedAt() != null) {
            fields.put("startedAt", job.getStartedAt().toString());
        }
        redis.delete(RedisKeyConstant.SEARCH_REINDEX_JOB_KEY);
        redis.opsForHash().putAll(RedisKeyConstant.SEARCH_REINDEX_JOB_KEY, fields);
    }

    /**
     * 寫入進度分母。
     *
     * @param jobId 作業 id
     * @param total 已發布文章總數
     */
    public void updateTotal(UUID jobId, long total) {
        guardedSet(jobId, "total", String.valueOf(total));
    }

    /**
     * 寫入目前進度。
     *
     * @param jobId     作業 id
     * @param processed 已寫入新索引的篇數
     */
    public void updateProgress(UUID jobId, long processed) {
        guardedSet(jobId, "processed", String.valueOf(processed));
    }

    /**
     * 標記完成。
     *
     * @param jobId      作業 id
     * @param processed  寫入新索引的篇數
     * @param finishedAt 結束時間
     */
    public void complete(UUID jobId, long processed, LocalDateTime finishedAt) {
        guardedSet(jobId, "state", ReindexJobState.COMPLETED.name(), "processed", String.valueOf(processed),
                "finishedAt", finishedAt.toString());
    }

    /**
     * 標記失敗。
     *
     * @param jobId      作業 id
     * @param error      錯誤摘要
     * @param finishedAt 結束時間
     */
    public void fail(UUID jobId, String error, LocalDateTime finishedAt) {
        guardedSet(jobId, "state", ReindexJobState.FAILED.name(), "error", error, "finishedAt", finishedAt.toString());
    }

    /**
     * 讀取最近一次作業；RUNNING 但鎖已不在該作業手上時改回報 FAILED（{@link #INTERRUPTED_ERROR}）。
     *
     * @return 最近一次作業；從未執行或狀態資料損毀時為 empty
     */
    public Optional<SearchReindexJobResponse> current() {
        Map<Object, Object> raw = redis.opsForHash().entries(RedisKeyConstant.SEARCH_REINDEX_JOB_KEY);
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        SearchReindexJobResponse job;
        try {
            job = SearchReindexJobResponse.builder()
                    .jobId(UUID.fromString((String) raw.get("jobId")))
                    .state(ReindexJobState.valueOf((String) raw.get("state")))
                    .processed(Long.parseLong((String) raw.get("processed")))
                    .total(raw.get("total") == null ? null : Long.valueOf((String) raw.get("total")))
                    .startedAt(parseTime(raw.get("startedAt")))
                    .finishedAt(parseTime(raw.get("finishedAt")))
                    .error((String) raw.get("error"))
                    .build();
        } catch (RuntimeException e) {
            log.warn("搜尋索引重建作業狀態無法解析，視同沒有作業：{}", e.getMessage());
            return Optional.empty();
        }
        if (job.getState() == ReindexJobState.RUNNING
                && !job.getJobId().toString().equals(redis.opsForValue().get(RedisKeyConstant.LOCK_SEARCH_REINDEX))) {
            job.setState(ReindexJobState.FAILED);
            job.setError(INTERRUPTED_ERROR);
        }
        return Optional.of(job);
    }

    /**
     * 以鎖的值（jobId）取得持有重建鎖的作業（觸發時已有作業在跑，409 回應的 data）。
     *
     * <p>不能直接用 {@link #current()}：新作業取得鎖之後、寫入狀態之前，「最近一次作業」仍是上一個作業（審查 m5）。
     * 狀態已寫入時回傳完整狀態（含進度）；尚未寫入時只回 jobId 與 RUNNING。</p>
     *
     * @return 持鎖作業；鎖已釋放時為 empty
     */
    public Optional<SearchReindexJobResponse> lockHolderJob() {
        String owner = redis.opsForValue().get(RedisKeyConstant.LOCK_SEARCH_REINDEX);
        if (owner == null) {
            return Optional.empty();
        }
        Optional<SearchReindexJobResponse> latest = current();
        if (latest.isPresent() && owner.equals(latest.get().getJobId().toString())) {
            return latest;
        }
        try {
            return Optional.of(SearchReindexJobResponse.builder()
                    .jobId(UUID.fromString(owner)).state(ReindexJobState.RUNNING).build());
        } catch (IllegalArgumentException e) {
            log.warn("搜尋索引重建鎖的值不是 jobId：{}", owner);
            return Optional.empty();
        }
    }

    /**
     * jobId 相符才寫入（Lua）。
     *
     * @param jobId        作業 id
     * @param fieldsValues field／value 成對
     */
    private void guardedSet(UUID jobId, String... fieldsValues) {
        Object[] args = new Object[fieldsValues.length + 1];
        args[0] = jobId.toString();
        System.arraycopy(fieldsValues, 0, args, 1, fieldsValues.length);
        redis.execute(GUARDED_HSET_SCRIPT, List.of(RedisKeyConstant.SEARCH_REINDEX_JOB_KEY), args);
    }

    /**
     * 解析 ISO-8601 時間字串。
     *
     * @param value 字串；可為 null
     * @return 時間；null 輸入回 null
     */
    private static LocalDateTime parseTime(Object value) {
        return value == null ? null : LocalDateTime.parse((String) value);
    }
}
