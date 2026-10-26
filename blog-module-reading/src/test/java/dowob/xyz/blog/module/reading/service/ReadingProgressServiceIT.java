package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.reading.job.ReadingProgressFlushJob;
import dowob.xyz.blog.module.reading.mapper.ReadingProgressMapper;
import dowob.xyz.blog.module.reading.model.dto.response.ProgressResponse;
import dowob.xyz.blog.module.reading.repository.UserReadingProgressRepository;
import dowob.xyz.blog.module.reading.support.ReadingCleanupITSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * {@link ReadingProgressService} 整合測試（真實 Redis、真實 Postgres）：閱讀進度一致性（RP-1）。
 *
 * <p>每筆寫入都帶版本時間 {@code ts}，Redis（Lua {@code WRITE_IF_NEWER}）與 DB（單調 upsert）兩邊都只接受
 * 「不比現有舊」的寫入。這裡以明確的 {@code ts} 重現亂序、完讀與回填的交錯，並以真實
 * {@link ReadingProgressFlushJob} 確認最終落到 DB 的值。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@DisplayName("ReadingProgressService 整合測試（Redis／DB 單調寫入、回填競態、完讀）")
class ReadingProgressServiceIT extends ReadingCleanupITSupport {

    @Autowired private ReadingProgressService progressService;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ArticleFacade articleFacade;
    @Autowired private ReadingProgressMapper realMapper;
    @Autowired private UserReadingProgressRepository progressRepo;

    private ReadingProgressFlushJob job;

    @BeforeEach
    void setUpJobAndCleanRedis() {
        job = new ReadingProgressFlushJob(redis, articleFacade, realMapper);
        clearRedis();
    }

    @AfterEach
    void cleanRedis() {
        clearRedis();
    }

    private void clearRedis() {
        Set<String> keys = redis.keys(RedisKeyConstant.READING_PROGRESS_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        redis.delete(RedisKeyConstant.READING_DIRTY_KEY);
    }

    private static String memberOf(UUID articleUuid) {
        return USER_UUID + ":" + articleUuid;
    }

    private static String hashKeyOf(UUID articleUuid) {
        return RedisKeyConstant.READING_PROGRESS_PREFIX + memberOf(articleUuid);
    }

    private boolean isDirty(UUID articleUuid) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(RedisKeyConstant.READING_DIRTY_KEY, memberOf(articleUuid)));
    }

    private BigDecimal hashProgress(UUID articleUuid) {
        Object v = redis.opsForHash().get(hashKeyOf(articleUuid), "progress");
        return v == null ? null : new BigDecimal(v.toString());
    }

    private String hashField(UUID articleUuid, String field) {
        Object v = redis.opsForHash().get(hashKeyOf(articleUuid), field);
        return v == null ? null : v.toString();
    }

    private BigDecimal dbProgress(UUID articleUuid) {
        return jdbc.query("SELECT progress FROM user_reading_progress WHERE user_uuid = ? AND article_uuid = ?",
                rs -> rs.next() ? rs.getBigDecimal(1) : null, USER_UUID, articleUuid);
    }

    private LocalDateTime dbUpdatedAt(UUID articleUuid) {
        return jdbc.query("SELECT updated_at FROM user_reading_progress WHERE user_uuid = ? AND article_uuid = ?",
                rs -> rs.next() ? rs.getTimestamp(1).toLocalDateTime() : null, USER_UUID, articleUuid);
    }

    private static LocalDateTime at(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    private UUID newArticle() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        return article.getUuid();
    }

    @Test
    @DisplayName("一次 update 後 hash（三個欄位）、TTL、dirty 成員三者齊備")
    void update_belowThreshold_writesHashTtlAndDirtyTogether() {
        UUID articleUuid = newArticle();
        long ts = System.currentTimeMillis();

        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.50"), "intro", ts);

        assertThat(hashProgress(articleUuid)).isEqualByComparingTo("0.50");
        assertThat(hashField(articleUuid, "lastHeading")).isEqualTo("intro");
        assertThat(hashField(articleUuid, "updatedAt")).isEqualTo(String.valueOf(ts));
        Long ttl = redis.getExpire(hashKeyOf(articleUuid), TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isBetween(
                TimeUnit.DAYS.toSeconds(RedisKeyConstant.READING_PROGRESS_TTL_DAYS) - 60,
                TimeUnit.DAYS.toSeconds(RedisKeyConstant.READING_PROGRESS_TTL_DAYS));
        assertThat(isDirty(articleUuid)).isTrue();
    }

    @Test
    @DisplayName("寫入的 Redis 指令（Lua execute）失敗：例外往上拋，hash 與 dirty 都不存在（原子，不會只寫一半）")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void update_redisExecuteFails_throwsAndLeavesNeitherHashNorDirty() {
        UUID articleUuid = newArticle();
        StringRedisTemplate spyRedis = spy(redis);
        doThrow(new IllegalStateException("redis down"))
                .when(spyRedis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        ReadingProgressService service = new ReadingProgressService(spyRedis, articleFacade, realMapper, progressRepo);

        assertThatThrownBy(() -> service.update(USER_UUID, articleUuid, new BigDecimal("0.50"), "intro"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("redis down");

        assertThat(redis.hasKey(hashKeyOf(articleUuid))).as("hash 不存在").isFalse();
        assertThat(isDirty(articleUuid)).as("dirty 不存在").isFalse();
    }

    @Test
    @DisplayName("亂序：先收到 ts=t2 再收到較舊的 ts=t1 → hash 與（flush 後）DB 都是 t2 的值")
    void update_olderRequestArrivesLater_keepsNewerInRedisAndDb() {
        UUID articleUuid = newArticle();
        long t1 = System.currentTimeMillis() - 10_000;
        long t2 = t1 + 5_000;

        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.60"), "newer", t2);
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.30"), "older", t1);

        assertThat(hashProgress(articleUuid)).isEqualByComparingTo("0.60");
        assertThat(hashField(articleUuid, "lastHeading")).isEqualTo("newer");
        assertThat(hashField(articleUuid, "updatedAt")).isEqualTo(String.valueOf(t2));

        job.flush();

        assertThat(dbProgress(articleUuid)).isEqualByComparingTo("0.60");
        assertThat(dbUpdatedAt(articleUuid)).isEqualTo(at(t2));
        assertThat(isDirty(articleUuid)).isFalse();
    }

    @Test
    @DisplayName("ts 相同的兩次 update：後到者覆寫（等於時允許）")
    void update_sameTs_laterWriteOverwrites() {
        UUID articleUuid = newArticle();
        long ts = System.currentTimeMillis();

        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.30"), "a", ts);
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.40"), "b", ts);

        assertThat(hashProgress(articleUuid)).isEqualByComparingTo("0.40");
    }

    @Test
    @DisplayName("完讀後再讀（0.97 → 0.2，ts 較新）：單調依時間不是進度大小，hash 與 flush 後的 DB 都是 0.2")
    void update_afterCompletionWithLowerProgressAndNewerTs_writesNormally() {
        UUID articleUuid = newArticle();
        long t1 = System.currentTimeMillis() - 10_000;

        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.97"), "end", t1);
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.20"), "top", t1 + 1_000);

        assertThat(hashProgress(articleUuid)).isEqualByComparingTo("0.20");
        assertThat(isDirty(articleUuid)).isTrue();

        job.flush();

        assertThat(dbProgress(articleUuid)).isEqualByComparingTo("0.20");
        assertThat(dbUpdatedAt(articleUuid)).isEqualTo(at(t1 + 1_000));
    }

    @Test
    @DisplayName("完讀路徑不 DEL hash：DB 立即為完讀值，快取設成完讀值且不標 dirty；get() 命中快取得到完讀值")
    void update_completion_keepsCacheAsCompletedValueAndGetHitsIt() {
        UUID articleUuid = newArticle();
        long t1 = System.currentTimeMillis() - 10_000;
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.30"), "first", t1);
        job.flush();
        assertThat(isDirty(articleUuid)).isFalse();

        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.97"), "end", t1 + 1_000);

        assertThat(dbProgress(articleUuid)).as("完讀直寫 DB").isEqualByComparingTo("0.97");
        assertThat(dbUpdatedAt(articleUuid)).isEqualTo(at(t1 + 1_000));
        assertThat(redis.hasKey(hashKeyOf(articleUuid))).as("hash 不得被 DEL").isTrue();
        assertThat(hashProgress(articleUuid)).isEqualByComparingTo("0.97");
        assertThat(isDirty(articleUuid)).as("完讀值已在 DB，不需 dirty").isFalse();
        Optional<ProgressResponse> got = progressService.get(USER_UUID, articleUuid);
        assertThat(got).isPresent();
        assertThat(got.get().getProgress()).isEqualByComparingTo("0.97");
        assertThat(got.get().getLastHeading()).isEqualTo("end");
        assertThat(got.get().getUpdatedAt()).isEqualTo(at(t1 + 1_000));
    }

    @Test
    @DisplayName("完讀請求比快取中的 hash 更舊（亂序）：不蓋掉較新的快取與其 dirty 標記；flush 後 DB 為較新者")
    void update_completionOlderThanCachedValue_keepsNewerCacheAndFlushesIt() {
        UUID articleUuid = newArticle();
        long t1 = System.currentTimeMillis() - 10_000;
        long t2 = t1 + 5_000;
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.30"), "newer", t2);

        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.97"), "end", t1);

        assertThat(dbProgress(articleUuid)).as("完讀的較舊請求仍寫入 DB（DB 尚無更新的列）").isEqualByComparingTo("0.97");
        assertThat(hashProgress(articleUuid)).as("快取仍是較新的 0.30").isEqualByComparingTo("0.30");
        assertThat(isDirty(articleUuid)).isTrue();

        job.flush();

        assertThat(dbProgress(articleUuid)).isEqualByComparingTo("0.30");
        assertThat(dbUpdatedAt(articleUuid)).isEqualTo(at(t2));
    }

    @Test
    @DisplayName("get() 快取 miss 回填：以 DB 列的 updated_at 為 ts、不標 dirty，hash 與 DB 一致")
    void get_cacheMiss_backfillsWithDbTimestampAndNoDirty() {
        UUID articleUuid = newArticle();
        long dbTs = System.currentTimeMillis() - 3_600_000;
        realMapper.upsert(USER_UUID, articleUuid, new BigDecimal("0.40"), "db", at(dbTs));

        Optional<ProgressResponse> got = progressService.get(USER_UUID, articleUuid);

        assertThat(got).isPresent();
        assertThat(got.get().getProgress()).isEqualByComparingTo("0.40");
        assertThat(hashProgress(articleUuid)).isEqualByComparingTo("0.40");
        assertThat(hashField(articleUuid, "updatedAt")).isEqualTo(String.valueOf(dbTs));
        assertThat(redis.getExpire(hashKeyOf(articleUuid), TimeUnit.SECONDS)).as("回填有 TTL").isPositive();
        assertThat(isDirty(articleUuid)).as("回填不標 dirty").isFalse();
    }

    @Test
    @DisplayName("get() 讀完 DB 之後、回填之前插入較新的 update：回填不覆蓋，快取與後續 flush 後的 DB 都是新值")
    void get_updateArrivesBetweenDbReadAndBackfill_backfillDoesNotOverwriteNewer() {
        UUID articleUuid = newArticle();
        long dbTs = System.currentTimeMillis() - 3_600_000;
        realMapper.upsert(USER_UUID, articleUuid, new BigDecimal("0.30"), "db", at(dbTs));
        UserReadingProgressRepository hookedRepo =
                mock(UserReadingProgressRepository.class, AdditionalAnswers.delegatesTo(progressRepo));
        doAnswer(invocation -> {
            Object row = invocation.getMethod().invoke(progressRepo, invocation.getArguments());
            progressService.update(USER_UUID, articleUuid, new BigDecimal("0.60"), "new");
            return row;
        }).when(hookedRepo).findByUserUuidAndArticleUuid(any(), any());
        ReadingProgressService getter = new ReadingProgressService(redis, articleFacade, realMapper, hookedRepo);

        getter.get(USER_UUID, articleUuid);

        assertThat(hashProgress(articleUuid)).as("併發 update 剛寫的新值不得被舊 DB 值回填蓋掉").isEqualByComparingTo("0.60");
        assertThat(hashField(articleUuid, "lastHeading")).isEqualTo("new");
        assertThat(isDirty(articleUuid)).isTrue();

        job.flush();

        assertThat(dbProgress(articleUuid)).isEqualByComparingTo("0.60");
        assertThat(isDirty(articleUuid)).isFalse();
    }

    @Test
    @DisplayName("文章不存在：update 靜默略過，不寫 Redis、不標 dirty、不落 DB")
    void update_articleNotFound_writesNothing() {
        UUID missing = UUID.randomUUID();

        progressService.update(USER_UUID, missing, new BigDecimal("0.50"), "x", System.currentTimeMillis());
        progressService.update(USER_UUID, missing, new BigDecimal("0.99"), "y", System.currentTimeMillis());

        assertThat(redis.hasKey(hashKeyOf(missing))).isFalse();
        assertThat(isDirty(missing)).isFalse();
        assertThat(countProgress(missing)).isZero();
    }

    /** 委派給真實 mapper 的 mapper，可在 upsert 前後插入 hook，或讓 upsert 失敗（一次性）。 */
    private final class HookingMapper implements ReadingProgressMapper {
        private Runnable beforeUpsert;
        private Runnable afterUpsert;
        private boolean failNext;

        @Override
        public int upsert(UUID userUuid, UUID articleUuid, BigDecimal progress, String lastHeading,
                          LocalDateTime updatedAt) {
            if (beforeUpsert != null) {
                Runnable hook = beforeUpsert;
                beforeUpsert = null;
                hook.run();
            }
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("db down");
            }
            int affected = realMapper.upsert(userUuid, articleUuid, progress, lastHeading, updatedAt);
            if (afterUpsert != null) {
                Runnable hook = afterUpsert;
                afterUpsert = null;
                hook.run();
            }
            return affected;
        }
    }

    @Test
    @DisplayName("完讀路徑 DB 寫入成功、Redis 寫入失敗（舊 hash＋dirty 仍在）：flush 發現 upsert 被守衛擋下，丟棄舊 hash 與成員；get() 回 DB 的完讀值")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void flush_completionRedisWriteFailed_staleHashIsDiscardedAndGetReturnsDbValue() {
        UUID articleUuid = newArticle();
        long t1 = System.currentTimeMillis() - 20_000;
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.30"), "old", t1);
        StringRedisTemplate spyRedis = spy(redis);
        doThrow(new IllegalStateException("redis down"))
                .when(spyRedis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        ReadingProgressService failing = new ReadingProgressService(spyRedis, articleFacade, realMapper, progressRepo);

        assertThatThrownBy(() -> failing.update(USER_UUID, articleUuid, new BigDecimal("0.97"), "end", t1 + 5_000))
                .isInstanceOf(IllegalStateException.class);
        assertThat(dbProgress(articleUuid)).as("DB 已是完讀值").isEqualByComparingTo("0.97");
        assertThat(hashProgress(articleUuid)).as("舊 hash 還在").isEqualByComparingTo("0.30");
        assertThat(isDirty(articleUuid)).isTrue();

        job.flush();

        assertThat(redis.hasKey(hashKeyOf(articleUuid))).as("舊 hash 被丟棄").isFalse();
        assertThat(isDirty(articleUuid)).as("成員被移除").isFalse();
        assertThat(dbProgress(articleUuid)).isEqualByComparingTo("0.97");
        Optional<ProgressResponse> got = progressService.get(USER_UUID, articleUuid);
        assertThat(got).isPresent();
        assertThat(got.get().getProgress()).as("get() 讀到 DB 的完讀值而非舊 hash").isEqualByComparingTo("0.97");
    }

    @Test
    @DisplayName("DB 時鐘比 JVM 快（DB 列 updated_at 在未來 5 分鐘）：回填的 ts 不超過現在，之後的 update 不被擋下，hash 為新值並標 dirty")
    void get_dbRowUpdatedAtInFuture_backfillTsCappedAtNowSoLaterUpdateIsAccepted() {
        UUID articleUuid = newArticle();
        long now = System.currentTimeMillis();
        realMapper.upsert(USER_UUID, articleUuid, new BigDecimal("0.40"), "legacy", at(now + 300_000));

        progressService.get(USER_UUID, articleUuid);
        long backfillTs = Long.parseLong(hashField(articleUuid, "updatedAt"));
        assertThat(backfillTs).as("回填 ts 上限為現在").isLessThanOrEqualTo(System.currentTimeMillis());
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.70"), "new");

        assertThat(hashProgress(articleUuid)).isEqualByComparingTo("0.70");
        assertThat(isDirty(articleUuid)).isTrue();
    }

    @Test
    @DisplayName("完讀請求被 DB 守衛擋下（DB 已有更新的列）：不寫 Redis，快取不與 DB 分歧")
    void update_completionRejectedByDbGuard_doesNotWriteRedis() {
        UUID articleUuid = newArticle();
        long now = System.currentTimeMillis();
        realMapper.upsert(USER_UUID, articleUuid, new BigDecimal("0.40"), "legacy", at(now + 300_000));

        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.97"), "end");

        assertThat(dbProgress(articleUuid)).as("DB 維持較新的列").isEqualByComparingTo("0.40");
        assertThat(redis.hasKey(hashKeyOf(articleUuid))).as("完讀值不得寫入 Redis").isFalse();
        assertThat(isDirty(articleUuid)).isFalse();
    }

    @Test
    @DisplayName("完讀路徑順序為先 DB 後 Redis：DB upsert 開始時快取仍是舊值；DB 失敗時快取不被改動")
    void update_completion_writesDbBeforeRedisAndLeavesRedisUntouchedWhenDbFails() {
        UUID articleUuid = newArticle();
        long t1 = System.currentTimeMillis() - 20_000;
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.30"), "old", t1);
        HookingMapper hooking = new HookingMapper();
        ReadingProgressService service = new ReadingProgressService(redis, articleFacade, hooking, progressRepo);
        BigDecimal[] hashAtUpsert = new BigDecimal[1];
        hooking.beforeUpsert = () -> hashAtUpsert[0] = hashProgress(articleUuid);
        service.update(USER_UUID, articleUuid, new BigDecimal("0.97"), "end", t1 + 5_000);
        assertThat(hashAtUpsert[0]).as("DB upsert 時 Redis 尚未寫入完讀值").isEqualByComparingTo("0.30");
        assertThat(hashProgress(articleUuid)).isEqualByComparingTo("0.97");

        hooking.failNext = true;
        assertThatThrownBy(() -> service.update(USER_UUID, articleUuid, new BigDecimal("0.99"), "end2", t1 + 9_000))
                .isInstanceOf(IllegalStateException.class);

        assertThat(hashProgress(articleUuid)).as("DB 失敗時 Redis 不得已被寫成 0.99").isEqualByComparingTo("0.97");
    }

    @Test
    @DisplayName("完讀路徑兩步之間插入一次持舊內容的 flush：DB 為完讀值、hash 為完讀值、dirty 已清")
    void update_completionWithStaleFlushBetweenDbAndCache_endsConsistent() {
        UUID articleUuid = newArticle();
        long t1 = System.currentTimeMillis() - 20_000;
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.30"), "old", t1);
        HookingMapper hooking = new HookingMapper();
        ReadingProgressService service = new ReadingProgressService(redis, articleFacade, hooking, progressRepo);
        hooking.afterUpsert = job::flush;

        service.update(USER_UUID, articleUuid, new BigDecimal("0.97"), "end", t1 + 5_000);

        assertThat(dbProgress(articleUuid)).isEqualByComparingTo("0.97");
        assertThat(hashProgress(articleUuid)).isEqualByComparingTo("0.97");
        assertThat(hashField(articleUuid, "updatedAt")).isEqualTo(String.valueOf(t1 + 5_000));
        assertThat(isDirty(articleUuid)).isFalse();
    }

    @Test
    @DisplayName("完讀路徑兩步之間插入一次較新的未達門檻 update：快取保留較新者並標 dirty，flush 後 DB 為較新者")
    void update_completionWithNewerUpdateBetweenDbAndCache_newerWins() {
        UUID articleUuid = newArticle();
        long t1 = System.currentTimeMillis() - 20_000;
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.30"), "old", t1);
        HookingMapper hooking = new HookingMapper();
        ReadingProgressService service = new ReadingProgressService(redis, articleFacade, hooking, progressRepo);
        hooking.afterUpsert = () ->
                progressService.update(USER_UUID, articleUuid, new BigDecimal("0.50"), "newest", t1 + 9_000);

        service.update(USER_UUID, articleUuid, new BigDecimal("0.97"), "end", t1 + 5_000);

        assertThat(dbProgress(articleUuid)).isEqualByComparingTo("0.97");
        assertThat(hashProgress(articleUuid)).as("較新的 0.50 不被完讀值蓋掉").isEqualByComparingTo("0.50");
        assertThat(isDirty(articleUuid)).isTrue();

        job.flush();

        assertThat(dbProgress(articleUuid)).isEqualByComparingTo("0.50");
        assertThat(dbUpdatedAt(articleUuid)).isEqualTo(at(t1 + 9_000));
        assertThat(isDirty(articleUuid)).isFalse();
    }
}
