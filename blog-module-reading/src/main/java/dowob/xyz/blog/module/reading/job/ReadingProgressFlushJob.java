package dowob.xyz.blog.module.reading.job;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.reading.mapper.ReadingProgressMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reading Progress Redis → DB 定期 flush 任務。
 *
 * <p>每 5 分鐘掃 {@code reading:dirty} Set（成員 {@code {userUuid}:{articleUuid}}），
 * 把待持久化的 progress 寫到 DB。</p>
 *
 * <p><b>處理順序（RACE-13／DATA-08，at-least-once）</b>：對每個成員「讀 hash（記下 {@code progress}、{@code lastHeading}、{@code updatedAt}）→ upsert →
 * Lua compare-and-remove」。Lua 在 Redis 內原子地檢查這三個欄位是否仍全部等於剛才讀到的值，
 * 全部相等才 {@code SREM} 該成員；任一不同（讀取之後又有新的 update）或 hash 已不存在時保留成員，留給下一輪。
 * 舊流程「讀 hash → upsert → 無條件 SREM」會把讀取之後的新 update 的 dirty 標記一併抹掉
 * （新 update 的 SADD 是 no-op），hash TTL 到期後進度回退。</p>
 *
 * <p>upsert 失敗、JVM 在 upsert 與 CAS 之間被終止、CAS 本身失敗時，成員都還在 dirty set，下一輪會重新寫入
 * （upsert 冪等，最壞情況只是多寫一次相同的值）；不需要「失敗時補回」，也就沒有補回本身失敗而遺失標記的路徑。
 * hash 已過期（讀到空）時，三個欄位都以空字串為期望值，僅在 hash 仍不存在時才 {@code SREM}，
 * 避免與剛好寫入的新 update 交錯而抹掉其標記。</p>
 *
 * <p>為什麼比對內容而不只比 {@code updatedAt}：{@code updatedAt} 是毫秒時間戳，同一毫秒內進度不同的兩次 update
 * 版本相同，只比它會把較晚者當成已 flush。三個欄位一起比時，進度或標題不同的更新必然被擋下；
 * 三個欄位完全相同（含 A→B→A）代表 DB 剛 upsert 的就是這個值，移除標記是正確的。</p>
 *
 * <p><b>文章存在性（PERF-31）</b>：進度表沒有指向 articles 的 FK（V27），不存在的文章不能 upsert（會寫出孤兒列）。
 * 整輪所有成員的文章 UUID 以 {@link ArticleFacade#filterExistingUuids} <b>一次</b>批次檢查，不逐筆查詢。
 * 取捨：檢查本身拋例外時<b>整輪放棄</b>、dirty set 不動，等下一輪再處理——寧可延後一個週期，
 * 也不把「查不到」當成「不存在」而丟掉進度，或在無法確認文章存在時寫出孤兒列。</p>
 *
 * <p>無法解析的成員（含舊格式 {@code {Long}:{uuid}}）記 warn 並從 dirty set 移除，不重試。</p>
 *
 * <p><b>單調 upsert（RP-1）</b>：upsert 的 {@code updatedAt} 取 hash 的 {@code updatedAt}（使用者送出該進度的時間，
 * epoch ms → {@code ZoneId.systemDefault()} 的 {@code LocalDateTime}），DB 端 {@code ON CONFLICT ... WHERE updated_at <= EXCLUDED.updated_at}
 * 只接受不比現有舊的寫入。因此兩個 flusher 交錯（F1 讀舊值停頓、F2 寫新值並清標記、F1 再 upsert 舊值），
 * 或完讀路徑直寫 DB 後舊 flush 才 upsert，舊值都會被守衛擋下，DB 不會回退；被擋下的 flusher 的 CAS
 * 因 hash 內容已變（或成員已被移除）而不動 dirty，下一輪讀到新值再收斂。</p>
 *
 * <p><b>壞資料（poison pill）</b>：hash 缺 {@code progress}、{@code updatedAt}，或兩者無法解析時，不 upsert、
 * 不拋例外中斷整輪，記 warn 後以讀到的內容做 {@link #COMPARE_AND_DISCARD_SCRIPT}（原子地「內容未變才 SREM 成員並 DEL hash」）；
 * 內容已變（有新的合法 update）則兩者都保留，交給下一輪。</p>
 *
 * <p>已知限制：{@code SMEMBERS} 一次取回整個 dirty set，成員數無上限（PERF-31 的另一半，不在 M2-c 處理）。</p>
 *
 * @author Yuan
 * @version 5.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadingProgressFlushJob {

    /**
     * compare-and-remove：僅當 {@code HMGET KEYS[1] progress lastHeading updatedAt} 的三個值依序等於
     * {@code ARGV[1..3]} 才 {@code SREM KEYS[2] ARGV[4]}。不存在的欄位（Lua 回 false）一律視為空字串，
     * 因此 hash 不存在時只與三個空字串的期望值相等。回傳 1 代表已移除，0 代表保留。
     */
    static final RedisScript<Long> COMPARE_AND_REMOVE_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('HMGET', KEYS[1], 'progress', 'lastHeading', 'updatedAt') "
                    + "for i = 1, 3 do if c[i] == false then c[i] = '' end end "
                    + "if c[1] == ARGV[1] and c[2] == ARGV[2] and c[3] == ARGV[3] then "
                    + "return redis.call('SREM', KEYS[2], ARGV[4]) end "
                    + "return 0",
            Long.class);

    /**
     * compare-and-discard：與 {@link #COMPARE_AND_REMOVE_SCRIPT} 相同的三欄位比對；全部相等時
     * {@code DEL KEYS[1]}（壞資料 hash）並 {@code SREM KEYS[2] ARGV[4]}，兩者在 Redis 內原子完成，
     * 避免「移除成員後、DEL 前」插入的新 update 被一併刪掉。回傳 1 代表已丟棄，0 代表保留（內容已變）。
     */
    static final RedisScript<Long> COMPARE_AND_DISCARD_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('HMGET', KEYS[1], 'progress', 'lastHeading', 'updatedAt') "
                    + "for i = 1, 3 do if c[i] == false then c[i] = '' end end "
                    + "if c[1] == ARGV[1] and c[2] == ARGV[2] and c[3] == ARGV[3] then "
                    + "redis.call('DEL', KEYS[1]) "
                    + "return redis.call('SREM', KEYS[2], ARGV[4]) end "
                    + "return 0",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ArticleFacade articleFacade;
    private final ReadingProgressMapper progressMapper;

    /**
     * 已解析的 dirty 成員。
     *
     * @param member      dirty set 內的原始成員字串
     * @param userUuid    讀者公開 UUID
     * @param articleUuid 文章公開 UUID
     */
    private record DirtyEntry(String member, UUID userUuid, UUID articleUuid) {
    }

    /**
     * 排程入口：把 dirty set 內的閱讀進度 flush 到 DB。單一成員失敗不影響同一輪其他成員。
     */
    @Scheduled(fixedDelayString = "${reading.progress.flush-interval-ms:300000}")
    public void flush() {
        Set<String> dirtyMembers = redisTemplate.opsForSet().members(RedisKeyConstant.READING_DIRTY_KEY);
        if (dirtyMembers == null || dirtyMembers.isEmpty()) return;

        List<DirtyEntry> entries = parse(dirtyMembers);
        if (entries.isEmpty()) return;

        Set<UUID> existingArticles;
        try {
            Set<UUID> articleUuids = new HashSet<>();
            entries.forEach(e -> articleUuids.add(e.articleUuid()));
            existingArticles = articleFacade.filterExistingUuids(articleUuids);
        } catch (Exception e) {
            log.warn("flush 無法確認文章是否存在，本輪放棄、dirty set 不動，下輪重試 - entries={}", entries.size(), e);
            return;
        }

        for (DirtyEntry entry : entries) {
            if (!existingArticles.contains(entry.articleUuid())) {
                discardForDeletedArticle(entry);
                continue;
            }
            flushOne(entry);
        }
    }

    /**
     * 解析 dirty 成員；無法解析者記 warn 並從 dirty set 移除（不重試）。
     *
     * @param members dirty set 的全部成員
     * @return 可解析的成員
     */
    private List<DirtyEntry> parse(Set<String> members) {
        List<DirtyEntry> entries = new ArrayList<>(members.size());
        for (String member : members) {
            try {
                String[] parts = member.split(":", 2);
                entries.add(new DirtyEntry(member, UUID.fromString(parts[0]), UUID.fromString(parts[1])));
            } catch (Exception e) {
                log.warn("flush 移除無法解析的 dirty 成員（不重試）- {}", member, e);
                try {
                    redisTemplate.opsForSet().remove(RedisKeyConstant.READING_DIRTY_KEY, member);
                } catch (Exception removeError) {
                    log.warn("flush 移除無法解析的 dirty 成員失敗，下輪再試 - {}", member, removeError);
                }
            }
        }
        return entries;
    }

    /**
     * 文章已不存在：先刪 hash、再把成員移出 dirty，不 upsert。
     *
     * <p>順序刻意是「先 DEL 後 SREM」：任何一步拋例外時成員都還在 dirty set，「下輪再試」才名實相符。</p>
     *
     * @param entry 對應文章已不存在的成員
     */
    private void discardForDeletedArticle(DirtyEntry entry) {
        try {
            redisTemplate.delete(RedisKeyConstant.READING_PROGRESS_PREFIX + entry.member());
            redisTemplate.opsForSet().remove(RedisKeyConstant.READING_DIRTY_KEY, entry.member());
        } catch (Exception e) {
            log.warn("flush 清理已刪除文章的進度失敗，成員仍在 dirty set，下輪再試 - {}", entry.member(), e);
        }
    }

    /**
     * 讀 hash → upsert → compare-and-remove（壞資料改走 compare-and-discard）。任何一步失敗都不動 dirty set，成員留待下一輪。
     *
     * @param entry 要 flush 的成員
     */
    private void flushOne(DirtyEntry entry) {
        String member = entry.member();
        String hashKey = RedisKeyConstant.READING_PROGRESS_PREFIX + member;
        try {
            Map<Object, Object> hash = redisTemplate.opsForHash().entries(hashKey);
            if (hash.isEmpty()) {
                compareAndRemove(hashKey, member, "", "", "");
                return;
            }
            String rawProgress = valueOrEmpty(hash.get("progress"));
            String rawHeading = valueOrEmpty(hash.get("lastHeading"));
            String rawUpdatedAt = valueOrEmpty(hash.get("updatedAt"));
            BigDecimal progress = parseProgress(rawProgress);
            LocalDateTime updatedAt = parseUpdatedAt(rawUpdatedAt);
            if (progress == null || updatedAt == null) {
                log.warn("flush 發現壞資料（progress 或 updatedAt 缺失／無法解析），丟棄且不 upsert - {} progress='{}' updatedAt='{}'",
                        member, rawProgress, rawUpdatedAt);
                compareAndDiscard(hashKey, member, rawProgress, rawHeading, rawUpdatedAt);
                return;
            }
            String lastHeading = rawHeading.isEmpty() ? null : rawHeading;

            progressMapper.upsert(entry.userUuid(), entry.articleUuid(), progress, lastHeading, updatedAt);
            compareAndRemove(hashKey, member, rawProgress, rawHeading, rawUpdatedAt);
        } catch (Exception e) {
            log.warn("flush 失敗，成員仍在 dirty set，下輪重試 - {}", member, e);
        }
    }

    /**
     * 以 Lua 原子地執行 compare-and-remove，見 {@link #COMPARE_AND_REMOVE_SCRIPT}。
     *
     * @param hashKey          進度 hash 的 key
     * @param member           dirty set 成員
     * @param expectedProgress 讀取 hash 時的 progress 原始字串；hash 為空時傳空字串
     * @param expectedHeading  讀取 hash 時的 lastHeading 原始字串；缺少或 hash 為空時傳空字串
     * @param expectedUpdated  讀取 hash 時的 updatedAt 原始字串；缺少或 hash 為空時傳空字串
     */
    private void compareAndRemove(String hashKey, String member, String expectedProgress,
                                  String expectedHeading, String expectedUpdated) {
        redisTemplate.execute(COMPARE_AND_REMOVE_SCRIPT,
                List.of(hashKey, RedisKeyConstant.READING_DIRTY_KEY),
                expectedProgress, expectedHeading, expectedUpdated, member);
    }

    /**
     * 以 Lua 原子地執行 compare-and-discard，見 {@link #COMPARE_AND_DISCARD_SCRIPT}。
     *
     * @param hashKey          進度 hash 的 key
     * @param member           dirty set 成員
     * @param expectedProgress 讀取 hash 時的 progress 原始字串；缺少時傳空字串
     * @param expectedHeading  讀取 hash 時的 lastHeading 原始字串；缺少時傳空字串
     * @param expectedUpdated  讀取 hash 時的 updatedAt 原始字串；缺少時傳空字串
     */
    private void compareAndDiscard(String hashKey, String member, String expectedProgress,
                                   String expectedHeading, String expectedUpdated) {
        redisTemplate.execute(COMPARE_AND_DISCARD_SCRIPT,
                List.of(hashKey, RedisKeyConstant.READING_DIRTY_KEY),
                expectedProgress, expectedHeading, expectedUpdated, member);
    }

    /**
     * 解析 progress。
     *
     * @param raw hash 內 progress 的原始字串
     * @return 進度；缺少（空字串）或無法解析回 null
     */
    private static BigDecimal parseProgress(String raw) {
        if (raw.isEmpty()) return null;
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析 updatedAt（epoch ms）為系統時區的 {@link LocalDateTime}（與 mapper 的時區假設一致）。
     *
     * @param raw hash 內 updatedAt 的原始字串
     * @return 時間；缺少（空字串）或無法解析回 null
     */
    private static LocalDateTime parseUpdatedAt(String raw) {
        if (raw.isEmpty()) return null;
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(raw)), ZoneId.systemDefault());
        } catch (NumberFormatException | DateTimeException e) {
            return null;
        }
    }

    private static String valueOrEmpty(Object value) {
        return value != null ? value.toString() : "";
    }
}
