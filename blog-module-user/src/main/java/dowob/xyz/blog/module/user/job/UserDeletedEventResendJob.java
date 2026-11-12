package dowob.xyz.blog.module.user.job;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.user.repository.UserRepository;
import dowob.xyz.blog.module.user.service.UserEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 近期刪帳事件補發排程（M2）：{@code UserDeletedEvent} 的輕量補償，是 transactional outbox 的替代品。
 *
 * <p>{@code UserService.deleteAccount} 在交易 commit 後 best-effort 發送 {@code UserDeletedEvent}；
 * MQ 當下不可用時事件遺失，各模組（article 草稿、reading 收藏、search 的 ES 暱稱冗餘等）不會清理。
 * 本排程每小時掃描 {@code status='DELETED' AND updated_at > now() - 48h} 的使用者，<b>無條件</b>重發事件
 * （每次新的 eventId）。各 consumer 皆以 userUuid 為條件刪除／改寫，對已清理完的使用者重送是 no-op，
 * 故天然冪等，重複發送只浪費少量 MQ 流量。</p>
 *
 * <p><b>界限</b>：只涵蓋 48 小時視窗——MQ 故障超過 48 小時才恢復、或刪帳後又被更新而超出視窗者，
 * 不會被補償，需人工依 ERROR log（{@code UserEventPublisher}）重送。沒有「已確認消費」的狀態，
 * 所以視窗內每小時都會重送一次（最多約 48 次/人）；若日後規模變大應改為真正的 outbox 表。
 * users 表規模小、條件只命中極少數列，查詢不另建索引。</p>
 *
 * <p>多副本以 Redis 鎖（{@code lock:reconciliation:user:users.deleted_event_resend}）互斥，
 * 慣例同 TagUsageReconciliationJob。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
public class UserDeletedEventResendJob {

    /** 每小時第 7 分（與既有每日 03:17~04:11 校正排程錯開，單執行緒排程池不會互相排隊太久） */
    public static final String CRON = "0 7 * * * *";

    /** 補發視窗 */
    static final Duration WINDOW = Duration.ofHours(48);

    /** 分散式鎖 TTL（秒） */
    private static final long LOCK_TTL_SECONDS = 600L;

    /** token compare-and-delete 解鎖腳本 */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
            Long.class);

    private final UserRepository userRepository;
    private final UserEventPublisher publisher;
    private final StringRedisTemplate redis;

    /**
     * 執行結果。
     *
     * @param executed 是否取得鎖並執行（false 表示別的實例正在跑，本次跳過）
     * @param resent   成功補發（呼叫未拋例外）的使用者數
     */
    public record Result(boolean executed, int resent) {
    }

    /**
     * 建構子。
     *
     * @param userRepository 使用者資料存取
     * @param publisher      使用者事件發布元件
     * @param redis          Redis 模板（分散式鎖）
     */
    @Autowired
    public UserDeletedEventResendJob(UserRepository userRepository, UserEventPublisher publisher,
                                     StringRedisTemplate redis) {
        this.userRepository = userRepository;
        this.publisher = publisher;
        this.redis = redis;
    }

    /** 排程入口：委派 {@link #run()}，例外只記錄、不外拋。 */
    @Scheduled(cron = CRON)
    void scheduledRun() {
        try {
            run();
        } catch (RuntimeException e) {
            log.error("UserDeletedEvent 補發排程失敗", e);
        }
    }

    /**
     * 執行一次補發。
     *
     * @return 執行結果；取不到鎖時 {@code executed=false}
     */
    public Result run() {
        String lockKey = RedisKeyConstant.getReconciliationLockKey("user", "users.deleted_event_resend");
        String token = UUID.randomUUID().toString();
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("未取得分散式鎖，跳過本次 UserDeletedEvent 補發");
            return new Result(false, 0);
        }
        try {
            List<UUID> uuids = userRepository.findRecentlyDeletedUuids(LocalDateTime.now().minus(WINDOW));
            int resent = 0;
            for (UUID uuid : uuids) {
                try {
                    publisher.publishDeleted(uuid);
                    resent++;
                } catch (RuntimeException e) {
                    log.error("UserDeletedEvent 補發失敗：userUuid={}, error={}", uuid, e.getMessage(), e);
                }
            }
            if (resent > 0) {
                log.info("UserDeletedEvent 補發完成：{} 位近期刪除的使用者", resent);
            }
            return new Result(true, resent);
        } finally {
            unlock(lockKey, token);
        }
    }

    private void unlock(String lockKey, String token) {
        try {
            redis.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
        } catch (RuntimeException e) {
            log.warn("UserDeletedEvent 補發解鎖失敗（鎖將於 TTL 到期後自動釋放）: {}", e.getMessage());
        }
    }
}
