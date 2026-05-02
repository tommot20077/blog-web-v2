package dowob.xyz.blog.module.series.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.series.exception.SeriesErrorCode;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.model.SeriesWithAuthor;
import dowob.xyz.blog.module.series.model.dto.request.CreateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.request.UpdateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.response.SeriesDetailResponse;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeriesServiceTest {

    @Mock private SeriesRepository repo;
    @Mock private SeriesMapper mapper;
    @Mock private ArticleService articleService;
    @Mock private ReadingFacade readingFacade;
    @InjectMocks private SeriesService service;

    private final UUID articleUuid = UUID.randomUUID();
    private final Long articleId = 200L;

    private final Long userId = 1L;
    private final Long seriesId = 100L;
    private final UUID seriesUuid = UUID.randomUUID();

    @Test
    void createSeries_validRequest_savesWithUuidAndDefaults() {
        when(repo.existsBySlug("vue-101")).thenReturn(false);
        when(repo.save(any(Series.class))).thenAnswer(inv -> {
            Series s = inv.getArgument(0);
            s.setId(seriesId);
            return s;
        });

        CreateSeriesRequest req = new CreateSeriesRequest();
        req.setTitle("Vue 101");
        req.setSlug("vue-101");
        req.setDescription("intro");

        service.createSeries(userId, req);

        ArgumentCaptor<Series> captor = ArgumentCaptor.forClass(Series.class);
        verify(repo).save(captor.capture());
        Series saved = captor.getValue();
        assertThat(saved.getUuid()).isNotNull();
        assertThat(saved.getAuthorId()).isEqualTo(userId);
        assertThat(saved.getTitle()).isEqualTo("Vue 101");
        assertThat(saved.getSlug()).isEqualTo("vue-101");
        assertThat(saved.getArticleCount()).isEqualTo(0);
    }

    @Test
    void createSeries_duplicateSlug_throwsS0104() {
        when(repo.existsBySlug("vue-101")).thenReturn(true);

        CreateSeriesRequest req = new CreateSeriesRequest();
        req.setTitle("Vue 101");
        req.setSlug("vue-101");

        assertThatThrownBy(() -> service.createSeries(userId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SLUG_ALREADY_USED.getMessage());

        verify(repo, never()).save(any());
    }

    @Test
    void updateSeries_byOwner_updatesAllFields() {
        Series existing = new Series();
        existing.setId(seriesId); existing.setUuid(seriesUuid);
        existing.setAuthorId(userId);
        existing.setTitle("old"); existing.setSlug("old-slug");
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(existing));
        when(repo.existsBySlug("new-slug")).thenReturn(false);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateSeriesRequest req = new UpdateSeriesRequest();
        req.setTitle("new");
        req.setSlug("new-slug");

        service.updateSeries(seriesUuid, userId, false, req);

        ArgumentCaptor<Series> captor = ArgumentCaptor.forClass(Series.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("new");
        assertThat(captor.getValue().getSlug()).isEqualTo("new-slug");
    }

    @Test
    void updateSeries_byNonOwnerNonAdmin_throwsS0102() {
        Series existing = new Series();
        existing.setId(seriesId); existing.setUuid(seriesUuid);
        existing.setAuthorId(999L);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(existing));

        UpdateSeriesRequest req = new UpdateSeriesRequest();
        req.setTitle("hijack");

        assertThatThrownBy(() -> service.updateSeries(seriesUuid, userId, false, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SERIES_ACCESS_DENIED.getMessage());

        verify(repo, never()).save(any());
    }

    @Test
    void updateSeries_byAdmin_succeeds() {
        Series existing = new Series();
        existing.setId(seriesId); existing.setUuid(seriesUuid);
        existing.setAuthorId(999L);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateSeriesRequest req = new UpdateSeriesRequest();
        req.setTitle("admin override");

        service.updateSeries(seriesUuid, userId, /* isAdmin */ true, req);

        verify(repo).save(any());
    }

    @Test
    void deleteSeries_byOwner_deletes() {
        Series existing = new Series();
        existing.setId(seriesId); existing.setUuid(seriesUuid);
        existing.setAuthorId(userId);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(existing));

        service.deleteSeries(seriesUuid, userId, false);

        verify(repo).deleteById(seriesId);
        // ON DELETE SET NULL 由 DB 處理；service 不需顯式 update articles
    }

    // ── T11: addArticleToSeries / removeArticleFromSeries ──────────────────

    @Test
    void addArticleToSeries_publishedArticle_savesAndIncrementsCount() {
        Series series = new Series();
        series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));

        Article article = new Article();
        article.setId(articleId); article.setUuid(articleUuid);
        article.setAuthorId(userId);
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setSeriesId(null);
        when(articleService.findByUuid(articleUuid)).thenReturn(Optional.of(article));

        service.addArticleToSeries(seriesUuid, articleUuid, userId, false, 3);

        verify(articleService).updateSeriesAssignment(articleId, seriesId, 3);
        verify(mapper).incrementArticleCount(seriesId);
    }

    @Test
    void addArticleToSeries_draftArticle_throwsS0103() {
        Series series = new Series();
        series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));

        Article article = new Article();
        article.setId(articleId); article.setUuid(articleUuid);
        article.setAuthorId(userId);
        article.setStatus(ArticleStatus.DRAFT);
        when(articleService.findByUuid(articleUuid)).thenReturn(Optional.of(article));

        assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userId, false, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.ARTICLE_NOT_PUBLISHED.getMessage());

        verify(articleService, never()).updateSeriesAssignment(any(), any(), any());
    }

    @Test
    void addArticleToSeries_articleAlreadyInOtherSeries_throwsS0106() {
        Series series = new Series();
        series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));

        Article article = new Article();
        article.setId(articleId); article.setUuid(articleUuid);
        article.setAuthorId(userId);
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setSeriesId(999L);
        when(articleService.findByUuid(articleUuid)).thenReturn(Optional.of(article));

        assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userId, false, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.ARTICLE_IN_OTHER_SERIES.getMessage());
    }

    @Test
    void addArticleToSeries_sameSeriesUpdatesPosition() {
        Series series = new Series();
        series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));

        Article article = new Article();
        article.setId(articleId); article.setUuid(articleUuid);
        article.setAuthorId(userId);
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setSeriesId(seriesId);
        article.setSeriesPosition(2);
        when(articleService.findByUuid(articleUuid)).thenReturn(Optional.of(article));

        service.addArticleToSeries(seriesUuid, articleUuid, userId, false, 5);

        verify(articleService).updateSeriesAssignment(articleId, seriesId, 5);
        verify(mapper, never()).incrementArticleCount(any());
    }

    @Test
    void removeArticleFromSeries_decrementsCount() {
        Series series = new Series();
        series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));

        Article article = new Article();
        article.setId(articleId); article.setUuid(articleUuid);
        article.setSeriesId(seriesId);
        article.setSeriesPosition(3);
        when(articleService.findByUuid(articleUuid)).thenReturn(Optional.of(article));

        service.removeArticleFromSeries(seriesUuid, articleUuid, userId, false);

        verify(articleService).updateSeriesAssignment(articleId, null, null);
        verify(mapper).decrementArticleCount(seriesId);
    }

    @Test
    void removeArticleFromSeries_articleNotInThisSeries_throwsS0105() {
        Series series = new Series();
        series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));

        Article article = new Article();
        article.setId(articleId); article.setUuid(articleUuid);
        article.setSeriesId(null);
        when(articleService.findByUuid(articleUuid)).thenReturn(Optional.of(article));

        assertThatThrownBy(() -> service.removeArticleFromSeries(seriesUuid, articleUuid, userId, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.ARTICLE_NOT_IN_SERIES.getMessage());
    }

    // ── T12: getSeriesDetail ───────────────────────────────────────────────

    @Test
    void getSeriesDetail_authenticated_includesMyProgress() {
        SeriesWithAuthor row = new SeriesWithAuthor();
        row.setId(seriesId); row.setUuid(seriesUuid);
        row.setTitle("Vue 101"); row.setSlug("vue-101");
        row.setArticleCount(3);
        row.setAuthorUuid(UUID.randomUUID()); row.setAuthorNickname("user");
        when(mapper.findBySlugWithAuthor("vue-101")).thenReturn(row);

        UUID uuidA = UUID.randomUUID();
        UUID uuidB = UUID.randomUUID();
        UUID uuidC = UUID.randomUUID();
        List<Article> articles = List.of(
                buildArticle(1L, uuidA, "A", 1),
                buildArticle(2L, uuidB, "B", 2),
                buildArticle(3L, uuidC, "C", 3)
        );
        when(articleService.findBySeriesIdOrderByPosition(seriesId)).thenReturn(articles);

        Map<Long, BigDecimal> progressMap = Map.of(
                1L, new BigDecimal("0.98"),
                2L, new BigDecimal("0.50")
        );
        lenient().when(readingFacade.batchGetProgress(eq(userId), any())).thenReturn(progressMap);

        SeriesDetailResponse resp = service.getSeriesDetail("vue-101", userId);

        assertThat(resp.getMyProgress()).isNotNull();
        // article 1 進度 0.98 >= 0.95 → 算已讀；article 2 = 0.50 / article 3 無紀錄 → 未讀
        assertThat(resp.getMyProgress().getReadCount()).isEqualTo(1);
        assertThat(resp.getMyProgress().getTotalCount()).isEqualTo(3);
        // 第一個未讀完的是 article 2（position=2，進度 0.50）
        assertThat(resp.getMyProgress().getNextUnreadArticleUuid()).isEqualTo(uuidB);
    }

    private Article buildArticle(Long id, UUID uuid, String title, int position) {
        Article a = new Article();
        a.setId(id); a.setUuid(uuid); a.setTitle(title);
        a.setSeriesPosition(position);
        return a;
    }
}
