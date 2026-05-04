package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.model.dto.response.EditorArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.TagSummaryResponse;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ArticleQuerySubService")
class ArticleQuerySubServiceTest {

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private ArticleEntityFinder entityFinder;

    @Mock
    private ArticleResponseMapper responseMapper;

    private ArticleQuerySubService querySubService;

    private static final Long AUTHOR_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long ARTICLE_ID = 11L;
    private static final Long SERIES_ID = 99L;
    private static final UUID ARTICLE_UUID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        querySubService = new ArticleQuerySubService(
                articleRepository, articleMapper, entityFinder, responseMapper
        );
    }

    private Article buildArticle(ArticleStatus status) {
        Article article = new Article();
        article.setId(ARTICLE_ID);
        article.setUuid(ARTICLE_UUID);
        article.setAuthorId(AUTHOR_ID);
        article.setTitle("Test article");
        article.setContent("Test content");
        article.setSummary("Test summary");
        article.setSlug("test-slug-abcd1234");
        article.setStatus(status);
        article.setViewCount(0L);
        article.setLikeCount(0L);
        article.setCommentCount(0);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        return article;
    }

    private ArticleResponse articleResponse(Article article) {
        return ArticleResponse.builder()
                .uuid(article.getUuid())
                .slug(article.getSlug())
                .status(article.getStatus())
                .build();
    }

    private EditorArticleResponse editorResponse(Article article) {
        return EditorArticleResponse.builder()
                .uuid(article.getUuid())
                .content(article.getContent())
                .status(article.getStatus())
                .build();
    }

    private ArticleSummaryResponse summaryResponse(Article article) {
        return ArticleSummaryResponse.builder()
                .uuid(article.getUuid())
                .status(article.getStatus())
                .updatedAt(article.getUpdatedAt())
                .build();
    }

    @Nested
    @DisplayName("getArticleByUuid")
    class GetArticleByUuidTests {

        @Test
        @DisplayName("published article can be read anonymously and mapped")
        void getArticle_published_anonymous() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            ArticleResponse expected = articleResponse(article);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(responseMapper.toResponse(article)).thenReturn(expected);

            ArticleResponse response = querySubService.getArticleByUuid(ARTICLE_UUID, null, null);

            assertThat(response).isSameAs(expected);
            verify(responseMapper).toResponse(article);
        }

        @Test
        @DisplayName("draft article can be read by author")
        void getArticle_draft_authorCanAccess() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            ArticleResponse expected = articleResponse(article);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(responseMapper.toResponse(article)).thenReturn(expected);

            ArticleResponse response = querySubService.getArticleByUuid(ARTICLE_UUID, AUTHOR_ID, Role.AUTHOR);

            assertThat(response).isSameAs(expected);
        }

        @Test
        @DisplayName("draft article can be read by admin")
        void getArticle_draft_adminCanAccess() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            ArticleResponse expected = articleResponse(article);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(responseMapper.toResponse(article)).thenReturn(expected);

            ArticleResponse response = querySubService.getArticleByUuid(ARTICLE_UUID, OTHER_USER_ID, Role.ADMIN);

            assertThat(response).isSameAs(expected);
        }

        @Test
        @DisplayName("draft article is hidden from non-author")
        void getArticle_draft_otherUserDenied() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> querySubService.getArticleByUuid(ARTICLE_UUID, OTHER_USER_ID, Role.AUTHOR))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage());
            verify(responseMapper, never()).toResponse(any());
        }

        @Test
        @DisplayName("pending review article can be read by author")
        void getArticle_pendingReview_authorCanAccess() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            ArticleResponse expected = articleResponse(article);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(responseMapper.toResponse(article)).thenReturn(expected);

            ArticleResponse response = querySubService.getArticleByUuid(ARTICLE_UUID, AUTHOR_ID, Role.AUTHOR);

            assertThat(response).isSameAs(expected);
        }

        @Test
        @DisplayName("pending review article is hidden from non-author")
        void getArticle_pendingReview_otherUserDenied() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> querySubService.getArticleByUuid(ARTICLE_UUID, OTHER_USER_ID, Role.AUTHOR))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage());
            verify(responseMapper, never()).toResponse(any());
        }
    }

    @Nested
    @DisplayName("getArticleBySlug")
    class GetArticleBySlugTests {

        @Test
        @DisplayName("published article can be read by slug and mapped")
        void getArticleBySlug_published_success() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            ArticleResponse expected = articleResponse(article);
            when(articleRepository.findBySlug(article.getSlug())).thenReturn(Optional.of(article));
            when(responseMapper.toResponse(article)).thenReturn(expected);

            ArticleResponse response = querySubService.getArticleBySlug(article.getSlug(), null, null);

            assertThat(response).isSameAs(expected);
            verify(responseMapper).toResponse(article);
        }

        @Test
        @DisplayName("missing slug throws article not found")
        void getArticleBySlug_notFound() {
            when(articleRepository.findBySlug("missing-slug")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> querySubService.getArticleBySlug("missing-slug", null, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("draft article by slug is hidden from non-author")
        void getArticleBySlug_draftArticle_otherUserDenied() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findBySlug(article.getSlug())).thenReturn(Optional.of(article));

            assertThatThrownBy(() -> querySubService.getArticleBySlug(article.getSlug(), OTHER_USER_ID, Role.AUTHOR))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage());
            verify(responseMapper, never()).toResponse(any());
        }
    }

    @Nested
    @DisplayName("getArticleForEdit")
    class GetArticleForEditTests {

        @Test
        @DisplayName("author can read editor response")
        void getArticleForEdit_authorCanAccess() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            EditorArticleResponse expected = editorResponse(article);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(responseMapper.toEditorResponse(article)).thenReturn(expected);

            EditorArticleResponse response = querySubService.getArticleForEdit(ARTICLE_UUID, AUTHOR_ID);

            assertThat(response).isSameAs(expected);
            verify(responseMapper).toEditorResponse(article);
        }

        @Test
        @DisplayName("rejected article can be read by author for edit")
        void getArticleForEdit_rejectedArticle_authorCanAccess() {
            Article article = buildArticle(ArticleStatus.REJECTED);
            EditorArticleResponse expected = editorResponse(article);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(responseMapper.toEditorResponse(article)).thenReturn(expected);

            EditorArticleResponse response = querySubService.getArticleForEdit(ARTICLE_UUID, AUTHOR_ID);

            assertThat(response).isSameAs(expected);
        }

        @Test
        @DisplayName("missing article propagates finder not found")
        void getArticleForEdit_notFound() {
            UUID missingUuid = UUID.randomUUID();
            when(entityFinder.findByUuidOrThrow(missingUuid))
                    .thenThrow(new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND));

            assertThatThrownBy(() -> querySubService.getArticleForEdit(missingUuid, AUTHOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("non-author cannot read editor response")
        void getArticleForEdit_otherUser_denied() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> querySubService.getArticleForEdit(ARTICLE_UUID, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage());
            verify(responseMapper, never()).toEditorResponse(any());
        }
    }

    @Nested
    @DisplayName("getPublishedArticles")
    class GetPublishedArticlesTests {

        @Test
        @DisplayName("returns mapped published page")
        void getPublishedArticles_success() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            ArticleSummaryResponse summary = summaryResponse(article);
            Map<UUID, List<TagSummaryResponse>> tagMap = Map.of(ARTICLE_UUID, List.of());
            when(articleMapper.findPublishedPage(0L, 10)).thenReturn(List.of(article));
            when(articleMapper.countPublished()).thenReturn(1L);
            when(responseMapper.batchToTagResponsesMap(List.of(ARTICLE_UUID))).thenReturn(tagMap);
            when(responseMapper.toSummaryResponse(article, tagMap)).thenReturn(summary);

            PageResult<ArticleSummaryResponse> result = querySubService.getPublishedArticles(1, 10);

            assertThat(result.getRecords()).containsExactly(summary);
            assertThat(result.getTotal()).isEqualTo(1L);
            assertThat(result.getCurrent()).isEqualTo(1);
        }

        @Test
        @DisplayName("second page uses calculated offset")
        void getPublishedArticles_secondPage() {
            when(articleMapper.findPublishedPage(10L, 10)).thenReturn(List.of());
            when(articleMapper.countPublished()).thenReturn(5L);
            when(responseMapper.batchToTagResponsesMap(List.of())).thenReturn(Map.of());

            PageResult<ArticleSummaryResponse> result = querySubService.getPublishedArticles(2, 10);

            assertThat(result.getRecords()).isEmpty();
            verify(articleMapper).findPublishedPage(10L, 10);
        }
    }

    @Nested
    @DisplayName("getPublishedArticlesByCategorySlug")
    class GetPublishedArticlesByCategorySlugTests {

        @Test
        @DisplayName("returns mapped category page")
        void getPublishedArticlesByCategorySlug_success() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            ArticleSummaryResponse summary = summaryResponse(article);
            String categorySlug = "tech";
            Map<UUID, List<TagSummaryResponse>> tagMap = Map.of(ARTICLE_UUID, List.of());
            when(articleMapper.findPublishedPageByCategorySlug(categorySlug, 0L, 10)).thenReturn(List.of(article));
            when(articleMapper.countPublishedByCategorySlug(categorySlug)).thenReturn(1L);
            when(responseMapper.batchToTagResponsesMap(List.of(ARTICLE_UUID))).thenReturn(tagMap);
            when(responseMapper.toSummaryResponse(article, tagMap)).thenReturn(summary);

            PageResult<ArticleSummaryResponse> result =
                    querySubService.getPublishedArticlesByCategorySlug(categorySlug, 1, 10);

            assertThat(result.getRecords()).containsExactly(summary);
            assertThat(result.getTotal()).isEqualTo(1L);
            verify(articleMapper).findPublishedPageByCategorySlug(categorySlug, 0L, 10);
            verify(articleMapper).countPublishedByCategorySlug(categorySlug);
        }

        @Test
        @DisplayName("second category page uses calculated offset")
        void getPublishedArticlesByCategorySlug_secondPage_correctOffset() {
            String categorySlug = "java";
            when(articleMapper.findPublishedPageByCategorySlug(categorySlug, 10L, 10)).thenReturn(List.of());
            when(articleMapper.countPublishedByCategorySlug(categorySlug)).thenReturn(3L);
            when(responseMapper.batchToTagResponsesMap(List.of())).thenReturn(Map.of());

            PageResult<ArticleSummaryResponse> result =
                    querySubService.getPublishedArticlesByCategorySlug(categorySlug, 2, 10);

            assertThat(result.getRecords()).isEmpty();
            verify(articleMapper).findPublishedPageByCategorySlug(categorySlug, 10L, 10);
        }
    }

    @Nested
    @DisplayName("getMyArticles")
    class GetMyArticlesTests {

        @Test
        @DisplayName("null status returns all author articles")
        void getMyArticles_statusNull_returnsAll() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            ArticleSummaryResponse summary = summaryResponse(article);
            Map<UUID, List<TagSummaryResponse>> tagMap = Map.of(ARTICLE_UUID, List.of());
            when(articleMapper.findByAuthorIdPaged(AUTHOR_ID, 0L, 10)).thenReturn(List.of(article));
            when(articleMapper.countByAuthorId(AUTHOR_ID)).thenReturn(1L);
            when(responseMapper.batchToTagResponsesMap(List.of(ARTICLE_UUID))).thenReturn(tagMap);
            when(responseMapper.toSummaryResponse(article, tagMap)).thenReturn(summary);

            PageResult<ArticleSummaryResponse> result =
                    querySubService.getMyArticles(AUTHOR_ID, 1, 10, null);

            assertThat(result.getRecords()).containsExactly(summary);
            assertThat(result.getTotal()).isEqualTo(1L);
            verify(articleMapper).findByAuthorIdPaged(AUTHOR_ID, 0L, 10);
            verify(articleMapper, never()).findByAuthorIdAndStatus(any(), any(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("status filter returns matching author articles")
        void getMyArticles_statusDraft_returnsDraftOnly() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            ArticleSummaryResponse summary = summaryResponse(article);
            Map<UUID, List<TagSummaryResponse>> tagMap = Map.of(ARTICLE_UUID, List.of());
            when(articleMapper.findByAuthorIdAndStatus(AUTHOR_ID, ArticleStatus.DRAFT, 0L, 10))
                    .thenReturn(List.of(article));
            when(articleMapper.countByAuthorIdAndStatus(AUTHOR_ID, ArticleStatus.DRAFT)).thenReturn(1L);
            when(responseMapper.batchToTagResponsesMap(List.of(ARTICLE_UUID))).thenReturn(tagMap);
            when(responseMapper.toSummaryResponse(article, tagMap)).thenReturn(summary);

            PageResult<ArticleSummaryResponse> result =
                    querySubService.getMyArticles(AUTHOR_ID, 1, 10, ArticleStatus.DRAFT);

            assertThat(result.getRecords()).containsExactly(summary);
            assertThat(result.getTotal()).isEqualTo(1L);
            verify(articleMapper).findByAuthorIdAndStatus(AUTHOR_ID, ArticleStatus.DRAFT, 0L, 10);
            verify(articleMapper).countByAuthorIdAndStatus(AUTHOR_ID, ArticleStatus.DRAFT);
            verify(articleMapper, never()).findByAuthorIdPaged(any(), anyLong(), anyInt());
        }
    }

    @Nested
    @DisplayName("getPendingArticles")
    class GetPendingArticlesTests {

        @Test
        @DisplayName("returns mapped pending review page")
        void getPendingArticles_success() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            ArticleSummaryResponse summary = summaryResponse(article);
            Map<UUID, List<TagSummaryResponse>> tagMap = Map.of(ARTICLE_UUID, List.of());
            when(articleMapper.findPendingReviewPage(0L, 10)).thenReturn(List.of(article));
            when(articleMapper.countPendingReview()).thenReturn(1L);
            when(responseMapper.batchToTagResponsesMap(List.of(ARTICLE_UUID))).thenReturn(tagMap);
            when(responseMapper.toSummaryResponse(article, tagMap)).thenReturn(summary);

            PageResult<ArticleSummaryResponse> result = querySubService.getPendingArticles(1, 10);

            assertThat(result.getRecords()).containsExactly(summary);
            assertThat(result.getTotal()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("findIdByUuid")
    class FindIdByUuidTests {

        @Test
        @DisplayName("delegates to mapper")
        void findIdByUuid_delegatesToMapper() {
            when(articleMapper.findIdByUuid(ARTICLE_UUID)).thenReturn(ARTICLE_ID);

            Long result = querySubService.findIdByUuid(ARTICLE_UUID);

            assertThat(result).isEqualTo(ARTICLE_ID);
            verify(articleMapper).findIdByUuid(ARTICLE_UUID);
        }
    }

    @Nested
    @DisplayName("findByUuid")
    class FindByUuidTests {

        @Test
        @DisplayName("delegates to repository")
        void findByUuid_delegatesToRepository() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            Optional<Article> result = querySubService.findByUuid(ARTICLE_UUID);

            assertThat(result).containsSame(article);
            verify(articleRepository).findByUuid(ARTICLE_UUID);
            verifyNoInteractions(entityFinder);
        }
    }

    @Nested
    @DisplayName("cross-module reads")
    class OtherCrossModuleTests {

        @Test
        @DisplayName("findById delegates to repository")
        void findById_delegatesToRepository() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(article));

            Optional<Article> result = querySubService.findById(ARTICLE_ID);

            assertThat(result).containsSame(article);
            verify(articleRepository).findById(ARTICLE_ID);
        }

        @Test
        @DisplayName("findByIds returns repository iteration in list")
        void findByIds_delegatesToRepository() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleRepository.findAllById(List.of(ARTICLE_ID))).thenReturn(List.of(article));

            List<Article> result = querySubService.findByIds(List.of(ARTICLE_ID));

            assertThat(result).containsExactly(article);
            verify(articleRepository).findAllById(List.of(ARTICLE_ID));
        }

        @Test
        @DisplayName("findByIds returns empty list for null or empty input")
        void findByIds_emptyInput_returnsEmptyList() {
            assertThat(querySubService.findByIds(null)).isEmpty();
            assertThat(querySubService.findByIds(List.of())).isEmpty();
            verify(articleRepository, never()).findAllById(anyList());
        }

        @Test
        @DisplayName("findBySeriesIdOrderByPosition delegates to mapper")
        void findBySeriesIdOrderByPosition_delegatesToMapper() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleMapper.findBySeriesIdOrderByPosition(SERIES_ID)).thenReturn(List.of(article));

            List<Article> result = querySubService.findBySeriesIdOrderByPosition(SERIES_ID);

            assertThat(result).containsExactly(article);
            verify(articleMapper).findBySeriesIdOrderByPosition(SERIES_ID);
        }

        @Test
        @DisplayName("getArticleSummariesByIds maps each found article")
        void getArticleSummariesByIds_mapsEachFoundArticle() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            ArticleSummaryResponse summary = summaryResponse(article);
            Map<UUID, List<TagSummaryResponse>> tagMap = Map.of(ARTICLE_UUID, List.of());
            when(articleRepository.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
            when(responseMapper.batchToTagResponsesMap(List.of(ARTICLE_UUID))).thenReturn(tagMap);
            when(responseMapper.toSummaryResponse(article, tagMap)).thenReturn(summary);

            List<ArticleSummaryResponse> result = querySubService.getArticleSummariesByIds(List.of(ARTICLE_ID));

            assertThat(result).containsExactly(summary);
            verify(articleRepository).findById(ARTICLE_ID);
            verify(responseMapper).toSummaryResponse(article, tagMap);
        }

        @Test
        @DisplayName("getArticleSummariesByIds returns empty list for null or empty input")
        void getArticleSummariesByIds_emptyInput_returnsEmptyList() {
            assertThat(querySubService.getArticleSummariesByIds(null)).isEmpty();
            assertThat(querySubService.getArticleSummariesByIds(List.of())).isEmpty();
            verify(articleRepository, never()).findById(any());
        }
    }
}
