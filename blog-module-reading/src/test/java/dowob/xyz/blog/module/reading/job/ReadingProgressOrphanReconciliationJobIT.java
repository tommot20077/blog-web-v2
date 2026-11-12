package dowob.xyz.blog.module.reading.job;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob.Result;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.reading.support.ReadingCleanupITSupport;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * {@link ReadingProgressOrphanReconciliationJob} 整合測試（真實 Postgres、Redis；owner 存在性查詢走真實 ArticleFacade）。
 *
 * <p>孤兒閱讀進度直接以 SQL 寫入（V27 起 user_reading_progress 沒有指向 articles 的 FK）。
 * 測試資料的規模刻意維持在框架中止門檻之下，並另有案例確認「全部都是孤兒」時 ABORTED、零刪除。
 * 此表被校正時，{@code user_article_likes} 不得被牽動。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ReadingProgressOrphanReconciliationJob 整合測試")
class ReadingProgressOrphanReconciliationJobIT extends ReadingCleanupITSupport {

    @Autowired private ReadingProgressOrphanReconciliationJob job;
    @Autowired private StringRedisTemplate redis;
    /** 測試期間掛到全域 registry 的 registry（job 的指標走 Metrics.globalRegistry） */
    private SimpleMeterRegistry registry;
    /** Spy：預設走真實實作，個別案例改為拋例外以模擬 owner 不可用 */
    @MockitoSpyBean private ArticleFacade articleFacade;

    @BeforeEach
    void clearRedisState() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        redis.delete(RedisKeyConstant.getReconciliationLockKey("reading", "user_reading_progress"));
        redis.delete(RedisKeyConstant.getReconciliationCursorKey("reading", "user_reading_progress"));
    }

    @AfterEach
    void removeRegistry() {
        Metrics.removeRegistry(registry);
        registry.close();
    }

    private double removedCounter() {
        var counter = registry.find("reconciliation.orphans_removed")
                .tag("module", "reading").tag("table", "user_reading_progress").counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("孤兒閱讀進度被刪除；非孤兒（含草稿文章）保留；按讚表不受牽動")
    void run_removesOrphansAndKeepsExistingArticlesProgress() {
        Article published = createArticle(ArticleStatus.PUBLISHED);
        Article draft = createArticle(ArticleStatus.DRAFT);
        Article another = createArticle(ArticleStatus.PUBLISHED);
        insertProgress(USER_UUID, published.getUuid());
        insertProgress(USER_UUID, draft.getUuid());
        insertProgress(USER_UUID, another.getUuid());
        UUID orphanUuid = UUID.randomUUID();
        insertProgress(USER_UUID, orphanUuid);
        insertProgress(OTHER_USER_UUID, orphanUuid);
        insertLike(USER_UUID, orphanUuid);
        double before = removedCounter();

        Result result = job.run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(countProgress(orphanUuid)).as("孤兒閱讀進度被刪除").isZero();
        assertThat(countProgress(published.getUuid())).isEqualTo(1);
        assertThat(countProgress(draft.getUuid())).as("草稿文章仍存在，不可當孤兒").isEqualTo(1);
        assertThat(countProgress(another.getUuid())).isEqualTo(1);
        assertThat(countLikes(orphanUuid)).as("按讚由另一個 job 負責").isEqualTo(1);
        assertThat(removedCounter() - before).as("orphans_removed 計入刪除的列數").isEqualTo(2.0);
    }

    @Test
    @DisplayName("沒有孤兒：COMPLETED 且零刪除")
    void run_noOrphans_completesWithoutDeleting() {
        Article a = createArticle(ArticleStatus.PUBLISHED);
        Article b = createArticle(ArticleStatus.PUBLISHED);
        insertProgress(USER_UUID, a.getUuid());
        insertProgress(USER_UUID, b.getUuid());

        Result result = job.run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(countProgress(a.getUuid())).isEqualTo(1);
        assertThat(countProgress(b.getUuid())).isEqualTo(1);
    }

    @Test
    @DisplayName("被參照的 UUID 全部都是孤兒：ABORTED、零刪除（框架保護未被子類別繞過）")
    void run_everyReferencedArticleIsOrphan_abortsWithoutDeleting() {
        createArticle(ArticleStatus.PUBLISHED);
        UUID orphan1 = UUID.randomUUID();
        UUID orphan2 = UUID.randomUUID();
        insertProgress(USER_UUID, orphan1);
        insertProgress(USER_UUID, orphan2);
        double before = removedCounter();

        Result result = job.run();

        assertThat(result).isEqualTo(Result.ABORTED);
        assertThat(countProgress(orphan1)).isEqualTo(1);
        assertThat(countProgress(orphan2)).isEqualTo(1);
        assertThat(removedCounter() - before).isZero();
    }

    @Test
    @DisplayName("owner 查詢拋例外：FAILED、零刪除（不把查不到當成不存在）")
    void run_filterExistingThrows_failsWithoutDeleting() {
        Article a = createArticle(ArticleStatus.PUBLISHED);
        Article b = createArticle(ArticleStatus.PUBLISHED);
        insertProgress(USER_UUID, a.getUuid());
        insertProgress(USER_UUID, b.getUuid());
        UUID orphanUuid = UUID.randomUUID();
        insertProgress(USER_UUID, orphanUuid);
        doThrow(new IllegalStateException("article service down")).when(articleFacade).filterExistingUuids(any());

        Result result = job.run();

        assertThat(result).isEqualTo(Result.FAILED);
        assertThat(countProgress(orphanUuid)).as("孤兒也不得被刪").isEqualTo(1);
        assertThat(countProgress(a.getUuid())).isEqualTo(1);
        assertThat(countProgress(b.getUuid())).isEqualTo(1);
    }
}
