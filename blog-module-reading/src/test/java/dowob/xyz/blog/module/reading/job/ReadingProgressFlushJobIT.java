package dowob.xyz.blog.module.reading.job;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.reading.mapper.ReadingProgressMapper;
import dowob.xyz.blog.module.reading.service.ReadingProgressService;
import dowob.xyz.blog.module.reading.service.ReadingProgressTestAccess;
import dowob.xyz.blog.module.reading.support.ReadingCleanupITSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * {@link ReadingProgressFlushJob} 整合測試（真實 Redis、真實 Postgres；進度的更新走真實 {@link ReadingProgressService}）。
 *
 * <p><b>RACE-13／DATA-08</b>：舊流程是「讀 hash → upsert → 無條件 SREM」。讀 hash 之後、SREM 之前若有新的
 * update，新值寫進 hash，但 dirty set 已含該成員、SADD 是 no-op，隨後的 SREM 把 dirty 標記抹掉——
 * 新進度不會再被 flush，hash 的 TTL 到期後讀回 DB 的舊值（進度回退）。新流程「讀 hash（記下 updatedAt）→
 * upsert → Lua compare-and-remove」：updatedAt 沒變才 SREM，期間有新 update 就保留成員；upsert 失敗或 JVM
 * 在 upsert 與 CAS 之間終止時成員本來就還在（at-least-once）。</p>
 *
 * <p>交錯以 {@link HookingMapper} 重現：它把 {@code upsert} 委派給真實 mapper（真實 Postgres），並在
 * upsert 之後執行 hook。upsert 一定發生在「讀完 hash 之後、flush 結束之前」，hook 在這個位置插入一次新的
 * update。job 以建構子手動組裝（真實 Redis、真實 {@link ArticleFacade}、{@link HookingMapper}）——
 * MyBatis mapper 是 JDK proxy，無法用 Mockito spy 呼叫真實方法。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@DisplayName("ReadingProgressFlushJob 整合測試（RACE-13 交錯、PERF-31、孤兒保護）")
class ReadingProgressFlushJobIT extends ReadingCleanupITSupport {

    @Autowired private ReadingProgressService progressService;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ArticleFacade articleFacade;
    @Autowired private ReadingProgressMapper realMapper;

    private HookingMapper hookingMapper;
    private ReadingProgressFlushJob job;

    /**
     * 委派給真實 mapper 的 {@link ReadingProgressMapper}，可在 upsert 之後插入 hook，或讓下一次 upsert 失敗。
     */
    private final class HookingMapper implements ReadingProgressMapper {

        /** 下一次 upsert 開始前（flush 已讀完 hash）執行的 hook（一次性） */
        private Runnable beforeNextUpsert;

        /** 下一次 upsert 完成後執行的 hook（一次性） */
        private Runnable afterNextUpsert;

        /** 為 true 時下一次 upsert 直接拋例外（一次性） */
        private boolean failNextUpsert;

        @Override
        public int upsert(UUID userUuid, UUID articleUuid, BigDecimal progress, String lastHeading,
                          LocalDateTime updatedAt) {
            if (failNextUpsert) {
                failNextUpsert = false;
                throw new IllegalStateException("db down");
            }
            if (beforeNextUpsert != null) {
                Runnable hook = beforeNextUpsert;
                beforeNextUpsert = null;
                hook.run();
            }
            int affected = realMapper.upsert(userUuid, articleUuid, progress, lastHeading, updatedAt);
            if (afterNextUpsert != null) {
                Runnable hook = afterNextUpsert;
                afterNextUpsert = null;
                hook.run();
            }
            return affected;
        }
    }

    @BeforeEach
    void setUpJobAndCleanRedis() {
        hookingMapper = new HookingMapper();
        job = new ReadingProgressFlushJob(redis, articleFacade, hookingMapper);
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

    /** 模擬 JVM 被終止：Error 不會被 job 的 catch (Exception) 吞掉，會直接穿出 flush()。 */
    private static final class SimulatedJvmAbort extends Error {
        private SimulatedJvmAbort() {
            super("simulated JVM abort");
        }
    }

    private String hashKeyOf(UUID userUuid, UUID articleUuid) {
        return RedisKeyConstant.READING_PROGRESS_PREFIX + memberOf(userUuid, articleUuid);
    }

    private String memberOf(UUID userUuid, UUID articleUuid) {
        return userUuid + ":" + articleUuid;
    }

    private boolean isDirty(UUID userUuid, UUID articleUuid) {
        return Boolean.TRUE.equals(redis.opsForSet()
                .isMember(RedisKeyConstant.READING_DIRTY_KEY, memberOf(userUuid, articleUuid)));
    }

    private BigDecimal dbProgress(UUID userUuid, UUID articleUuid) {
        return jdbc.query("SELECT progress FROM user_reading_progress WHERE user_uuid = ? AND article_uuid = ?",
                rs -> rs.next() ? rs.getBigDecimal(1) : null, userUuid, articleUuid);
    }

    private LocalDateTime dbUpdatedAt(UUID userUuid, UUID articleUuid) {
        return jdbc.query("SELECT updated_at FROM user_reading_progress WHERE user_uuid = ? AND article_uuid = ?",
                rs -> rs.next() ? rs.getTimestamp(1).toLocalDateTime() : null, userUuid, articleUuid);
    }

    private static LocalDateTime at(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    private BigDecimal hashProgress(UUID userUuid, UUID articleUuid) {
        Object v = redis.opsForHash().get(
                RedisKeyConstant.READING_PROGRESS_PREFIX + memberOf(userUuid, articleUuid), "progress");
        return v == null ? null : new BigDecimal(v.toString());
    }

    @Test
    @DisplayName("RACE-13：flush 讀完 hash 之後才發生的新 update 不會被抹掉——flush 結束後 dirty 仍含該成員，下一輪 flush 後 DB 為新值")
    void flush_updateArrivesAfterHashRead_isNotLostAndFlushedNextRound() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        UUID articleUuid = article.getUuid();
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.30"), "first");

        // upsert 完成時 flush 已讀完 hash（舊值 0.30）；此刻插入一次新的 update
        hookingMapper.afterNextUpsert = () ->
                progressService.update(USER_UUID, articleUuid, new BigDecimal("0.60"), "second");

        job.flush();

        assertThat(dbProgress(USER_UUID, articleUuid)).as("第一輪 flush 寫入的是讀 hash 當下的值")
                .isEqualByComparingTo("0.30");
        assertThat(hashProgress(USER_UUID, articleUuid)).as("新 update 已寫進 hash").isEqualByComparingTo("0.60");
        assertThat(isDirty(USER_UUID, articleUuid))
                .as("新 update 的 dirty 標記不得被第一輪 flush 抹掉").isTrue();

        job.flush();

        assertThat(dbProgress(USER_UUID, articleUuid)).as("下一輪 flush 後 DB 為新值").isEqualByComparingTo("0.60");
        assertThat(isDirty(USER_UUID, articleUuid)).isFalse();
    }

    @Test
    @DisplayName("upsert 失敗：成員仍在 dirty set（從未被移除）、hash 保留，下一輪成功寫入")
    void flush_upsertFails_keepsDirtyAndRetriesNextRound() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        UUID articleUuid = article.getUuid();
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.40"), "h");
        hookingMapper.failNextUpsert = true;

        job.flush();

        assertThat(isDirty(USER_UUID, articleUuid)).as("失敗後成員仍在 dirty").isTrue();
        assertThat(hashProgress(USER_UUID, articleUuid)).isEqualByComparingTo("0.40");
        assertThat(dbProgress(USER_UUID, articleUuid)).isNull();

        job.flush();

        assertThat(dbProgress(USER_UUID, articleUuid)).isEqualByComparingTo("0.40");
        assertThat(isDirty(USER_UUID, articleUuid)).isFalse();
    }

    @Test
    @DisplayName("同一毫秒、進度不同的兩次 update：CAS 比對內容（progress、lastHeading、updatedAt），新進度的 dirty 標記不得被抹掉")
    void flush_updateInSameMillisecondWithDifferentProgress_isNotLost() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        UUID articleUuid = article.getUuid();
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.30"), "first");
        String sameUpdatedAt = redis.opsForHash().get(hashKeyOf(USER_UUID, articleUuid), "updatedAt").toString();
        hookingMapper.afterNextUpsert = () -> {
            redis.opsForHash().putAll(hashKeyOf(USER_UUID, articleUuid),
                    Map.of("progress", "0.60", "lastHeading", "second", "updatedAt", sameUpdatedAt));
            redis.opsForSet().add(RedisKeyConstant.READING_DIRTY_KEY, memberOf(USER_UUID, articleUuid));
        };

        job.flush();

        assertThat(dbProgress(USER_UUID, articleUuid)).isEqualByComparingTo("0.30");
        assertThat(isDirty(USER_UUID, articleUuid)).as("updatedAt 相同但內容不同，不得 SREM").isTrue();

        job.flush();

        assertThat(dbProgress(USER_UUID, articleUuid)).as("下一輪寫入新進度").isEqualByComparingTo("0.60");
        assertThat(isDirty(USER_UUID, articleUuid)).isFalse();
    }

    @Test
    @DisplayName("同一毫秒、progress 相同但 lastHeading 不同：同樣不得抹掉 dirty 標記")
    void flush_updateInSameMillisecondWithDifferentHeading_isNotLost() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        UUID articleUuid = article.getUuid();
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.30"), "first");
        String sameUpdatedAt = redis.opsForHash().get(hashKeyOf(USER_UUID, articleUuid), "updatedAt").toString();
        hookingMapper.afterNextUpsert = () -> {
            redis.opsForHash().putAll(hashKeyOf(USER_UUID, articleUuid),
                    Map.of("progress", "0.30", "lastHeading", "second", "updatedAt", sameUpdatedAt));
            redis.opsForSet().add(RedisKeyConstant.READING_DIRTY_KEY, memberOf(USER_UUID, articleUuid));
        };

        job.flush();

        assertThat(isDirty(USER_UUID, articleUuid)).isTrue();
    }

    @Test
    @DisplayName("讀到空 hash 之後、compare-and-remove 之前才有新的 update：新寫入的成員仍留在 dirty set，下一輪寫入 DB")
    void flush_updateArrivesAfterEmptyHashRead_keepsMember() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        UUID articleUuid = article.getUuid();
        redis.opsForSet().add(RedisKeyConstant.READING_DIRTY_KEY, memberOf(USER_UUID, articleUuid));
        StringRedisTemplate spyRedis = spy(redis);
        doAnswer(invocation -> {
            progressService.update(USER_UUID, articleUuid, new BigDecimal("0.40"), "x");
            return invocation.callRealMethod();
        }).when(spyRedis).execute(any(RedisScript.class), anyList(), any(Object[].class));

        new ReadingProgressFlushJob(spyRedis, articleFacade, hookingMapper).flush();

        assertThat(isDirty(USER_UUID, articleUuid)).as("空 hash 讀取後才有的新 update，標記不得被抹掉").isTrue();
        assertThat(dbProgress(USER_UUID, articleUuid)).isNull();

        job.flush();

        assertThat(dbProgress(USER_UUID, articleUuid)).isEqualByComparingTo("0.40");
        assertThat(isDirty(USER_UUID, articleUuid)).isFalse();
    }

    @Test
    @DisplayName("upsert 之後、compare-and-remove 之前 JVM 被終止：成員仍在 dirty set，下一輪 flush 會重新寫入（at-least-once）")
    void flush_jvmAbortsAfterUpsertBeforeCas_memberStaysAndNextRoundWrites() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        UUID articleUuid = article.getUuid();
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.30"), "first");
        hookingMapper.afterNextUpsert = () -> {
            throw new SimulatedJvmAbort();
        };

        assertThatThrownBy(() -> job.flush()).isInstanceOf(SimulatedJvmAbort.class);

        assertThat(isDirty(USER_UUID, articleUuid)).as("CAS 沒有機會執行，成員必須還在").isTrue();
        assertThat(hashProgress(USER_UUID, articleUuid)).isEqualByComparingTo("0.30");
        jdbc.update("UPDATE user_reading_progress SET progress = 0 WHERE user_uuid = ? AND article_uuid = ?",
                USER_UUID, articleUuid);

        job.flush();

        assertThat(dbProgress(USER_UUID, articleUuid)).as("下一輪 flush 重新寫入").isEqualByComparingTo("0.30");
        assertThat(isDirty(USER_UUID, articleUuid)).isFalse();
    }

    @Test
    @DisplayName("hash 在 upsert 之後過期（CAS 時 hash 不存在）：保留成員，下一輪讀到空 hash 才移除")
    void flush_hashExpiresAfterUpsert_keepsMemberUntilNextRound() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        UUID articleUuid = article.getUuid();
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.30"), "first");
        hookingMapper.afterNextUpsert = () -> redis.delete(
                RedisKeyConstant.READING_PROGRESS_PREFIX + memberOf(USER_UUID, articleUuid));

        job.flush();

        assertThat(dbProgress(USER_UUID, articleUuid)).isEqualByComparingTo("0.30");
        assertThat(isDirty(USER_UUID, articleUuid)).as("hash 已不存在，CAS 不相等，保留成員").isTrue();

        job.flush();

        assertThat(isDirty(USER_UUID, articleUuid)).as("下一輪讀到空 hash，成員移除").isFalse();
    }

    @Test
    @DisplayName("hash 已過期且期間沒有新 update：成員被移除")
    void flush_hashAlreadyExpired_removesMember() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        UUID articleUuid = article.getUuid();
        redis.opsForSet().add(RedisKeyConstant.READING_DIRTY_KEY, memberOf(USER_UUID, articleUuid));

        job.flush();

        assertThat(isDirty(USER_UUID, articleUuid)).isFalse();
        assertThat(countProgress(articleUuid)).isZero();
    }

    @Test
    @DisplayName("正常情境：dirty 的進度寫入 DB（user_uuid／article_uuid），成員移出 dirty，hash 仍保留供讀取")
    void flush_normalEntry_persistsAndClearsDirty() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        UUID articleUuid = article.getUuid();
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.50"), "mid");

        job.flush();

        assertThat(dbProgress(USER_UUID, articleUuid)).isEqualByComparingTo("0.50");
        assertThat(isDirty(USER_UUID, articleUuid)).isFalse();
        assertThat(hashProgress(USER_UUID, articleUuid)).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("文章已被刪除：不寫出孤兒列（進度表已無 FK）、刪 hash、成員移出 dirty")
    void flush_articleDeleted_writesNoOrphanRowAndCleansRedis() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        UUID articleUuid = article.getUuid();
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.50"), "mid");
        articleRepo.deleteById(article.getId());

        job.flush();

        assertThat(countProgress(articleUuid)).as("不得寫出孤兒列").isZero();
        assertThat(hashProgress(USER_UUID, articleUuid)).isNull();
        assertThat(isDirty(USER_UUID, articleUuid)).isFalse();
    }

    @Test
    @DisplayName("無法解析的舊格式成員（{Long}:{uuid}）：warn 並從 dirty set 移除、不重試；同輪其他成員照常寫入")
    void flush_legacyFormatMember_isRemovedAndOthersFlushed() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        UUID articleUuid = article.getUuid();
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.20"), null);
        String legacy = "1:" + UUID.randomUUID();
        redis.opsForSet().add(RedisKeyConstant.READING_DIRTY_KEY, legacy, "garbage");

        job.flush();

        assertThat(redis.opsForSet().isMember(RedisKeyConstant.READING_DIRTY_KEY, legacy)).isFalse();
        assertThat(redis.opsForSet().isMember(RedisKeyConstant.READING_DIRTY_KEY, "garbage")).isFalse();
        assertThat(dbProgress(USER_UUID, articleUuid)).isEqualByComparingTo("0.20");
    }

    @Test
    @DisplayName("兩個 flusher 交錯：F1 讀舊 hash 後停頓，期間新 update 並由 F2 flush 清標記；F1 再 upsert 舊值時被 DB 單調守衛擋下，DB 不回退")
    void flush_twoFlushersInterleaved_staleUpsertDoesNotRegressDb() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        UUID articleUuid = article.getUuid();
        long t1 = System.currentTimeMillis() - 10_000;
        ReadingProgressTestAccess.update(progressService, USER_UUID, articleUuid, new BigDecimal("0.30"), "first", t1);
        ReadingProgressFlushJob otherFlusher = new ReadingProgressFlushJob(redis, articleFacade, realMapper);
        hookingMapper.beforeNextUpsert = () -> {
            ReadingProgressTestAccess.update(progressService, USER_UUID, articleUuid, new BigDecimal("0.60"), "second", t1 + 1_000);
            otherFlusher.flush();
        };

        job.flush();
        job.flush();

        assertThat(dbProgress(USER_UUID, articleUuid)).as("DB 應為較新的 0.60，不被 F1 的舊值蓋掉")
                .isEqualByComparingTo("0.60");
        assertThat(dbUpdatedAt(USER_UUID, articleUuid)).isEqualTo(at(t1 + 1_000));
        assertThat(isDirty(USER_UUID, articleUuid)).isFalse();
    }

    @Test
    @DisplayName("完讀路徑與 flush 交錯：flush 讀到 0.30 後，完讀 0.97 寫入 DB；舊 flush 的 upsert 被守衛擋下，快取保留完讀值，下一輪 flush 後 DB 仍為 0.97")
    void flush_completionInterleavedWithFlush_staleFlushDoesNotOverwriteCompletion() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        UUID articleUuid = article.getUuid();
        long t1 = System.currentTimeMillis() - 10_000;
        ReadingProgressTestAccess.update(progressService, USER_UUID, articleUuid, new BigDecimal("0.30"), "first", t1);
        hookingMapper.beforeNextUpsert = () ->
                ReadingProgressTestAccess.update(progressService, USER_UUID, articleUuid, new BigDecimal("0.97"), "end", t1 + 1_000);

        job.flush();

        assertThat(dbProgress(USER_UUID, articleUuid)).as("完讀進度 0.97 不得被舊的 0.30 蓋掉")
                .isEqualByComparingTo("0.97");
        assertThat(hashProgress(USER_UUID, articleUuid)).as("快取是完讀值").isEqualByComparingTo("0.97");
        assertThat(isDirty(USER_UUID, articleUuid)).as("hash 內容已變，CAS 保留成員交給下一輪").isTrue();

        job.flush();

        assertThat(dbProgress(USER_UUID, articleUuid)).isEqualByComparingTo("0.97");
        assertThat(isDirty(USER_UUID, articleUuid)).as("下一輪讀到完讀值並 CAS 移除成員").isFalse();
    }

    @Test
    @DisplayName("壞資料（hash 缺 progress，poison pill）：成員被移除、hash 被刪、不 upsert；同輪其他成員照常 flush")
    void flush_hashWithoutProgress_isDiscardedAndOthersFlushed() {
        Article bad = createArticle(ArticleStatus.PUBLISHED);
        Article good = createArticle(ArticleStatus.PUBLISHED);
        UUID badUuid = bad.getUuid();
        UUID goodUuid = good.getUuid();
        redis.opsForHash().putAll(hashKeyOf(USER_UUID, badUuid),
                Map.of("lastHeading", "h", "updatedAt", String.valueOf(System.currentTimeMillis())));
        redis.opsForSet().add(RedisKeyConstant.READING_DIRTY_KEY, memberOf(USER_UUID, badUuid));
        progressService.update(USER_UUID, goodUuid, new BigDecimal("0.20"), null);

        job.flush();

        assertThat(isDirty(USER_UUID, badUuid)).as("壞資料成員被移除").isFalse();
        assertThat(redis.hasKey(hashKeyOf(USER_UUID, badUuid))).as("壞資料 hash 被刪").isFalse();
        assertThat(countProgress(badUuid)).as("不得 upsert").isZero();
        assertThat(dbProgress(USER_UUID, goodUuid)).as("其他成員照常 flush").isEqualByComparingTo("0.20");
        assertThat(isDirty(USER_UUID, goodUuid)).isFalse();
    }

    @Test
    @DisplayName("壞資料（hash 缺 updatedAt）：同樣被丟棄，不 upsert、不拋例外")
    void flush_hashWithoutUpdatedAt_isDiscarded() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        UUID articleUuid = article.getUuid();
        redis.opsForHash().putAll(hashKeyOf(USER_UUID, articleUuid), Map.of("progress", "0.40", "lastHeading", ""));
        redis.opsForSet().add(RedisKeyConstant.READING_DIRTY_KEY, memberOf(USER_UUID, articleUuid));

        job.flush();

        assertThat(isDirty(USER_UUID, articleUuid)).isFalse();
        assertThat(redis.hasKey(hashKeyOf(USER_UUID, articleUuid))).isFalse();
        assertThat(countProgress(articleUuid)).isZero();
    }

    @Test
    @DisplayName("壞資料丟棄是 compare-and-discard：讀到壞 hash 之後、丟棄之前有新的合法 update → 成員與新 hash 都保留，下一輪寫入 DB")
    void flush_badHashReplacedByUpdateBeforeDiscard_keepsMemberAndHash() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        UUID articleUuid = article.getUuid();
        redis.opsForHash().putAll(hashKeyOf(USER_UUID, articleUuid), Map.of("lastHeading", "h", "updatedAt", "1"));
        redis.opsForSet().add(RedisKeyConstant.READING_DIRTY_KEY, memberOf(USER_UUID, articleUuid));
        StringRedisTemplate spyRedis = spy(redis);
        doAnswer(invocation -> {
            progressService.update(USER_UUID, articleUuid, new BigDecimal("0.40"), "x");
            return invocation.callRealMethod();
        }).when(spyRedis).execute(any(RedisScript.class), anyList(), any(Object[].class));

        new ReadingProgressFlushJob(spyRedis, articleFacade, hookingMapper).flush();

        assertThat(isDirty(USER_UUID, articleUuid)).as("新 update 的標記不得被丟棄動作抹掉").isTrue();
        assertThat(hashProgress(USER_UUID, articleUuid)).as("新 hash 不得被 DEL").isEqualByComparingTo("0.40");

        job.flush();

        assertThat(dbProgress(USER_UUID, articleUuid)).isEqualByComparingTo("0.40");
        assertThat(isDirty(USER_UUID, articleUuid)).isFalse();
    }
}
