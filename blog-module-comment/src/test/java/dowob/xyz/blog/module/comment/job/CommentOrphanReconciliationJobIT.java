package dowob.xyz.blog.module.comment.job;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob.Result;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.support.CommentCleanupITSupport;
import dowob.xyz.blog.module.comment.support.SeededUsers;
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
 * {@link CommentOrphanReconciliationJob} 整合測試（真實 Postgres、Redis；owner 存在性查詢走真實 ArticleFacade）。
 *
 * <p>孤兒留言的製造方式見 {@link CommentCleanupITSupport#insertOrphanComment}：V25 起 comments 沒有指向
 * articles 的 FK，直接以 SQL 寫入即可。測試資料的規模刻意維持在框架中止門檻之下
 * （孤兒不超過掃描數的全部），並另有案例確認「全部都是孤兒」時 ABORTED、零刪除。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("CommentOrphanReconciliationJob 整合測試")
class CommentOrphanReconciliationJobIT extends CommentCleanupITSupport {

    @Autowired private CommentOrphanReconciliationJob job;
    @Autowired private StringRedisTemplate redis;
    /** 測試期間掛到全域 registry 的 registry（job 的指標走 Metrics.globalRegistry） */
    private SimpleMeterRegistry registry;
    /** Spy：預設走真實實作，個別案例改為拋例外以模擬 owner 不可用 */
    @MockitoSpyBean private ArticleFacade articleFacade;

    @BeforeEach
    void clearRedisState() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        redis.delete(RedisKeyConstant.getReconciliationLockKey("comment", "comments"));
        redis.delete(RedisKeyConstant.getReconciliationCursorKey("comment", "comments"));
    }

    @AfterEach
    void removeRegistry() {
        Metrics.removeRegistry(registry);
        registry.close();
    }

    private double removedCounter() {
        var counter = registry.find("reconciliation.orphans_removed")
                .tag("module", "comment").tag("table", "comments").counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("孤兒留言（含回覆與留言讚）被刪除；非孤兒（含草稿文章）保留")
    void run_removesOrphansAndKeepsExistingArticlesComments() {
        Article published = createArticle(ArticleStatus.PUBLISHED);
        Article draft = createArticle(ArticleStatus.DRAFT);
        Article another = createArticle(ArticleStatus.PUBLISHED);
        createComment(published, null);
        createComment(draft, null);
        createComment(another, null);
        UUID orphanUuid = UUID.randomUUID();
        Long orphanTop = insertOrphanComment(orphanUuid, null);
        Long orphanReply = insertOrphanComment(orphanUuid, orphanTop);
        createLike(orphanReply, SeededUsers.USER_UUID);
        double before = removedCounter();

        Result result = job.run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(countComments(orphanUuid)).as("孤兒留言（含回覆）被刪除").isZero();
        assertThat(countAllLikes()).as("孤兒留言的讚由模組內 CASCADE 一併清除").isZero();
        assertThat(countComments(published.getUuid())).isEqualTo(1);
        assertThat(countComments(draft.getUuid())).as("草稿文章仍存在，不可當孤兒").isEqualTo(1);
        assertThat(countComments(another.getUuid())).isEqualTo(1);
        assertThat(removedCounter() - before).as("orphans_removed 計入刪除的留言列數").isEqualTo(2.0);
    }

    @Test
    @DisplayName("沒有孤兒：COMPLETED 且零刪除")
    void run_noOrphans_completesWithoutDeleting() {
        Article a = createArticle(ArticleStatus.PUBLISHED);
        Article b = createArticle(ArticleStatus.PUBLISHED);
        createComment(a, null);
        createComment(b, null);

        Result result = job.run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(countComments(a.getUuid())).isEqualTo(1);
        assertThat(countComments(b.getUuid())).isEqualTo(1);
    }

    @Test
    @DisplayName("被參照的 UUID 全部都是孤兒：ABORTED、零刪除（框架保護未被子類別繞過）")
    void run_everyReferencedArticleIsOrphan_abortsWithoutDeleting() {
        Article borrowed = createArticle(ArticleStatus.PUBLISHED);
        UUID orphan1 = UUID.randomUUID();
        UUID orphan2 = UUID.randomUUID();
        insertOrphanComment(orphan1, null);
        insertOrphanComment(orphan2, null);
        double before = removedCounter();

        Result result = job.run();

        assertThat(result).isEqualTo(Result.ABORTED);
        assertThat(countComments(orphan1)).isEqualTo(1);
        assertThat(countComments(orphan2)).isEqualTo(1);
        assertThat(removedCounter() - before).isZero();
    }

    @Test
    @DisplayName("owner 查詢拋例外：FAILED、零刪除（不把查不到當成不存在）")
    void run_filterExistingThrows_failsWithoutDeleting() {
        Article a = createArticle(ArticleStatus.PUBLISHED);
        Article b = createArticle(ArticleStatus.PUBLISHED);
        createComment(a, null);
        createComment(b, null);
        UUID orphanUuid = UUID.randomUUID();
        insertOrphanComment(orphanUuid, null);
        doThrow(new IllegalStateException("article service down")).when(articleFacade).filterExistingUuids(any());

        Result result = job.run();

        assertThat(result).isEqualTo(Result.FAILED);
        assertThat(countComments(orphanUuid)).as("孤兒也不得被刪").isEqualTo(1);
        assertThat(countComments(a.getUuid())).as("a 自己的留言保留").isEqualTo(1);
        assertThat(countComments(b.getUuid())).isEqualTo(1);
    }
}
