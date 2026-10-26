package dowob.xyz.blog.module.article.job;

import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob;
import dowob.xyz.blog.module.article.mapper.ArticleSeriesCleanupMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleSeriesOrphanReconciliationJob} 單元測試：子類別提供的各個鉤子與排程標註
 * （框架行為由 blog-infrastructure 的測試涵蓋，整體流程見 {@code ArticleSeriesOrphanReconciliationJobIT}）。
 *
 * <p>與其他模組的校正排程不同：孤兒的處理是「把 {@code articles.series_uuid} 設為 NULL」，<b>絕不刪除文章</b>
 * （系列只是文章的可選關聯，文章本身仍是有效資料）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleSeriesOrphanReconciliationJob 單元測試")
class ArticleSeriesOrphanReconciliationJobTest {

    private ArticleSeriesCleanupMapper mapper;
    private SeriesFacade seriesFacade;
    private ArticleSeriesOrphanReconciliationJob job;

    @BeforeEach
    void setUp() {
        mapper = mock(ArticleSeriesCleanupMapper.class);
        seriesFacade = mock(SeriesFacade.class);
        job = new ArticleSeriesOrphanReconciliationJob(mock(StringRedisTemplate.class), mapper, seriesFacade);
    }

    @Test
    @DisplayName("module 與 table 識別為 article／articles")
    void identity_isArticleAndArticles() {
        assertThat(job.module()).isEqualTo("article");
        assertThat(job.table()).isEqualTo("articles");
    }

    @Test
    @DisplayName("是 OrphanReconciliationJob 的子類別（沿用框架的鎖、游標、中止門檻與指標）")
    void extendsOrphanReconciliationJob() {
        assertThat(OrphanReconciliationJob.class).isAssignableFrom(ArticleSeriesOrphanReconciliationJob.class);
    }

    @Test
    @DisplayName("fetchReferencedUuids：把 mapper 回傳的字串轉回 UUID，並原樣傳遞游標與筆數")
    void fetchReferencedUuids_parsesMapperRowsAndPassesCursor() {
        UUID after = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(mapper.findDistinctSeriesUuidsAfter(after, 500)).thenReturn(List.of(a.toString(), b.toString()));

        assertThat(job.fetchReferencedUuids(after, 500)).containsExactly(a, b);
    }

    @Test
    @DisplayName("fetchReferencedUuids：游標為 null 時從頭開始")
    void fetchReferencedUuids_nullCursor_startsFromBeginning() {
        when(mapper.findDistinctSeriesUuidsAfter(null, 10)).thenReturn(List.of());

        assertThat(job.fetchReferencedUuids(null, 10)).isEmpty();
    }

    @Test
    @DisplayName("filterExisting：委派 SeriesFacade.filterExistingUuids")
    void filterExisting_delegatesToSeriesFacade() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(seriesFacade.filterExistingUuids(Set.of(a, b))).thenReturn(Set.of(a));

        assertThat(job.filterExisting(Set.of(a, b))).containsExactly(a);
    }

    @Test
    @DisplayName("removeOrphans：以 mapper 把成員文章的系列歸屬設為 NULL，並回傳清空的文章筆數")
    void removeOrphans_clearsMembershipAndReturnsCount() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(mapper.clearSeriesByUuids(List.of(a, b))).thenReturn(5);

        assertThat(job.removeOrphans(List.of(a, b))).isEqualTo(5);
        verify(mapper).clearSeriesByUuids(List.of(a, b));
    }

    @Test
    @DisplayName("removeOrphans：空集合不呼叫 mapper（避免 IN () 語法錯誤）並回傳 0")
    void removeOrphans_empty_returnsZeroWithoutQuery() {
        assertThat(job.removeOrphans(List.of())).isZero();
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("cleanup mapper 只提供「清空歸屬」，沒有任何刪除文章的方法（絕不可刪文章）")
    void cleanupMapper_hasNoDeleteMethod() {
        assertThat(Arrays.stream(ArticleSeriesCleanupMapper.class.getDeclaredMethods()).map(Method::getName))
                .noneMatch(name -> name.toLowerCase().contains("delete"));
    }

    @Test
    @DisplayName("排程：恰有一個 @Scheduled 方法，cron 為每日一次的固定時刻常數 03:53")
    void scheduledMethod_usesDailyFixedCron() {
        List<Method> scheduled = Arrays.stream(ArticleSeriesOrphanReconciliationJob.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class))
                .toList();

        assertThat(scheduled).hasSize(1);
        assertThat(scheduled.get(0).getAnnotation(Scheduled.class).cron())
                .isEqualTo(ArticleSeriesOrphanReconciliationJob.CRON)
                .isEqualTo("0 53 3 * * *");
    }
}
