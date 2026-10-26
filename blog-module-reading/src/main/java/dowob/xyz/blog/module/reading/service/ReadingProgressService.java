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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

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
 * <p>progress &gt;= 0.95 視為已讀完（先單調 upsert DB，再把快取設成完讀值，不帶 dirty）。其他情況走單一 Lua
 * {@link #WRITE_IF_NEWER_SCRIPT} 寫 hash＋TTL＋dirty Set，由 ReadingProgressFlushJob 5 分鐘 flush 到 DB。</p>
 *
 * <p><b>版本時間 {@code ts}（RP-1）</b>：每一筆寫入都帶「收到請求時的 JVM {@code System.currentTimeMillis()}」，
 * Redis（{@code WRITE_IF_NEWER}）與 DB（{@code ReadingProgressMapper.upsert} 的單調守衛）都只接受不比現有舊的寫入。
 * 因此亂序到達的請求、與 flush 交錯的完讀、與 {@link #get} 回填交錯的新 update 都不會讓較舊的值蓋掉較新的值。
 * 單調依時間而非進度大小：完讀後使用者回捲（0.97 → 0.2，ts 較新）會正常寫入。</p>
 *
 * <p><b>無交易</b>：{@code update} 只剩一條 DB 語句（自動 commit），Redis 寫入在 DB 之後；不在交易內動快取
 * （security.md 原則 10）。</p>
 *
 * @author Yuan
 * @version 3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadingProgressService {

    /**
     * 單調寫入：{@code KEYS[1]} 進度 hash、{@code KEYS[2]} dirty set；
     * {@code ARGV} = progress、lastHeading、ts（epoch ms）、TTL 秒數、dirty 成員（空字串＝不標 dirty）。
     *
     * <p>hash 現有 {@code updatedAt} 存在、可解析且大於 {@code ts} → 不寫並回 0；否則（含相等）原子地
     * {@code HSET} 三欄位、{@code EXPIRE}，成員非空時 {@code SADD}，回 1。整支在 Redis 內原子執行，
     * 不會出現「hash 已是新值卻沒有 dirty 標記」的中間狀態。</p>
     */
    static final RedisScript<Long> WRITE_IF_NEWER_SCRIPT = new DefaultRedisScript<>(
            "local cur = tonumber(redis.call('HGET', KEYS[1], 'updatedAt')) "
                    + "if cur and cur > tonumber(ARGV[3]) then return 0 end "
                    + "redis.call('HSET', KEYS[1], 'progress', ARGV[1], 'lastHeading', ARGV[2], 'updatedAt', ARGV[3]) "
                    + "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4])) "
                    + "if ARGV[5] ~= '' then redis.call('SADD', KEYS[2], ARGV[5]) end "
                    + "return 1",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ArticleFacade articleFacade;
    private final ReadingProgressMapper progressMapper;
    private final UserReadingProgressRepository progressRepo;

    /**
     * 更新閱讀進度；版本時間取收到請求當下的 {@code System.currentTimeMillis()}。
     *
     * @param userUuid    讀者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @param progress    進度（0.00 ~ 1.00）
     * @param lastHeading 最後閱讀的標題錨點（可為 null）
     * @see #update(UUID, UUID, BigDecimal, String, long)
     */
    public void update(UUID userUuid, UUID articleUuid, BigDecimal progress, String lastHeading) {
        update(userUuid, articleUuid, progress, lastHeading, System.currentTimeMillis());
    }

    /**
     * 以指定版本時間更新閱讀進度（亂序到達的請求以 {@code requestTs} 排序；測試與重放用）。
     *
     * <p>文章不存在（不分狀態）時靜默略過：不寫 Redis、不加 dirty、不落 DB（進度表已無指向 articles 的 FK，
     * 這個檢查是「不寫出孤兒列」的防線）。Redis key 為 {@code reading:progress:{userUuid}:{articleUuid}}，
     * dirty set 成員為 {@code {userUuid}:{articleUuid}}。</p>
     *
     * <p>進度未達完讀門檻：單一 Lua 寫 hash、TTL 與 dirty 成員（較舊的 {@code requestTs} 不會覆蓋較新的 hash）。
     * 達完讀門檻：先以 {@code requestTs} 單調 upsert DB，再 Lua 把快取設成完讀值但<b>不標 dirty</b>（值已在 DB），
     * 不 DEL hash——手上持有舊內容的 flush 其 compare-and-remove 會因 hash 內容已變而保留成員，下一輪讀到完讀值收斂；
     * 同時 {@link #get} 不會因 hash 消失而回填舊的 DB 值。</p>
     *
     * @param userUuid    讀者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @param progress    進度（0.00 ~ 1.00）
     * @param lastHeading 最後閱讀的標題錨點（可為 null）
     * @param requestTs   版本時間（收到請求時的 epoch ms）
     */
    public void update(UUID userUuid, UUID articleUuid, BigDecimal progress, String lastHeading, long requestTs) {
        if (!articleFacade.existsByUuid(articleUuid)) return;

        String key = RedisKeyConstant.READING_PROGRESS_PREFIX + userUuid + ":" + articleUuid;

        if (progress.compareTo(RedisKeyConstant.READING_PROGRESS_COMPLETED_THRESHOLD) >= 0) {
            progressMapper.upsert(userUuid, articleUuid, progress, lastHeading, toLocalDateTime(requestTs));
            writeIfNewer(key, progress.toPlainString(), lastHeading, requestTs, "");
        } else {
            writeIfNewer(key, progress.toPlainString(), lastHeading, requestTs, userUuid + ":" + articleUuid);
        }
    }

    /**
     * 查詢閱讀進度（Redis 優先，沒有時讀 DB 並回填 Redis）。
     *
     * <p>Redis 沒有且文章不存在時回 empty（不查 DB）。</p>
     *
     * <p>回填走 {@link #WRITE_IF_NEWER_SCRIPT}（ts 為 DB 列 {@code updated_at} 的 epoch ms、不帶 dirty）：
     * 讀完 DB 之後、回填之前若有併發 update 寫入較新的 hash，回填不會覆蓋它。</p>
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

    /**
     * 以 DB 列回填快取：ts 為該列 {@code updated_at} 轉 epoch ms，不帶 dirty 成員；
     * 快取中已有較新的值（併發 update 剛寫入）時不覆蓋。
     */
    private void cacheToRedis(String key, UserReadingProgress p) {
        long ts = p.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        writeIfNewer(key, p.getProgress().toPlainString(), p.getLastHeadingAnchor(), ts, "");
    }

    /**
     * 執行 {@link #WRITE_IF_NEWER_SCRIPT}（單一 Redis 指令）。
     *
     * @param key         進度 hash 的 key
     * @param progress    進度字串
     * @param lastHeading 標題錨點；null 以空字串寫入
     * @param ts          版本時間（epoch ms）
     * @param dirtyMember dirty set 成員；空字串代表不標 dirty
     */
    private void writeIfNewer(String key, String progress, String lastHeading, long ts, String dirtyMember) {
        redisTemplate.execute(WRITE_IF_NEWER_SCRIPT,
                List.of(key, RedisKeyConstant.READING_DIRTY_KEY),
                progress,
                lastHeading != null ? lastHeading : "",
                String.valueOf(ts),
                String.valueOf(TimeUnit.DAYS.toSeconds(RedisKeyConstant.READING_PROGRESS_TTL_DAYS)),
                dirtyMember);
    }

    private static LocalDateTime toLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
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
