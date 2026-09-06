package dowob.xyz.blog.module.series.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.module.article.service.ArticleQueryService;
import dowob.xyz.blog.module.series.exception.SeriesErrorCode;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.model.SeriesWithAuthor;
import dowob.xyz.blog.module.series.model.dto.request.CreateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.request.UpdateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.response.SeriesDetailResponse;
import dowob.xyz.blog.module.series.model.dto.response.SeriesSummaryResponse;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeriesServiceTest {

    @Mock private SeriesRepository repo;
    @Mock private SeriesMapper mapper;
    @Mock private ArticleFacade articleFacade;
    @Mock private ArticleQueryService articleQueryService;
    @Mock private ReadingFacade readingFacade;
    @InjectMocks private SeriesService service;

    private final UUID articleUuid = UUID.randomUUID();
    private final Long articleId = 200L;

    /**
     * stub create/update 寫入後的 re-fetch。
     *
     * <p>createSeries / updateSeries 回傳 SeriesSummaryResponse（對外只出 UUID，
     * 不再回 entity），故寫入後會以 slug re-fetch 取得 author 欄位。本 helper 讓
     * 該 re-fetch 回傳一筆對得上的 row；驗證寫入內容的斷言仍以 repo.save 的
     * ArgumentCaptor 為準，不受影響。</p>
     */
    private void stubRefetchBySlug(String slug) {
        SeriesWithAuthor row = new SeriesWithAuthor();
        row.setId(seriesId);
        row.setUuid(seriesUuid);
        row.setSlug(slug);
        row.setAuthorUuid(UUID.randomUUID());
        row.setAuthorNickname("user");
        lenient().when(mapper.findBySlugWithAuthor(slug)).thenReturn(row);
    }

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
        stubRefetchBySlug("vue-101");

        CreateSeriesRequest req = new CreateSeriesRequest();
        req.setTitle("Vue 101");
        req.setSlug("vue-101");
        req.setDescription("intro");

        SeriesSummaryResponse resp = service.createSeries(userId, req);

        ArgumentCaptor<Series> captor = ArgumentCaptor.forClass(Series.class);
        verify(repo).save(captor.capture());
        Series saved = captor.getValue();
        assertThat(saved.getUuid()).isNotNull();
        assertThat(saved.getAuthorId()).isEqualTo(userId);
        assertThat(saved.getTitle()).isEqualTo("Vue 101");
        assertThat(saved.getSlug()).isEqualTo("vue-101");
        assertThat(saved.getArticleCount()).isEqualTo(0);

        // 對外只出 UUID（architecture.md）：回傳 DTO 帶 uuid 與 author summary，
        // 不含內部 Long id / authorId。此斷言在 service 層鎖住「不洩漏」不變量，
        // 不再只靠 SeriesControllerIT 的 .doesNotExist() 守衛。
        assertThat(resp).isNotNull();
        assertThat(resp.getUuid()).isEqualTo(seriesUuid);
        assertThat(resp.getAuthor()).isNotNull();
        assertThat(resp.getAuthor().getNickname()).isEqualTo("user");
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

    /**
     * 寫入成功但 re-fetch 回傳 null（資料異常）時，應丟 BusinessException 而非 NPE，
     * 比照 getSeriesDetail 的防禦式寫法（Copilot review 建議）。
     */
    @Test
    void createSeries_refetchReturnsNull_throwsSeriesNotFound() {
        when(repo.existsBySlug("vue-101")).thenReturn(false);
        when(repo.save(any(Series.class))).thenAnswer(inv -> {
            Series s = inv.getArgument(0);
            s.setId(seriesId);
            return s;
        });
        when(mapper.findBySlugWithAuthor("vue-101")).thenReturn(null);

        CreateSeriesRequest req = new CreateSeriesRequest();
        req.setTitle("Vue 101");
        req.setSlug("vue-101");

        assertThatThrownBy(() -> service.createSeries(userId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SERIES_NOT_FOUND.getMessage());
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
        stubRefetchBySlug("new-slug");

        UpdateSeriesRequest req = new UpdateSeriesRequest();
        req.setTitle("new");
        req.setSlug("new-slug");

        SeriesSummaryResponse resp = service.updateSeries(seriesUuid, userId, false, req);

        ArgumentCaptor<Series> captor = ArgumentCaptor.forClass(Series.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("new");
        assertThat(captor.getValue().getSlug()).isEqualTo("new-slug");

        // 對外只出 UUID（architecture.md）：回傳 DTO 帶 uuid 與 author summary，
        // 不含內部 Long id / authorId。
        assertThat(resp).isNotNull();
        assertThat(resp.getUuid()).isEqualTo(seriesUuid);
        assertThat(resp.getAuthor()).isNotNull();
        assertThat(resp.getAuthor().getNickname()).isEqualTo("user");
    }

    /**
     * 更新成功但 re-fetch 回傳 null（資料異常）時，應丟 BusinessException 而非 NPE，
     * 避免「更新成功卻回 500」（Copilot review 建議）。
     */
    @Test
    void updateSeries_refetchReturnsNull_throwsSeriesNotFound() {
        Series existing = new Series();
        existing.setId(seriesId); existing.setUuid(seriesUuid);
        existing.setAuthorId(userId);
        existing.setTitle("old"); existing.setSlug("old-slug");
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(existing));
        when(repo.existsBySlug("new-slug")).thenReturn(false);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.findBySlugWithAuthor("new-slug")).thenReturn(null);

        UpdateSeriesRequest req = new UpdateSeriesRequest();
        req.setTitle("new");
        req.setSlug("new-slug");

        assertThatThrownBy(() -> service.updateSeries(seriesUuid, userId, false, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SERIES_NOT_FOUND.getMessage());
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
        existing.setSlug("admin-series");
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubRefetchBySlug("admin-series");

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

        ArticleData article = new ArticleData(articleId, articleUuid, userId, ArticleStatus.PUBLISHED.name(), null, null);
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(article));

        service.addArticleToSeries(seriesUuid, articleUuid, userId, false, 3);

        verify(articleFacade).updateSeriesAssignment(articleId, seriesId, 3);
        verify(mapper).incrementArticleCount(seriesId);
    }

    @Test
    void addArticleToSeries_draftArticle_throwsS0103() {
        Series series = new Series();
        series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));

        ArticleData article = new ArticleData(articleId, articleUuid, userId, ArticleStatus.DRAFT.name(), null, null);
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(article));

        assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userId, false, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.ARTICLE_NOT_PUBLISHED.getMessage());

        verify(articleFacade, never()).updateSeriesAssignment(any(), any(), any());
    }

    @Test
    void addArticleToSeries_articleAlreadyInOtherSeries_throwsS0106() {
        Series series = new Series();
        series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));

        ArticleData article = new ArticleData(articleId, articleUuid, userId, ArticleStatus.PUBLISHED.name(), 999L, 1);
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(article));

        assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userId, false, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.ARTICLE_IN_OTHER_SERIES.getMessage());
    }

    @Test
    void addArticleToSeries_sameSeriesUpdatesPosition() {
        Series series = new Series();
        series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));

        ArticleData article = new ArticleData(articleId, articleUuid, userId, ArticleStatus.PUBLISHED.name(), seriesId, 2);
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(article));

        service.addArticleToSeries(seriesUuid, articleUuid, userId, false, 5);

        verify(articleFacade).updateSeriesAssignment(articleId, seriesId, 5);
        verify(mapper, never()).incrementArticleCount(any());
    }

    @Test
    void removeArticleFromSeries_decrementsCount() {
        Series series = new Series();
        series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));

        ArticleData article = new ArticleData(articleId, articleUuid, userId, ArticleStatus.PUBLISHED.name(), seriesId, 3);
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(article));

        service.removeArticleFromSeries(seriesUuid, articleUuid, userId, false);

        verify(articleFacade).updateSeriesAssignment(articleId, null, null);
        verify(mapper).decrementArticleCount(seriesId);
    }

    @Test
    void removeArticleFromSeries_articleNotInThisSeries_throwsS0105() {
        Series series = new Series();
        series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));

        ArticleData article = new ArticleData(articleId, articleUuid, userId, ArticleStatus.PUBLISHED.name(), null, null);
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(article));

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
        List<ArticleData> articles = List.of(
                new ArticleData(1L, uuidA, userId, ArticleStatus.PUBLISHED.name(), seriesId, 1),
                new ArticleData(2L, uuidB, userId, ArticleStatus.PUBLISHED.name(), seriesId, 2),
                new ArticleData(3L, uuidC, userId, ArticleStatus.PUBLISHED.name(), seriesId, 3)
        );
        when(articleFacade.findBySeriesIdOrderByPosition(seriesId)).thenReturn(articles);

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

    // ── B: ARTICLE_NOT_FOUND (S0107) ──────────────────────────────────────

    @Test
    void addArticleToSeries_articleNotFound_throwsS0107() {
        Series series = new Series();
        series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userId, false, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.ARTICLE_NOT_FOUND.getMessage());
    }

    @Test
    void removeArticleFromSeries_articleNotFound_throwsS0107() {
        Series series = new Series();
        series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeArticleFromSeries(seriesUuid, articleUuid, userId, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.ARTICLE_NOT_FOUND.getMessage());
    }

    // ── C: getSeriesDetail articles 非空 ───────────────────────────────────

    @Test
    void getSeriesDetail_withArticles_articlesListNotEmpty() {
        SeriesWithAuthor row = new SeriesWithAuthor();
        row.setId(seriesId); row.setUuid(seriesUuid);
        row.setTitle("Vue 101"); row.setSlug("vue-101");
        row.setArticleCount(1);
        row.setAuthorUuid(UUID.randomUUID()); row.setAuthorNickname("user");
        when(mapper.findBySlugWithAuthor("vue-101")).thenReturn(row);

        UUID uuidA = UUID.randomUUID();
        ArticleData articleData = new ArticleData(1L, uuidA, userId, ArticleStatus.PUBLISHED.name(), seriesId, 1);
        List<ArticleData> articles = List.of(articleData);
        when(articleFacade.findBySeriesIdOrderByPosition(seriesId)).thenReturn(articles);

        var summary = dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse.builder()
                .uuid(uuidA).title("A").seriesPosition(1).build();
        when(articleQueryService.getArticleSummariesByIds(List.of(1L), false)).thenReturn(List.of(summary));

        lenient().when(readingFacade.batchGetProgress(any(), any())).thenReturn(Map.of());

        dowob.xyz.blog.module.series.model.dto.response.SeriesDetailResponse resp =
                service.getSeriesDetail("vue-101", null);

        assertThat(resp.getArticles()).isNotEmpty();
        assertThat(resp.getArticles()).hasSize(1);
        assertThat(resp.getArticles().get(0).getUuid()).isEqualTo(uuidA);
    }

    // ── D: getSeriesDetail 只對外公開 PUBLISHED ─────────────────────────────

    @Test
    void getSeriesDetail_mixedStatuses_exposesPublishedOnly() {
        SeriesWithAuthor row = new SeriesWithAuthor();
        row.setId(seriesId); row.setUuid(seriesUuid);
        row.setTitle("Vue 101"); row.setSlug("vue-101");
        row.setArticleCount(5);
        row.setAuthorUuid(UUID.randomUUID()); row.setAuthorNickname("user");
        when(mapper.findBySlugWithAuthor("vue-101")).thenReturn(row);

        UUID publishedUuid = UUID.randomUUID();
        UUID draftUuid = UUID.randomUUID();
        List<ArticleData> articles = List.of(
                new ArticleData(1L, publishedUuid, userId, ArticleStatus.PUBLISHED.name(), seriesId, 1),
                new ArticleData(2L, draftUuid, userId, ArticleStatus.DRAFT.name(), seriesId, 2),
                new ArticleData(3L, UUID.randomUUID(), userId, ArticleStatus.REJECTED.name(), seriesId, 3),
                new ArticleData(4L, UUID.randomUUID(), userId, ArticleStatus.ARCHIVED.name(), seriesId, 4),
                new ArticleData(5L, UUID.randomUUID(), userId, ArticleStatus.PENDING_REVIEW.name(), seriesId, 5)
        );
        when(articleFacade.findBySeriesIdOrderByPosition(seriesId)).thenReturn(articles);

        var publishedSummary = dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse.builder()
                .uuid(publishedUuid).title("A").seriesPosition(1).build();
        when(articleQueryService.getArticleSummariesByIds(any(), eq(false))).thenReturn(List.of(publishedSummary));

        // 唯一的 PUBLISHED 文章已讀完，故公開視角下沒有下一篇未讀
        lenient().when(readingFacade.batchGetProgress(eq(userId), any()))
                .thenReturn(Map.of(1L, new BigDecimal("0.98")));

        SeriesDetailResponse resp = service.getSeriesDetail("vue-101", userId);

        // 非 PUBLISHED 不得進入 enrich 查詢，否則 title/slug/summary/content 會流到匿名訪客
        verify(articleQueryService).getArticleSummariesByIds(List.of(1L), false);
        assertThat(resp.getArticles()).hasSize(1);
        assertThat(resp.getArticles().get(0).getUuid()).isEqualTo(publishedUuid);

        // 分母只算公開文章；nextUnread 若回 draftUuid 等於把未公開文章的 UUID 洩漏出去
        assertThat(resp.getMyProgress().getReadCount()).isEqualTo(1);
        assertThat(resp.getMyProgress().getTotalCount()).isEqualTo(1);
        assertThat(resp.getMyProgress().getNextUnreadArticleUuid()).isNull();
    }

    // ── E: getSeriesDetail articleCount 反映 PUBLISHED 數（finding #1）─────────

    @Test
    void getSeriesDetail_mixedStatuses_articleCountReflectsPublishedOnly() {
        SeriesWithAuthor row = new SeriesWithAuthor();
        row.setId(seriesId); row.setUuid(seriesUuid);
        row.setTitle("Vue 101"); row.setSlug("vue-101");
        // 非正規化計數欄含非公開文章（此處帶漂移）；response 的 articleCount 不得沿用此值
        row.setArticleCount(3);
        row.setAuthorUuid(UUID.randomUUID()); row.setAuthorNickname("user");
        when(mapper.findBySlugWithAuthor("vue-101")).thenReturn(row);

        UUID publishedUuid = UUID.randomUUID();
        List<ArticleData> articles = List.of(
                new ArticleData(1L, publishedUuid, userId, ArticleStatus.PUBLISHED.name(), seriesId, 1),
                new ArticleData(2L, UUID.randomUUID(), userId, ArticleStatus.DRAFT.name(), seriesId, 2),
                new ArticleData(3L, UUID.randomUUID(), userId, ArticleStatus.ARCHIVED.name(), seriesId, 3)
        );
        when(articleFacade.findBySeriesIdOrderByPosition(seriesId)).thenReturn(articles);

        var publishedSummary = dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse.builder()
                .uuid(publishedUuid).title("A").seriesPosition(1).build();
        when(articleQueryService.getArticleSummariesByIds(any(), eq(false))).thenReturn(List.of(publishedSummary));

        SeriesDetailResponse resp = service.getSeriesDetail("vue-101", null);

        // articleCount 必須對齊對外可見的文章數，否則讀者看到「count=3 但只列 1 篇」並反推出隱藏文章數
        assertThat(resp.getArticleCount()).isEqualTo(1);
        assertThat(resp.getArticles()).hasSize(1);
    }

    // ── F: getSeriesDetail 無任何 PUBLISHED 文章 → 回 200 空清單（series 仍視為存在，finding #3）─

    @Test
    void getSeriesDetail_noPublishedArticles_returnsEmptyArticles() {
        SeriesWithAuthor row = new SeriesWithAuthor();
        row.setId(seriesId); row.setUuid(seriesUuid);
        row.setTitle("Draft Only"); row.setSlug("draft-only");
        // 反正規化計數 > 0（漂移），但實際無 PUBLISHED
        row.setArticleCount(2);
        when(mapper.findBySlugWithAuthor("draft-only")).thenReturn(row);

        List<ArticleData> articles = List.of(
                new ArticleData(1L, UUID.randomUUID(), userId, ArticleStatus.DRAFT.name(), seriesId, 1),
                new ArticleData(2L, UUID.randomUUID(), userId, ArticleStatus.PENDING_REVIEW.name(), seriesId, 2)
        );
        when(articleFacade.findBySeriesIdOrderByPosition(seriesId)).thenReturn(articles);

        // series 一旦存在即回應（不因零篇公開而 404）：articles 為空、articleCount 為 0
        SeriesDetailResponse resp = service.getSeriesDetail("draft-only", null);

        assertThat(resp.getArticleCount()).isEqualTo(0);
        assertThat(resp.getArticles()).isEmpty();
        // 仍不得對未公開內容做任何 enrich 查詢
        verify(articleQueryService, never()).getArticleSummariesByIds(any());
    }

    // ── G: listPublic 三段式（Task 5）─────────────────────────────────────

    @Test
    @DisplayName("listPublic 只列出有公開文章的 series，且 articleCount 為即時計數")
    void listPublicOnlyListsSeriesWithPublishedArticlesAndArticleCountIsLive() {
        when(mapper.findAllIdsOrderByCreatedAtDesc()).thenReturn(List.of(10L, 20L, 30L));
        when(articleFacade.countPublishedBySeriesIds(List.of(10L, 20L, 30L)))
                .thenReturn(Map.of(10L, 3, 30L, 1));

        SeriesWithAuthor row10 = new SeriesWithAuthor();
        row10.setId(10L);
        row10.setUuid(UUID.randomUUID());
        row10.setSlug("s10");
        row10.setAuthorUuid(UUID.randomUUID());
        SeriesWithAuthor row30 = new SeriesWithAuthor();
        row30.setId(30L);
        row30.setUuid(UUID.randomUUID());
        row30.setSlug("s30");
        row30.setAuthorUuid(UUID.randomUUID());
        when(mapper.findByIdsWithAuthor(List.of(10L, 30L))).thenReturn(List.of(row10, row30));

        PageResult<SeriesSummaryResponse> result = service.listPublic(1, 20);

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getRecords().get(0).getArticleCount()).isEqualTo(3);
        assertThat(result.getRecords().get(1).getArticleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("listPublic 第二頁只取該頁的 id")
    void listPublicSecondPageOnlyTakesIdsOfThatPage() {
        when(mapper.findAllIdsOrderByCreatedAtDesc()).thenReturn(List.of(10L, 20L, 30L));
        when(articleFacade.countPublishedBySeriesIds(List.of(10L, 20L, 30L)))
                .thenReturn(Map.of(10L, 1, 20L, 1, 30L, 1));

        SeriesWithAuthor row30 = new SeriesWithAuthor();
        row30.setId(30L);
        row30.setUuid(UUID.randomUUID());
        row30.setSlug("s30");
        row30.setAuthorUuid(UUID.randomUUID());
        when(mapper.findByIdsWithAuthor(List.of(30L))).thenReturn(List.of(row30));

        PageResult<SeriesSummaryResponse> result = service.listPublic(2, 2);

        assertThat(result.getTotal()).isEqualTo(3L);
        assertThat(result.getRecords()).hasSize(1);
    }

    @Test
    @DisplayName("listPublic 全無公開文章時回空頁，且不查明細")
    void listPublicWhenNoPublishedArticles_returnsEmptyPageWithoutDetailQuery() {
        when(mapper.findAllIdsOrderByCreatedAtDesc()).thenReturn(List.of(10L, 20L));
        when(articleFacade.countPublishedBySeriesIds(List.of(10L, 20L))).thenReturn(Map.of());

        PageResult<SeriesSummaryResponse> result = service.listPublic(1, 20);

        assertThat(result.getTotal()).isZero();
        assertThat(result.getRecords()).isEmpty();
        verify(mapper, never()).findByIdsWithAuthor(anyList());
    }

    @Test
    @DisplayName("listPublic 超出範圍的頁回空清單，但 total 不變")
    void listPublicOutOfRangePageReturnsEmptyListButTotalUnchanged() {
        when(mapper.findAllIdsOrderByCreatedAtDesc()).thenReturn(List.of(10L));
        when(articleFacade.countPublishedBySeriesIds(List.of(10L))).thenReturn(Map.of(10L, 1));

        PageResult<SeriesSummaryResponse> result = service.listPublic(5, 20);

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getRecords()).isEmpty();
    }

    /**
     * 鎖住「newest first」的排序契約。
     *
     * <p>{@code SeriesMapper.findByIdsWithAuthor} 的 JavaDoc 明言回傳順序不保證，SQL 也沒有
     * ORDER BY；listPublic 之所以仍能保證「最新優先」，全靠依 pageIds 走訪、以
     * {@code rowById::get} 查表重組順序這個寫法。若改成直接疊代 {@code rowById.values()}，
     * 順序會退化成 HashMap 的內部疊代順序而非 pageIds 順序。本測試刻意讓 mapper 回傳與
     * pageIds 相反的順序，若 production code 改用 values()，本測試會抓到（見
     * fix-wave 報告中的 RED 佐證）。</p>
     */
    @Test
    @DisplayName("listPublic 回傳順序鎖定在 pageIds，即使 mapper 回傳順序相反也不受影響")
    void listPublicOrdersRecordsByPageIdsRegardlessOfMapperReturnOrder() {
        when(mapper.findAllIdsOrderByCreatedAtDesc()).thenReturn(List.of(10L, 20L, 30L));
        when(articleFacade.countPublishedBySeriesIds(List.of(10L, 20L, 30L)))
                .thenReturn(Map.of(10L, 1, 20L, 1, 30L, 1));

        SeriesWithAuthor row10 = new SeriesWithAuthor();
        row10.setId(10L);
        row10.setUuid(UUID.randomUUID());
        row10.setSlug("s10");
        row10.setAuthorUuid(UUID.randomUUID());
        SeriesWithAuthor row20 = new SeriesWithAuthor();
        row20.setId(20L);
        row20.setUuid(UUID.randomUUID());
        row20.setSlug("s20");
        row20.setAuthorUuid(UUID.randomUUID());
        SeriesWithAuthor row30 = new SeriesWithAuthor();
        row30.setId(30L);
        row30.setUuid(UUID.randomUUID());
        row30.setSlug("s30");
        row30.setAuthorUuid(UUID.randomUUID());

        // mapper 刻意回傳與 pageIds（10, 20, 30）相反的順序，模擬「順序不保證」的真實情境。
        when(mapper.findByIdsWithAuthor(List.of(10L, 20L, 30L)))
                .thenReturn(List.of(row30, row20, row10));

        PageResult<SeriesSummaryResponse> result = service.listPublic(1, 20);

        // 回應順序必須依 pageIds（10 → 20 → 30），不是 mapper 回傳順序（30 → 20 → 10），
        // 也不是任何 Map 的內部疊代順序。
        assertThat(result.getRecords())
                .extracting(SeriesSummaryResponse::getSlug)
                .containsExactly("s10", "s20", "s30");
    }

    /**
     * 鎖住 offset 運算不溢位。
     *
     * <p>{@code (page - 1) * size} 若以 int 相乘，在 page 夠大時會溢位成負數；舊寫法
     * {@code Math.max(0, …)} 會把溢位的負值吞成 0，等於靜默地把 offset 當成 0——之後
     * {@code subList(0, …)} 仍會撈出「第 1 頁」的內容並正常組出 records，只是回報給呼叫端的
     * 仍是它送來的巨大 page 號。這裡刻意 stub {@code findByIdsWithAuthor} 回傳第 1 頁的兩筆
     * row：若 production code 仍是 int 溢位版本，offset 會被吞成 0，records 就會非空
     * （撈到這兩筆）；long 版本則會讓 offset 正確落在 visibleIds 範圍外，直接回空清單、
     * 連 {@code findByIdsWithAuthor} 都不會呼叫——這與姊妹端點 {@code BookmarkQueryService}
     * 對同樣輸入的行為一致。size 固定在 {@code MAX_PAGE_SIZE}（100）之上界時，
     * page ≈ 21,474,838 即可讓 {@code (page - 1) * size} 以 int 運算溢位；此處取更大的 page
     * 以確保穩定觸發。</p>
     */
    @Test
    @DisplayName("listPublic 給超大 page 時 offset 以 long 運算不溢位，回空清單而非誤回第一頁")
    void listPublicHugePageDoesNotOverflowOffsetArithmetic() {
        when(mapper.findAllIdsOrderByCreatedAtDesc()).thenReturn(List.of(10L, 20L));
        when(articleFacade.countPublishedBySeriesIds(List.of(10L, 20L)))
                .thenReturn(Map.of(10L, 1, 20L, 1));

        // 若 production code 仍是 int 溢位版本，offset 會被 Math.max(0, …) 吞成 0，
        // 進而以「頁 1」的 id 呼叫這支 mapper 方法；stub 讓那個誤判有真實資料可撈，
        // 使該分支的錯誤行為在斷言上「看得見」而非被 Mockito 預設空回傳蓋掉。
        SeriesWithAuthor row10 = new SeriesWithAuthor();
        row10.setId(10L);
        row10.setUuid(UUID.randomUUID());
        row10.setSlug("s10");
        row10.setAuthorUuid(UUID.randomUUID());
        SeriesWithAuthor row20 = new SeriesWithAuthor();
        row20.setId(20L);
        row20.setUuid(UUID.randomUUID());
        row20.setSlug("s20");
        row20.setAuthorUuid(UUID.randomUUID());
        lenient().when(mapper.findByIdsWithAuthor(List.of(10L, 20L)))
                .thenReturn(List.of(row10, row20));

        PageResult<SeriesSummaryResponse> result = service.listPublic(30_000_000, 100);

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getRecords()).isEmpty();
    }
}

