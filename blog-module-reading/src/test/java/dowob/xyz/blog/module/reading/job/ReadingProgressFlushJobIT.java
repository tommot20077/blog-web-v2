package dowob.xyz.blog.module.reading.job;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.reading.mapper.ReadingProgressMapper;
import dowob.xyz.blog.module.reading.service.ReadingProgressService;
import dowob.xyz.blog.module.reading.support.ReadingCleanupITSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReadingProgressFlushJob} 整合測試（真實 Redis、真實 Postgres；進度的更新走真實 {@link ReadingProgressService}）。
 *
 * <p><b>RACE-13／DATA-08</b>：舊流程是「讀 hash → upsert → 最後才 SREM」。讀 hash 之後、SREM 之前若有新的
 * update，新值寫進 hash，但 dirty set 已含該成員、SADD 是 no-op，隨後的 SREM 把 dirty 標記抹掉——
 * 新進度不會再被 flush，hash 的 TTL 到期後讀回 DB 的舊值（進度回退）。新流程「先 SREM 該成員，再讀 hash 與
 * upsert」，之後任何新的 update 都會重新 SADD，下一輪必被 flush。</p>
 *
 * <p>交錯以 {@link HookingMapper} 重現：它把 {@code upsert} 委派給真實 mapper（真實 Postgres），並在
 * upsert 之後執行 hook。upsert 一定發生在「讀完 hash 之後、flush 結束之前」，hook 在這個位置插入一次新的
 * update。job 以建構子手動組裝（真實 Redis、真實 {@link ArticleFacade}、{@link HookingMapper}）——
 * MyBatis mapper 是 JDK proxy，無法用 Mockito spy 呼叫真實方法。</p>
 *
 * @author Yuan
 * @version 1.0
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

        /** 下一次 upsert 完成後執行的 hook（一次性） */
        private Runnable afterNextUpsert;

        /** 為 true 時下一次 upsert 直接拋例外（一次性） */
        private boolean failNextUpsert;

        @Override
        public int upsert(UUID userUuid, UUID articleUuid, BigDecimal progress, String lastHeading) {
            if (failNextUpsert) {
                failNextUpsert = false;
                throw new IllegalStateException("db down");
            }
            int affected = realMapper.upsert(userUuid, articleUuid, progress, lastHeading);
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
    @DisplayName("upsert 失敗：成員被補回 dirty set、hash 保留，下一輪成功寫入")
    void flush_upsertFails_restoresDirtyAndRetriesNextRound() {
        Article article = createArticle(ArticleStatus.PUBLISHED);
        UUID articleUuid = article.getUuid();
        progressService.update(USER_UUID, articleUuid, new BigDecimal("0.40"), "h");
        hookingMapper.failNextUpsert = true;

        job.flush();

        assertThat(isDirty(USER_UUID, articleUuid)).as("失敗後成員補回 dirty").isTrue();
        assertThat(hashProgress(USER_UUID, articleUuid)).isEqualByComparingTo("0.40");
        assertThat(dbProgress(USER_UUID, articleUuid)).isNull();

        job.flush();

        assertThat(dbProgress(USER_UUID, articleUuid)).isEqualByComparingTo("0.40");
        assertThat(isDirty(USER_UUID, articleUuid)).isFalse();
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
}
