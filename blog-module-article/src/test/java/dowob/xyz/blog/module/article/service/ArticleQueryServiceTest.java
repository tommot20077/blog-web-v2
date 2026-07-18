package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesNavigation;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.dto.response.ArticleArchiveResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArticleQueryService 單元測試
 *
 * <p>驗證 CQRS Read 層的 liked 欄位填充邏輯，以及對 ArticleService 的正確委派。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleQueryService 單元測試")
class ArticleQueryServiceTest {

    @Mock
    private ArticleService articleService;

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private ReadingFacade readingFacade;

    @Mock
    private SeriesFacade seriesFacade;

    @InjectMocks
    private ArticleQueryService articleQueryService;

    private static final Long AUTHOR_ID = 1L;
    private static final UUID ARTICLE_UUID = UUID.randomUUID();
    private static final Long ARTICLE_DB_ID = 42L;

    /**
     * 建立測試用 ArticleSummaryResponse
     */
    private ArticleSummaryResponse buildSummary() {
        return ArticleSummaryResponse.builder()
                .uuid(ARTICLE_UUID)
                .title("測試標題")
                .summary("測試摘要")
                .status(ArticleStatus.PUBLISHED)
                .viewCount(0L)
                .likeCount(0L)
                .commentCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 建立測試用 ArticleResponse（liked=null，模擬 ArticleService 回傳）
     */
    private ArticleResponse buildResponse() {
        return ArticleResponse.builder()
                .uuid(ARTICLE_UUID)
                .title("測試標題")
                .content("測試內容")
                .status(ArticleStatus.PUBLISHED)
                .viewCount(0L)
                .likeCount(0L)
                .commentCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .liked(null)
                .build();
    }

    /**
     * 建立 Article stub（僅含 uuid 和 id，供 findIdsByUuids 回傳）
     */
    private Article buildArticleIdRow() {
        Article a = new Article();
        a.setId(ARTICLE_DB_ID);
        a.setUuid(ARTICLE_UUID);
        return a;
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ─── getPublishedArticles ───

    @Nested
    @DisplayName("getPublishedArticles — liked 填充")
    class GetPublishedArticlesTests {

        @Test
        @DisplayName("正常：未登入時，所有文章 liked=false")
        void anonymous_likedFalse() {
            ArticleSummaryResponse summary = buildSummary();
            PageResult<ArticleSummaryResponse> page = PageResult.of(1, 10, 1L, List.of(summary));
            when(articleService.getPublishedArticles(1, 10)).thenReturn(page);
            when(articleMapper.findIdsByUuids(List.of(ARTICLE_UUID))).thenReturn(List.of(buildArticleIdRow()));

            PageResult<ArticleSummaryResponse> result = articleQueryService.getPublishedArticles(1, 10);

            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getRecords().get(0).getLiked()).isFalse();
        }

        @Test
        @DisplayName("正常：已登入且已按讚，liked=true")
        void loggedIn_likedTrue() {
            ArticleSummaryResponse summary = buildSummary();
            PageResult<ArticleSummaryResponse> page = PageResult.of(1, 10, 1L, List.of(summary));
            when(articleService.getPublishedArticles(1, 10)).thenReturn(page);
            when(articleMapper.findIdsByUuids(List.of(ARTICLE_UUID))).thenReturn(List.of(buildArticleIdRow()));
            when(readingFacade.batchIsLiked(AUTHOR_ID, List.of(ARTICLE_DB_ID))).thenReturn(Set.of(ARTICLE_DB_ID));

            // 設定登入狀態
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(AUTHOR_ID, null, List.of()));

            PageResult<ArticleSummaryResponse> result = articleQueryService.getPublishedArticles(1, 10);

            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getRecords().get(0).getLiked()).isTrue();
        }

        @Test
        @DisplayName("正常：已登入但未按讚，liked=false")
        void loggedIn_notLiked() {
            ArticleSummaryResponse summary = buildSummary();
            PageResult<ArticleSummaryResponse> page = PageResult.of(1, 10, 1L, List.of(summary));
            when(articleService.getPublishedArticles(1, 10)).thenReturn(page);
            when(articleMapper.findIdsByUuids(List.of(ARTICLE_UUID))).thenReturn(List.of(buildArticleIdRow()));
            when(readingFacade.batchIsLiked(AUTHOR_ID, List.of(ARTICLE_DB_ID))).thenReturn(Collections.emptySet());

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(AUTHOR_ID, null, List.of()));

            PageResult<ArticleSummaryResponse> result = articleQueryService.getPublishedArticles(1, 10);

            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getRecords().get(0).getLiked()).isFalse();
        }

        @Test
        @DisplayName("正常：空列表時不出錯，直接回傳空結果")
        void emptyList_noError() {
            PageResult<ArticleSummaryResponse> page = PageResult.of(1, 10, 0L, Collections.emptyList());
            when(articleService.getPublishedArticles(1, 10)).thenReturn(page);

            PageResult<ArticleSummaryResponse> result = articleQueryService.getPublishedArticles(1, 10);

            assertThat(result.getRecords()).isEmpty();
        }
    }

    // ─── getArticleByUuid ───

    @Nested
    @DisplayName("getArticleByUuid — liked 填充")
    class GetArticleByUuidTests {

        @Test
        @DisplayName("正常：未登入時，liked=false")
        void anonymous_likedFalse() {
            ArticleResponse resp = buildResponse();
            when(articleService.getArticleByUuid(ARTICLE_UUID, null, null, "127.0.0.1")).thenReturn(resp);
            when(articleService.findIdByUuid(ARTICLE_UUID)).thenReturn(ARTICLE_DB_ID);

            ArticleResponse result = articleQueryService.getArticleByUuid(ARTICLE_UUID, null, null, "127.0.0.1");

            assertThat(result.getLiked()).isFalse();
        }

        @Test
        @DisplayName("正常：已登入且已按讚，liked=true")
        void loggedIn_likedTrue() {
            ArticleResponse resp = buildResponse();
            when(articleService.getArticleByUuid(ARTICLE_UUID, AUTHOR_ID, Role.AUTHOR, "127.0.0.1")).thenReturn(resp);
            when(articleService.findIdByUuid(ARTICLE_UUID)).thenReturn(ARTICLE_DB_ID);
            when(readingFacade.isLiked(AUTHOR_ID, ARTICLE_DB_ID)).thenReturn(true);

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(AUTHOR_ID, null, List.of()));

            ArticleResponse result = articleQueryService.getArticleByUuid(ARTICLE_UUID, AUTHOR_ID, Role.AUTHOR, "127.0.0.1");

            assertThat(result.getLiked()).isTrue();
        }

        @Test
        @DisplayName("正常：已登入但未按讚，liked=false")
        void loggedIn_notLiked() {
            ArticleResponse resp = buildResponse();
            when(articleService.getArticleByUuid(ARTICLE_UUID, AUTHOR_ID, Role.AUTHOR, "127.0.0.1")).thenReturn(resp);
            when(articleService.findIdByUuid(ARTICLE_UUID)).thenReturn(ARTICLE_DB_ID);
            when(readingFacade.isLiked(AUTHOR_ID, ARTICLE_DB_ID)).thenReturn(false);

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(AUTHOR_ID, null, List.of()));

            ArticleResponse result = articleQueryService.getArticleByUuid(ARTICLE_UUID, AUTHOR_ID, Role.AUTHOR, "127.0.0.1");

            assertThat(result.getLiked()).isFalse();
        }
    }

    // ─── getArticleBySlug ───

    @Nested
    @DisplayName("getArticleBySlug — liked 填充")
    class GetArticleBySlugTests {

        @Test
        @DisplayName("正常：未登入時，liked=false")
        void anonymous_likedFalse() {
            ArticleResponse resp = buildResponse();
            when(articleService.getArticleBySlug("test-slug", null, null, "127.0.0.1")).thenReturn(resp);
            when(articleService.findIdByUuid(ARTICLE_UUID)).thenReturn(ARTICLE_DB_ID);

            ArticleResponse result = articleQueryService.getArticleBySlug("test-slug", null, null, "127.0.0.1");

            assertThat(result.getLiked()).isFalse();
        }

        @Test
        @DisplayName("正常：已登入且已按讚，liked=true")
        void loggedIn_likedTrue() {
            ArticleResponse resp = buildResponse();
            when(articleService.getArticleBySlug("test-slug", AUTHOR_ID, Role.AUTHOR, "127.0.0.1")).thenReturn(resp);
            when(articleService.findIdByUuid(ARTICLE_UUID)).thenReturn(ARTICLE_DB_ID);
            when(readingFacade.isLiked(AUTHOR_ID, ARTICLE_DB_ID)).thenReturn(true);

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(AUTHOR_ID, null, List.of()));

            ArticleResponse result = articleQueryService.getArticleBySlug("test-slug", AUTHOR_ID, Role.AUTHOR, "127.0.0.1");

            assertThat(result.getLiked()).isTrue();
        }
    }

    // ─── getMyArticles ───

    @Nested
    @DisplayName("getMyArticles — liked 填充")
    class GetMyArticlesTests {

        @Test
        @DisplayName("正常：已登入且有按讚，liked=true")
        void loggedIn_likedTrue() {
            ArticleSummaryResponse summary = buildSummary();
            PageResult<ArticleSummaryResponse> page = PageResult.of(1, 10, 1L, List.of(summary));
            when(articleService.getMyArticles(AUTHOR_ID, 1, 10, null)).thenReturn(page);
            when(articleMapper.findIdsByUuids(List.of(ARTICLE_UUID))).thenReturn(List.of(buildArticleIdRow()));
            when(readingFacade.batchIsLiked(AUTHOR_ID, List.of(ARTICLE_DB_ID))).thenReturn(Set.of(ARTICLE_DB_ID));

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(AUTHOR_ID, null, List.of()));

            PageResult<ArticleSummaryResponse> result = articleQueryService.getMyArticles(AUTHOR_ID, 1, 10, null);

            assertThat(result.getRecords().get(0).getLiked()).isTrue();
        }
    }

    // ─── getPendingArticles ───

    @Nested
    @DisplayName("getPendingArticles — liked 填充")
    class GetPendingArticlesTests {

        @Test
        @DisplayName("正常：未登入時，所有待審文章 liked=false")
        void anonymous_likedFalse() {
            ArticleSummaryResponse summary = buildSummary();
            PageResult<ArticleSummaryResponse> page = PageResult.of(1, 10, 1L, List.of(summary));
            when(articleService.getPendingArticles(1, 10)).thenReturn(page);
            when(articleMapper.findIdsByUuids(List.of(ARTICLE_UUID))).thenReturn(List.of(buildArticleIdRow()));

            PageResult<ArticleSummaryResponse> result = articleQueryService.getPendingArticles(1, 10);

            assertThat(result.getRecords().get(0).getLiked()).isFalse();
        }
    }

    // ─── enrich bookmarked + lastReadProgress（T12 新增）───

    @Nested
    @DisplayName("enrich 列表 — bookmarked + lastReadProgress 填充")
    class EnrichBookmarkedAndProgressTests {

        @Test
        @DisplayName("已登入且已收藏：bookmarked=true")
        void enrich_includesBookmarkedFlag() {
            ArticleSummaryResponse summary = buildSummary();
            PageResult<ArticleSummaryResponse> page = PageResult.of(1, 10, 1L, List.of(summary));
            when(articleService.getPublishedArticles(1, 10)).thenReturn(page);
            when(articleMapper.findIdsByUuids(List.of(ARTICLE_UUID))).thenReturn(List.of(buildArticleIdRow()));
            when(readingFacade.batchIsLiked(AUTHOR_ID, List.of(ARTICLE_DB_ID)))
                    .thenReturn(Collections.emptySet());
            when(readingFacade.batchIsBookmarked(AUTHOR_ID, List.of(ARTICLE_DB_ID)))
                    .thenReturn(Set.of(ARTICLE_DB_ID));
            when(readingFacade.batchGetProgress(AUTHOR_ID, List.of(ARTICLE_DB_ID)))
                    .thenReturn(Collections.emptyMap());

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(AUTHOR_ID, null, List.of()));

            PageResult<ArticleSummaryResponse> result = articleQueryService.getPublishedArticles(1, 10);

            assertThat(result.getRecords().get(0).getBookmarked()).isTrue();
        }

        @Test
        @DisplayName("已登入且有閱讀進度：lastReadProgress 填入正確值")
        void enrich_includesLastReadProgress() {
            BigDecimal progress = new BigDecimal("0.45");
            ArticleSummaryResponse summary = buildSummary();
            PageResult<ArticleSummaryResponse> page = PageResult.of(1, 10, 1L, List.of(summary));
            when(articleService.getPublishedArticles(1, 10)).thenReturn(page);
            when(articleMapper.findIdsByUuids(List.of(ARTICLE_UUID))).thenReturn(List.of(buildArticleIdRow()));
            when(readingFacade.batchIsLiked(AUTHOR_ID, List.of(ARTICLE_DB_ID)))
                    .thenReturn(Collections.emptySet());
            when(readingFacade.batchIsBookmarked(AUTHOR_ID, List.of(ARTICLE_DB_ID)))
                    .thenReturn(Collections.emptySet());
            when(readingFacade.batchGetProgress(AUTHOR_ID, List.of(ARTICLE_DB_ID)))
                    .thenReturn(Map.of(ARTICLE_DB_ID, progress));

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(AUTHOR_ID, null, List.of()));

            PageResult<ArticleSummaryResponse> result = articleQueryService.getPublishedArticles(1, 10);

            assertThat(result.getRecords().get(0).getLastReadProgress())
                    .isEqualByComparingTo(progress);
        }

        @Test
        @DisplayName("匿名查詢時 bookmarked=false，lastReadProgress=null")
        void enrich_unauthenticated_allFlagsAreFalseOrNull() {
            ArticleSummaryResponse summary = buildSummary();
            PageResult<ArticleSummaryResponse> page = PageResult.of(1, 10, 1L, List.of(summary));
            when(articleService.getPublishedArticles(1, 10)).thenReturn(page);
            when(articleMapper.findIdsByUuids(List.of(ARTICLE_UUID))).thenReturn(List.of(buildArticleIdRow()));

            // 不設 SecurityContext，模擬匿名
            PageResult<ArticleSummaryResponse> result = articleQueryService.getPublishedArticles(1, 10);

            assertThat(result.getRecords().get(0).getLiked()).isFalse();
            assertThat(result.getRecords().get(0).getBookmarked()).isFalse();
            assertThat(result.getRecords().get(0).getLastReadProgress()).isNull();
        }
    }

    // ─── getArchive（Task 2 新增）───

    @Nested
    @DisplayName("getArchive — 年度歸檔精簡投影")
    class GetArchiveTests {

        /**
         * 建立測試用 ArticleArchiveResponse
         *
         * @param title       文章標題
         * @param slug        URL slug
         * @param publishedAt 發布時間
         * @return 歸檔投影
         */
        private ArticleArchiveResponse buildArchive(String title, String slug, LocalDateTime publishedAt) {
            return ArticleArchiveResponse.builder()
                    .uuid(UUID.randomUUID())
                    .title(title)
                    .slug(slug)
                    .publishedAt(publishedAt)
                    .tags(List.of("Java", "Spring"))
                    .build();
        }

        @Test
        @DisplayName("正常：回傳全部已發布投影，依 publishedAt 由新到舊排序")
        void getArchive_returnsAllPublishedProjectionOrderedByPublishedAtDesc() {
            ArticleArchiveResponse newer =
                    buildArchive("新文章", "newer", LocalDateTime.of(2026, 6, 1, 10, 0));
            ArticleArchiveResponse older =
                    buildArchive("舊文章", "older", LocalDateTime.of(2025, 1, 1, 10, 0));
            when(articleService.getArchive()).thenReturn(List.of(newer, older));

            List<ArticleArchiveResponse> result = articleQueryService.getArchive();

            assertThat(result).hasSize(2);
            assertThat(result).extracting(ArticleArchiveResponse::getTitle)
                    .containsExactly("新文章", "舊文章");
            assertThat(result.get(0).getPublishedAt()).isAfter(result.get(1).getPublishedAt());
            assertThat(result.get(0).getUuid()).isNotNull();
            assertThat(result.get(0).getSlug()).isEqualTo("newer");
            assertThat(result.get(0).getTags()).containsExactly("Java", "Spring");
        }

        @Test
        @DisplayName("邊界：無已發布文章時回傳空清單")
        void getArchive_whenNoPublished_returnsEmptyList() {
            when(articleService.getArchive()).thenReturn(List.of());

            List<ArticleArchiveResponse> result = articleQueryService.getArchive();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常：直接委派 ArticleService.getArchive() 原樣回傳（不做 liked enrich）")
        void getArchive_passesThroughServiceResult() {
            ArticleArchiveResponse one =
                    buildArchive("唯一文章", "only", LocalDateTime.of(2026, 3, 3, 8, 0));
            List<ArticleArchiveResponse> expected = List.of(one);
            when(articleService.getArchive()).thenReturn(expected);

            List<ArticleArchiveResponse> result = articleQueryService.getArchive();

            assertThat(result).isSameAs(expected);
        }
    }

    // ─── enrichSingle — bookmarked + lastReadProgress（T12 新增）───

    @Nested
    @DisplayName("enrichSingle — 所有欄位填充")
    class EnrichSingleAllFieldsTests {

        @Test
        @DisplayName("已登入：liked + bookmarked + lastReadProgress 全部填入")
        void enrichSingle_setsAllNewFields() {
            BigDecimal progress = new BigDecimal("0.72");
            ArticleResponse resp = buildResponse();

            when(articleService.getArticleByUuid(ARTICLE_UUID, AUTHOR_ID, Role.AUTHOR, "127.0.0.1"))
                    .thenReturn(resp);
            when(articleService.findIdByUuid(ARTICLE_UUID)).thenReturn(ARTICLE_DB_ID);
            when(readingFacade.isLiked(AUTHOR_ID, ARTICLE_DB_ID)).thenReturn(true);
            when(readingFacade.isBookmarked(AUTHOR_ID, ARTICLE_DB_ID)).thenReturn(true);
            when(readingFacade.getProgress(AUTHOR_ID, ARTICLE_UUID)).thenReturn(progress);
            when(seriesFacade.getSeriesNavigation(ARTICLE_DB_ID)).thenReturn(Optional.empty());

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(AUTHOR_ID, null, List.of()));

            ArticleResponse result = articleQueryService.getArticleByUuid(
                    ARTICLE_UUID, AUTHOR_ID, Role.AUTHOR, "127.0.0.1");

            assertThat(result.getLiked()).isTrue();
            assertThat(result.getBookmarked()).isTrue();
            assertThat(result.getLastReadProgress()).isEqualByComparingTo(progress);
        }
    }

    // ─── enrichSingle — SeriesNavigation（T14 新增）───

    @Nested
    @DisplayName("enrichSingle — SeriesNavigation 填充")
    class EnrichSingleSeriesNavTests {

        @Test
        @DisplayName("文章在 series 中間位置：seriesNav 含 prev 與 next")
        void enrichSingle_articleInSeries_includesSeriesNav() {
            ArticleResponse resp = buildResponse();
            SeriesNavigation.SeriesArticleRef prevRef =
                    new SeriesNavigation.SeriesArticleRef(UUID.randomUUID(), "第一篇", "first-article");
            SeriesNavigation.SeriesArticleRef nextRef =
                    new SeriesNavigation.SeriesArticleRef(UUID.randomUUID(), "第三篇", "third-article");
            SeriesNavigation nav = new SeriesNavigation(
                    UUID.randomUUID(), "測試系列", "test-series", 2, 3, prevRef, nextRef);

            when(articleService.getArticleByUuid(ARTICLE_UUID, null, null, "127.0.0.1")).thenReturn(resp);
            when(articleService.findIdByUuid(ARTICLE_UUID)).thenReturn(ARTICLE_DB_ID);
            when(seriesFacade.getSeriesNavigation(ARTICLE_DB_ID)).thenReturn(Optional.of(nav));

            ArticleResponse result = articleQueryService.getArticleByUuid(ARTICLE_UUID, null, null, "127.0.0.1");

            assertThat(result.getSeriesNav()).isNotNull();
            assertThat(result.getSeriesNav().getPrev()).isNotNull();
            assertThat(result.getSeriesNav().getPrev().getTitle()).isEqualTo("第一篇");
            assertThat(result.getSeriesNav().getNext()).isNotNull();
            assertThat(result.getSeriesNav().getNext().getTitle()).isEqualTo("第三篇");
        }

        @Test
        @DisplayName("文章在 series 第一位：seriesNav.prev 為 null")
        void enrichSingle_seriesPositionFirst_prevIsNull() {
            ArticleResponse resp = buildResponse();
            SeriesNavigation.SeriesArticleRef nextRef =
                    new SeriesNavigation.SeriesArticleRef(UUID.randomUUID(), "第二篇", "second-article");
            SeriesNavigation nav = new SeriesNavigation(
                    UUID.randomUUID(), "測試系列", "test-series", 1, 2, null, nextRef);

            when(articleService.getArticleByUuid(ARTICLE_UUID, null, null, "127.0.0.1")).thenReturn(resp);
            when(articleService.findIdByUuid(ARTICLE_UUID)).thenReturn(ARTICLE_DB_ID);
            when(seriesFacade.getSeriesNavigation(ARTICLE_DB_ID)).thenReturn(Optional.of(nav));

            ArticleResponse result = articleQueryService.getArticleByUuid(ARTICLE_UUID, null, null, "127.0.0.1");

            assertThat(result.getSeriesNav()).isNotNull();
            assertThat(result.getSeriesNav().getPrev()).isNull();
            assertThat(result.getSeriesNav().getNext()).isNotNull();
            assertThat(result.getSeriesNav().getNext().getTitle()).isEqualTo("第二篇");
        }

        @Test
        @DisplayName("文章在 series 最後位置：seriesNav.next 為 null")
        void enrichSingle_seriesPositionLast_nextIsNull() {
            ArticleResponse resp = buildResponse();
            SeriesNavigation.SeriesArticleRef prevRef =
                    new SeriesNavigation.SeriesArticleRef(UUID.randomUUID(), "第一篇", "first-article");
            SeriesNavigation nav = new SeriesNavigation(
                    UUID.randomUUID(), "測試系列", "test-series", 2, 2, prevRef, null);

            when(articleService.getArticleByUuid(ARTICLE_UUID, null, null, "127.0.0.1")).thenReturn(resp);
            when(articleService.findIdByUuid(ARTICLE_UUID)).thenReturn(ARTICLE_DB_ID);
            when(seriesFacade.getSeriesNavigation(ARTICLE_DB_ID)).thenReturn(Optional.of(nav));

            ArticleResponse result = articleQueryService.getArticleByUuid(ARTICLE_UUID, null, null, "127.0.0.1");

            assertThat(result.getSeriesNav()).isNotNull();
            assertThat(result.getSeriesNav().getPrev()).isNotNull();
            assertThat(result.getSeriesNav().getPrev().getTitle()).isEqualTo("第一篇");
            assertThat(result.getSeriesNav().getNext()).isNull();
        }
    }

    @Nested
    @DisplayName("getArticleSummariesByIds — series enrich 開關")
    class GetArticleSummariesByIdsTests {

        private ArticleSummaryResponse summaryWithSeriesPosition() {
            return ArticleSummaryResponse.builder()
                    .uuid(ARTICLE_UUID)
                    .seriesPosition(1)
                    .build();
        }

        @Test
        @DisplayName("includeSeriesNav=false：不呼叫 seriesFacade，seriesUuid/seriesTitle 保持 null")
        void getArticleSummariesByIds_skipSeriesNav_doesNotCallSeriesFacade() {
            ArticleSummaryResponse summary = summaryWithSeriesPosition();
            when(articleService.getArticleSummariesByIds(List.of(ARTICLE_DB_ID)))
                    .thenReturn(List.of(summary));
            when(articleMapper.findIdsByUuids(List.of(ARTICLE_UUID)))
                    .thenReturn(List.of(buildArticleIdRow()));

            List<ArticleSummaryResponse> result =
                    articleQueryService.getArticleSummariesByIds(List.of(ARTICLE_DB_ID), false);

            // series 詳情自行以 row 覆寫 seriesUuid/seriesTitle，enrich 不該再查 SeriesFacade（會被覆寫，白費）
            verify(seriesFacade, never()).batchGetSeriesBasicInfo(anyList());
            assertThat(result.get(0).getSeriesUuid()).isNull();
            assertThat(result.get(0).getSeriesTitle()).isNull();
        }

        @Test
        @DisplayName("預設（1-arg）：仍呼叫 seriesFacade 補 seriesUuid/seriesTitle（BookmarkController 需要）")
        void getArticleSummariesByIds_default_enrichesSeriesNav() {
            UUID seriesUuid = UUID.randomUUID();
            ArticleSummaryResponse summary = summaryWithSeriesPosition();
            when(articleService.getArticleSummariesByIds(List.of(ARTICLE_DB_ID)))
                    .thenReturn(List.of(summary));
            when(articleMapper.findIdsByUuids(List.of(ARTICLE_UUID)))
                    .thenReturn(List.of(buildArticleIdRow()));
            when(seriesFacade.batchGetSeriesBasicInfo(List.of(ARTICLE_DB_ID)))
                    .thenReturn(Map.of(ARTICLE_DB_ID, new SeriesBasicInfo(seriesUuid, "S")));

            List<ArticleSummaryResponse> result =
                    articleQueryService.getArticleSummariesByIds(List.of(ARTICLE_DB_ID));

            verify(seriesFacade).batchGetSeriesBasicInfo(List.of(ARTICLE_DB_ID));
            assertThat(result.get(0).getSeriesUuid()).isEqualTo(seriesUuid);
            assertThat(result.get(0).getSeriesTitle()).isEqualTo("S");
        }
    }
}
