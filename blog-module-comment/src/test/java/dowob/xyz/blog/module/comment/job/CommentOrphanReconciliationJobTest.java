package dowob.xyz.blog.module.comment.job;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.comment.mapper.CommentArticleCleanupMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link CommentOrphanReconciliationJob} 單元測試：子類別提供的各個鉤子與排程標註（框架行為由
 * blog-infrastructure 的測試涵蓋，整體流程見 {@code CommentOrphanReconciliationJobIT}）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("CommentOrphanReconciliationJob 單元測試")
class CommentOrphanReconciliationJobTest {

    private CommentArticleCleanupMapper mapper;
    private ArticleFacade articleFacade;
    private CommentOrphanReconciliationJob job;

    @BeforeEach
    void setUp() {
        mapper = mock(CommentArticleCleanupMapper.class);
        articleFacade = mock(ArticleFacade.class);
        job = new CommentOrphanReconciliationJob(mock(StringRedisTemplate.class), mapper, articleFacade);
    }

    @Test
    @DisplayName("module 與 table 識別為 comment／comments")
    void identity_isCommentAndComments() {
        assertThat(job.module()).isEqualTo("comment");
        assertThat(job.table()).isEqualTo("comments");
    }

    @Test
    @DisplayName("fetchReferencedUuids：把 mapper 回傳的字串轉回 UUID，並原樣傳遞游標與筆數")
    void fetchReferencedUuids_parsesMapperRowsAndPassesCursor() {
        UUID after = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(mapper.findDistinctArticleUuidsAfter(after, 500)).thenReturn(List.of(a.toString(), b.toString()));

        assertThat(job.fetchReferencedUuids(after, 500)).containsExactly(a, b);
    }

    @Test
    @DisplayName("fetchReferencedUuids：游標為 null 時從頭開始")
    void fetchReferencedUuids_nullCursor_startsFromBeginning() {
        when(mapper.findDistinctArticleUuidsAfter(null, 10)).thenReturn(List.of());

        assertThat(job.fetchReferencedUuids(null, 10)).isEmpty();
    }

    @Test
    @DisplayName("filterExisting：委派 ArticleFacade.filterExistingUuids（不分狀態），且不使用 filterPublishedUuids")
    void filterExisting_delegatesToFilterExistingUuids() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(articleFacade.filterExistingUuids(Set.of(a, b))).thenReturn(Set.of(a));

        assertThat(job.filterExisting(Set.of(a, b))).containsExactly(a);
        verify(articleFacade, never()).filterPublishedUuids(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("removeOrphans：以 mapper 一次刪除並回傳刪除筆數")
    void removeOrphans_deletesAndReturnsCount() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(mapper.deleteByArticleUuids(List.of(a, b))).thenReturn(5);

        assertThat(job.removeOrphans(List.of(a, b))).isEqualTo(5);
    }

    @Test
    @DisplayName("removeOrphans：空集合不呼叫 mapper（避免 IN () 語法錯誤）並回傳 0")
    void removeOrphans_empty_returnsZeroWithoutQuery() {
        assertThat(job.removeOrphans(List.of())).isZero();
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("排程：恰有一個 @Scheduled 方法，cron 為每日一次的固定時刻常數")
    void scheduledMethod_usesDailyFixedCron() {
        List<Method> scheduled = java.util.Arrays.stream(CommentOrphanReconciliationJob.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class))
                .toList();

        assertThat(scheduled).hasSize(1);
        assertThat(scheduled.get(0).getAnnotation(Scheduled.class).cron())
                .isEqualTo(CommentOrphanReconciliationJob.CRON)
                .isEqualTo("0 17 3 * * *");
    }
}
