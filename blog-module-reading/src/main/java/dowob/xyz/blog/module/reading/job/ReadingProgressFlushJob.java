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
 * <p><b>處理順序（RACE-13／DATA-08，at-least-once）</b>：對每個成員「讀 hash（記下 {@code updatedAt}）→ upsert →
 * Lua compare-and-remove」。Lua 在 Redis 內原子地檢查 {@code HGET key updatedAt} 是否仍等於剛才讀到的值，
 * 相等才 {@code SREM} 該成員；不相等（讀取之後又有新的 update）或 hash 已不存在時保留成員，留給下一輪。
 * 舊流程「讀 hash → upsert → 無條件 SREM」會把讀取之後的新 update 的 dirty 標記一併抹掉
 * （新 update 的 SADD 是 no-op），hash TTL 到期後進度回退。</p>
 *
 * <p>upsert 失敗、JVM 在 upsert 與 CAS 之間被終止、CAS 本身失敗時，成員都還在 dirty set，下一輪會重新寫入
 * （upsert 冪等，最壞情況只是多寫一次相同的值）；不需要「失敗時補回」，也就沒有補回本身失敗而遺失標記的路徑。
 * hash 已過期（讀到空）時，同一段 Lua 以空字串為期望值，僅在 hash 仍不存在時才 {@code SREM}，
 * 避免與剛好寫入的新 update 交錯而抹掉其標記。</p>
 *
 * <p>已知限制：{@code updatedAt} 是毫秒時間戳；同一毫秒內的兩次 update 版本相同，CAS 會把較晚者當成已 flush
 * （需要同一使用者在同一毫秒對同一篇文章送出兩個不同進度，且恰好落在 flush 的讀取與 CAS 之間）。</p>
 *
 * <p><b>文章存在性（PERF-31）</b>：進度表沒有指向 articles 的 FK（V27），不存在的文章不能 upsert（會寫出孤兒列）。
 * 整輪所有成員的文章 UUID 以 {@link ArticleFacade#filterExistingUuids} <b>一次</b>批次檢查，不逐筆查詢。
 * 取捨：檢查本身拋例外時<b>整輪放棄</b>、dirty set 不動，等下一輪再處理——寧可延後一個週期，
 * 也不把「查不到」當成「不存在」而丟掉進度，或在無法確認文章存在時寫出孤兒列。</p>
 *
 * <p>無法解析的成員（含舊格式 {@code {Long}:{uuid}}）記 warn 並從 dirty set 移除，不重試。</p>
 *
 * <p>已知限制：{@code SMEMBERS} 一次取回整個 dirty set，成員數無上限（PERF-31 的另一半，不在 M2-c 處理）。</p>
 *
 * @author Yuan
 * @version 3.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadingProgressFlushJob {

    /**
     * compare-and-remove：僅當 {@code HGET KEYS[1] updatedAt} 等於 {@code ARGV[1]} 才 {@code SREM KEYS[2] ARGV[2]}。
     * hash 不存在（或沒有 updatedAt 欄位）時 HGET 為 false，視為空字串，只與空字串期望值相等。
     * 回傳 1 代表已移除，0 代表保留。
     */
    static final RedisScript<Long> COMPARE_AND_REMOVE_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('HGET', KEYS[1], 'updatedAt') "
                    + "if current == false then current = '' end "
                    + "if current == ARGV[1] then return redis.call('SREM', KEYS[2], ARGV[2]) else return 0 end",
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
     * 讀 hash → upsert → compare-and-remove。任何一步失敗都不動 dirty set，成員留待下一輪。
     *
     * @param entry 要 flush 的成員
     */
    private void flushOne(DirtyEntry entry) {
        String member = entry.member();
        String hashKey = RedisKeyConstant.READING_PROGRESS_PREFIX + member;
        try {
            Map<Object, Object> hash = redisTemplate.opsForHash().entries(hashKey);
            if (hash.isEmpty()) {
                compareAndRemove(hashKey, member, "");
                return;
            }
            Object updatedAtVal = hash.get("updatedAt");
            String expectedUpdatedAt = updatedAtVal != null ? updatedAtVal.toString() : "";
            BigDecimal progress = new BigDecimal(hash.get("progress").toString());
            Object hVal = hash.get("lastHeading");
            String lastHeading = (hVal != null && !hVal.toString().isEmpty()) ? hVal.toString() : null;

            progressMapper.upsert(entry.userUuid(), entry.articleUuid(), progress, lastHeading);
            compareAndRemove(hashKey, member, expectedUpdatedAt);
        } catch (Exception e) {
            log.warn("flush 失敗，成員仍在 dirty set，下輪重試 - {}", member, e);
        }
    }

    /**
     * 以 Lua 原子地執行 compare-and-remove，見 {@link #COMPARE_AND_REMOVE_SCRIPT}。
     *
     * @param hashKey           進度 hash 的 key
     * @param member            dirty set 成員
     * @param expectedUpdatedAt 讀取 hash 時記下的 updatedAt；hash 為空時傳空字串
     */
    private void compareAndRemove(String hashKey, String member, String expectedUpdatedAt) {
        redisTemplate.execute(COMPARE_AND_REMOVE_SCRIPT,
                List.of(hashKey, RedisKeyConstant.READING_DIRTY_KEY), expectedUpdatedAt, member);
    }
}
