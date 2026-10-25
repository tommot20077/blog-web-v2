package dowob.xyz.blog.module.reading.job;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.reading.mapper.ReadingProgressMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * <p><b>處理順序（RACE-13／DATA-08）</b>：對每個成員「先 SREM，再讀 hash 與 upsert；任何失敗時 SADD 回去」。
 * 舊流程是「讀 hash → upsert → 最後才 SREM」：讀 hash 之後、SREM 之前若有新的 update，新值寫進 hash，
 * 但 dirty set 已含該成員、SADD 是 no-op，隨後的 SREM 把 dirty 標記抹掉，新進度不會再被 flush，
 * hash 的 TTL 到期後讀回 DB 的舊值（進度回退）。先 SREM 之後，任何新的 update 都會重新 SADD，下一輪必被 flush；
 * 最壞情況只是多寫一次相同的值。SREM 回傳 0 表示成員已被另一個執行者取走，本輪略過。</p>
 *
 * <p><b>文章存在性（PERF-31）</b>：進度表沒有指向 articles 的 FK（V27），不存在的文章不能 upsert（會寫出孤兒列）。
 * 整輪所有成員的文章 UUID 以 {@link ArticleFacade#filterExistingUuids} <b>一次</b>批次檢查，不逐筆查詢；
 * 檢查本身失敗時整輪放棄、dirty set 不動，下一輪重試。</p>
 *
 * <p>無法解析的成員（含舊格式 {@code {Long}:{uuid}}）記 warn 並從 dirty set 移除，不重試。</p>
 *
 * <p>已知限制：{@code SMEMBERS} 一次取回整個 dirty set，成員數無上限（PERF-31 的另一半，不在 M2-c 處理）。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadingProgressFlushJob {

    private final StringRedisTemplate redisTemplate;
    private final ArticleFacade articleFacade;
    private final ReadingProgressMapper progressMapper;

    /** 已解析的 dirty 成員 */
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
     * 文章已不存在：刪 hash、成員移出 dirty，不 upsert。
     */
    private void discardForDeletedArticle(DirtyEntry entry) {
        try {
            redisTemplate.opsForSet().remove(RedisKeyConstant.READING_DIRTY_KEY, entry.member());
            redisTemplate.delete(RedisKeyConstant.READING_PROGRESS_PREFIX + entry.member());
        } catch (Exception e) {
            log.warn("flush 清理已刪除文章的進度失敗，下輪再試 - {}", entry.member(), e);
        }
    }

    /**
     * 先 SREM 成員，再讀 hash 與 upsert；任何失敗都把成員 SADD 回去。
     */
    private void flushOne(DirtyEntry entry) {
        String member = entry.member();
        Long removed;
        try {
            removed = redisTemplate.opsForSet().remove(RedisKeyConstant.READING_DIRTY_KEY, member);
        } catch (Exception e) {
            log.warn("flush 取走 dirty 成員失敗，本輪略過 - {}", member, e);
            return;
        }
        if (removed != null && removed == 0L) {
            return; // 已被另一個執行者取走
        }

        try {
            Map<Object, Object> hash = redisTemplate.opsForHash()
                    .entries(RedisKeyConstant.READING_PROGRESS_PREFIX + member);
            if (hash.isEmpty()) {
                return; // hash 已過期，沒有東西可寫；成員已移出
            }
            BigDecimal progress = new BigDecimal(hash.get("progress").toString());
            Object hVal = hash.get("lastHeading");
            String lastHeading = (hVal != null && !hVal.toString().isEmpty()) ? hVal.toString() : null;

            progressMapper.upsert(entry.userUuid(), entry.articleUuid(), progress, lastHeading);
        } catch (Exception e) {
            log.warn("flush 失敗，成員補回 dirty set 待下輪重試 - {}", member, e);
            restoreDirty(member);
        }
    }

    private void restoreDirty(String member) {
        try {
            redisTemplate.opsForSet().add(RedisKeyConstant.READING_DIRTY_KEY, member);
        } catch (Exception e) {
            log.error("flush 失敗後補回 dirty 成員也失敗，此進度要等使用者下次更新才會再被 flush - {}", member, e);
        }
    }
}
