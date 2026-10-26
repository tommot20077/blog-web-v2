package dowob.xyz.blog.module.article.job;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob.Result;
import dowob.xyz.blog.module.article.support.ArticleSeriesITSupport;
import dowob.xyz.blog.module.article.support.WarnLogCapture;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleSeriesOrphanReconciliationJob} 整合測試（真實 Postgres、Redis；系列存在性查詢以 mock 的
 * {@code SeriesFacade} 回答——article 不依賴 series 模組）。
 *
 * <p>孤兒＝文章的 {@code series_uuid} 指向已不存在的系列（V32 起沒有 FK，直接以 SQL 寫入）。
 * 處理方式是<b>把歸屬設為 NULL，絕不刪除文章</b>。測試資料的規模刻意維持在框架中止門檻之下，
 * 並另有案例確認「全部都是孤兒」時 ABORTED、零變更。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleSeriesOrphanReconciliationJob 整合測試")
class ArticleSeriesOrphanReconciliationJobIT extends ArticleSeriesITSupport {

    @Autowired private ArticleSeriesOrphanReconciliationJob job;
    @Autowired private StringRedisTemplate redis;
    /** 測試期間掛到全域 registry 的 registry（job 的指標走 Metrics.globalRegistry） */
    private SimpleMeterRegistry registry;

    @BeforeEach
    void clearRedisState() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        redis.delete(RedisKeyConstant.getReconciliationLockKey("article", "articles"));
        redis.delete(RedisKeyConstant.getReconciliationCursorKey("article", "articles"));
    }

    @AfterEach
    void removeRegistry() {
        Metrics.removeRegistry(registry);
        registry.close();
    }

    private double removedCounter() {
        var counter = registry.find("reconciliation.orphans_removed")
                .tag("module", "article").tag("table", "articles").counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** 讓 SeriesFacade 只認得 {@code existing} 內的系列（其餘視為已被刪除）。 */
    private void seriesExist(UUID... existing) {
        Set<UUID> known = Set.of(existing);
        when(seriesFacade.filterExistingUuids(any())).thenAnswer(inv -> {
            Collection<UUID> asked = inv.getArgument(0);
            Set<UUID> result = new HashSet<>(asked);
            result.retainAll(known);
            return result;
        });
    }

    @Test
    @DisplayName("孤兒系列的成員文章：series_uuid 與 series_position 設為 NULL、文章全部保留；仍存在的系列不受影響")
    void run_clearsOrphanMembershipButNeverDeletesArticles() {
        UUID alive1 = UUID.randomUUID();
        UUID alive2 = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        UUID aliveMember1 = insertArticle(alive1, 1, "PUBLISHED");
        UUID aliveMember2 = insertArticle(alive2, 4, "PUBLISHED");
        UUID orphan1 = insertArticle(gone, 1, "PUBLISHED");
        UUID orphan2 = insertArticle(gone, 2, "DRAFT");
        UUID loner = insertArticle(null, null, "PUBLISHED");
        seriesExist(alive1, alive2);
        double before = removedCounter();

        Result result = job.run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(seriesUuidOf(orphan1)).isNull();
        assertThat(positionOf(orphan1)).isNull();
        assertThat(seriesUuidOf(orphan2)).isNull();
        assertThat(positionOf(orphan2)).isNull();
        assertThat(seriesUuidOf(aliveMember1)).isEqualTo(alive1);
        assertThat(positionOf(aliveMember2)).isEqualTo(4);
        assertThat(seriesUuidOf(loner)).isNull();
        assertThat(countArticles()).as("絕不可刪除文章").isEqualTo(5);
        assertThat(removedCounter() - before).as("orphans_removed 計入清空的文章列數").isEqualTo(2.0);
    }

    @Test
    @DisplayName("校正清空歸屬遞增 version（與 consumer 一致），不更新 updated_at（與改造前相同）")
    void run_bumpsVersionButNotUpdatedAt() {
        UUID alive1 = UUID.randomUUID();
        UUID alive2 = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        insertArticle(alive1, 1, "PUBLISHED");
        insertArticle(alive2, 1, "PUBLISHED");
        LocalDateTime fixed = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        UUID orphan = insertArticleWithAudit(gone, 2, 6, fixed);
        UUID aliveMember = insertArticleWithAudit(alive1, 3, 8, fixed);
        seriesExist(alive1, alive2);

        job.run();

        assertThat(seriesUuidOf(orphan)).isNull();
        assertThat(versionOf(orphan)).isEqualTo(7);
        assertThat(updatedAtOf(orphan)).isEqualTo(fixed);
        assertThat(versionOf(aliveMember)).as("仍存在系列的成員不受影響").isEqualTo(8);
    }

    @Test
    @DisplayName("清空前以 WARN 逐篇記錄 articleUuid、原 series_uuid、原 series_position，供人工復原")
    void run_warnsEachAffectedArticleBeforeClearing() {
        UUID alive1 = UUID.randomUUID();
        UUID alive2 = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        insertArticle(alive1, 1, "PUBLISHED");
        insertArticle(alive2, 1, "PUBLISHED");
        UUID orphan1 = insertArticle(gone, 3, "PUBLISHED");
        UUID orphan2 = insertArticle(gone, 7, "DRAFT");
        seriesExist(alive1, alive2);

        List<String> warns;
        try (WarnLogCapture capture = new WarnLogCapture(ArticleSeriesOrphanReconciliationJob.class)) {
            job.run();
            warns = capture.warnings();
        }

        assertThat(warns).anySatisfy(m -> assertThat(m)
                .contains(orphan1.toString()).contains(gone.toString()).contains("seriesPosition=3"));
        assertThat(warns).anySatisfy(m -> assertThat(m)
                .contains(orphan2.toString()).contains(gone.toString()).contains("seriesPosition=7"));
        assertThat(warns).noneSatisfy(m -> assertThat(m).contains(alive1.toString()));
    }

    @Test
    @DisplayName("單次處理的孤兒系列上限為 20：超過即 ABORTED、零變更（誤判時不致一次清光大量歸屬）")
    void run_moreThanTwentyOrphanSeries_abortsWithoutChanging() {
        UUID alive1 = UUID.randomUUID();
        UUID alive2 = UUID.randomUUID();
        insertArticle(alive1, 1, "PUBLISHED");
        insertArticle(alive2, 1, "PUBLISHED");
        List<UUID> orphans = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) {
            UUID gone = UUID.randomUUID();
            orphans.add(insertArticle(gone, i + 1, "PUBLISHED"));
        }
        seriesExist(alive1, alive2);

        Result result = job.run();

        assertThat(result).isEqualTo(Result.ABORTED);
        assertThat(orphans).allSatisfy(a -> assertThat(seriesUuidOf(a)).isNotNull());
    }

    @Test
    @DisplayName("沒有孤兒：COMPLETED 且零變更")
    void run_noOrphans_completesWithoutChanges() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        UUID m1 = insertArticle(s1, 1, "PUBLISHED");
        UUID m2 = insertArticle(s2, 2, "PUBLISHED");
        seriesExist(s1, s2);

        Result result = job.run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(seriesUuidOf(m1)).isEqualTo(s1);
        assertThat(seriesUuidOf(m2)).isEqualTo(s2);
    }

    @Test
    @DisplayName("重複執行：第二次為 no-op（冪等）")
    void run_twice_isIdempotent() {
        UUID alive1 = UUID.randomUUID();
        UUID alive2 = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        insertArticle(alive1, 1, "PUBLISHED");
        insertArticle(alive2, 1, "PUBLISHED");
        UUID orphan = insertArticle(gone, 3, "PUBLISHED");
        seriesExist(alive1, alive2);

        job.run();
        redis.delete(RedisKeyConstant.getReconciliationCursorKey("article", "articles"));
        Result second = job.run();

        assertThat(second).isEqualTo(Result.COMPLETED);
        assertThat(seriesUuidOf(orphan)).isNull();
        assertThat(countArticles()).isEqualTo(3);
    }

    @Test
    @DisplayName("被參照的系列全部都是孤兒：ABORTED、零變更（框架保護未被子類別繞過）")
    void run_everyReferencedSeriesIsOrphan_abortsWithoutChanging() {
        UUID gone1 = UUID.randomUUID();
        UUID gone2 = UUID.randomUUID();
        UUID m1 = insertArticle(gone1, 1, "PUBLISHED");
        UUID m2 = insertArticle(gone2, 1, "PUBLISHED");
        seriesExist();
        double before = removedCounter();

        Result result = job.run();

        assertThat(result).isEqualTo(Result.ABORTED);
        assertThat(seriesUuidOf(m1)).isEqualTo(gone1);
        assertThat(seriesUuidOf(m2)).isEqualTo(gone2);
        assertThat(removedCounter() - before).isZero();
    }

    @Test
    @DisplayName("series 查詢拋例外：FAILED、零變更（不把查不到當成已刪除）")
    void run_filterExistingThrows_failsWithoutChanging() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        UUID m1 = insertArticle(s1, 1, "PUBLISHED");
        UUID m2 = insertArticle(s2, 1, "PUBLISHED");
        UUID orphan = insertArticle(gone, 1, "PUBLISHED");
        doThrow(new IllegalStateException("series service down")).when(seriesFacade).filterExistingUuids(any());

        Result result = job.run();

        assertThat(result).isEqualTo(Result.FAILED);
        assertThat(seriesUuidOf(orphan)).as("孤兒也不得被動").isEqualTo(gone);
        assertThat(seriesUuidOf(m1)).isEqualTo(s1);
        assertThat(seriesUuidOf(m2)).isEqualTo(s2);
    }
}
