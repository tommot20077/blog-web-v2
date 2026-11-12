package dowob.xyz.blog.module.tag.job;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob.Result;
import dowob.xyz.blog.module.tag.support.TagCleanupITSupport;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleTagOrphanReconciliationJob} 整合測試（真實 Postgres、Redis；文章存在性以 Mock 的
 * {@code ArticleFacade} 模擬——owner 在 article 模組，本模組測試不載入它）。
 *
 * <p>孤兒關聯直接以 SQL 寫入（V33 起 {@code article_tags.article_id} 沒有指向 articles 的 FK）。
 * 測試資料規模維持在框架中止門檻之下，並另有案例確認「全部都是孤兒」時 ABORTED、零刪除。
 * 校正只刪 {@code article_tags} 的列，{@code tags} 與 {@code usage_count} 不動。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleTagOrphanReconciliationJob 整合測試")
class ArticleTagOrphanReconciliationJobIT extends TagCleanupITSupport {

    @Autowired private ArticleTagOrphanReconciliationJob job;
    @Autowired private StringRedisTemplate redis;

    /** 目前「存在」的文章 UUID（facade 只回傳輸入中屬於此集合者） */
    private final Set<UUID> existingArticles = new HashSet<>();
    /** 測試期間掛到全域 registry 的 registry（job 的指標走 Metrics.globalRegistry） */
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUpFacadeAndRedis() {
        existingArticles.clear();
        reset(articleFacade);
        when(articleFacade.filterExistingUuids(any())).thenAnswer(inv -> {
            Set<UUID> result = new HashSet<>(inv.<java.util.Collection<UUID>>getArgument(0));
            result.retainAll(existingArticles);
            return result;
        });
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        redis.delete(RedisKeyConstant.getReconciliationLockKey("tag", "article_tags"));
        redis.delete(RedisKeyConstant.getReconciliationCursorKey("tag", "article_tags"));
    }

    @AfterEach
    void removeRegistry() {
        Metrics.removeRegistry(registry);
        registry.close();
    }

    private double removedCounter() {
        var counter = registry.find("reconciliation.orphans_removed")
                .tag("module", "tag").tag("table", "article_tags").counter();
        return counter == null ? 0.0 : counter.count();
    }

    private UUID existingArticle() {
        UUID uuid = UUID.randomUUID();
        existingArticles.add(uuid);
        return uuid;
    }

    @Test
    @DisplayName("孤兒關聯被刪除；仍存在文章的關聯保留；tags 與 usage_count 不動")
    void run_removesOrphansAndKeepsExistingArticlesLinks() {
        UUID tagA = insertTag("java", 4);
        UUID tagB = insertTag("spring", 2);
        UUID alive1 = existingArticle();
        UUID alive2 = existingArticle();
        UUID orphan = UUID.randomUUID();
        insertArticleTag(alive1, tagA);
        insertArticleTag(alive1, tagB);
        insertArticleTag(alive2, tagA);
        insertArticleTag(orphan, tagA);
        insertArticleTag(orphan, tagB);
        double before = removedCounter();

        Result result = job.run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(countArticleTags(orphan)).as("孤兒關聯被刪除").isZero();
        assertThat(countArticleTags(alive1)).isEqualTo(2);
        assertThat(countArticleTags(alive2)).isEqualTo(1);
        assertThat(countTags()).isEqualTo(2);
        assertThat(usageCountOf(tagA)).as("usage_count 不動（DATA-01 另案）").isEqualTo(4);
        assertThat(usageCountOf(tagB)).isEqualTo(2);
        assertThat(removedCounter() - before).as("orphans_removed 計入刪除的列數").isEqualTo(2.0);
    }

    @Test
    @DisplayName("沒有孤兒：COMPLETED 且零刪除")
    void run_noOrphans_completesWithoutDeleting() {
        UUID tag = insertTag("java", 1);
        UUID a = existingArticle();
        UUID b = existingArticle();
        insertArticleTag(a, tag);
        insertArticleTag(b, tag);

        Result result = job.run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(countArticleTags(a)).isEqualTo(1);
        assertThat(countArticleTags(b)).isEqualTo(1);
    }

    @Test
    @DisplayName("被參照的文章 UUID 全部都是孤兒：ABORTED、零刪除（框架保護未被子類別繞過）")
    void run_everyReferencedArticleIsOrphan_abortsWithoutDeleting() {
        UUID tag = insertTag("java", 1);
        UUID orphan1 = UUID.randomUUID();
        UUID orphan2 = UUID.randomUUID();
        insertArticleTag(orphan1, tag);
        insertArticleTag(orphan2, tag);
        double before = removedCounter();

        Result result = job.run();

        assertThat(result).isEqualTo(Result.ABORTED);
        assertThat(countArticleTags(orphan1)).isEqualTo(1);
        assertThat(countArticleTags(orphan2)).isEqualTo(1);
        assertThat(removedCounter() - before).isZero();
    }

    @Test
    @DisplayName("owner 查詢拋例外：FAILED、零刪除（不把查不到當成不存在）")
    void run_filterExistingThrows_failsWithoutDeleting() {
        UUID tag = insertTag("java", 1);
        UUID a = existingArticle();
        UUID b = existingArticle();
        UUID orphan = UUID.randomUUID();
        insertArticleTag(a, tag);
        insertArticleTag(b, tag);
        insertArticleTag(orphan, tag);
        doThrow(new IllegalStateException("article service down")).when(articleFacade).filterExistingUuids(any());

        Result result = job.run();

        assertThat(result).isEqualTo(Result.FAILED);
        assertThat(countArticleTags(orphan)).as("孤兒也不得被刪").isEqualTo(1);
        assertThat(countArticleTags(a)).isEqualTo(1);
        assertThat(countArticleTags(b)).isEqualTo(1);
    }
}
