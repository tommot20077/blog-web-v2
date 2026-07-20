package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.model.dto.response.EditorArticleResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleService facade")
class ArticleServiceTest {

    @Mock
    private ArticleViewSubService articleViewSubService;

    @Mock
    private ArticleCommandSubService commandSubService;

    @Mock
    private ArticleQuerySubService querySubService;

    private ArticleServiceImpl articleService;

    private static final Long AUTHOR_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long ARTICLE_ID = 11L;
    private static final Long SERIES_ID = 99L;
    private static final UUID ARTICLE_UUID = UUID.randomUUID();
    private static final String CLIENT_IP = "127.0.0.1";

    @BeforeEach
    void setUp() {
        articleService = new ArticleServiceImpl(articleViewSubService, commandSubService, querySubService);
    }

    private Article buildArticle() {
        Article article = new Article();
        article.setId(ARTICLE_ID);
        article.setUuid(ARTICLE_UUID);
        article.setStatus(ArticleStatus.PUBLISHED);
        return article;
    }

    @Nested
    @DisplayName("query coordination")
    class QueryCoordinationTests {

        @Test
        @DisplayName("getArticleByUuid queries before recording view")
        void getArticleByUuid_queriesBeforeRecordingView() {
            ArticleResponse expected = ArticleResponse.builder()
                    .uuid(ARTICLE_UUID)
                    .status(ArticleStatus.PUBLISHED)
                    .build();
            when(querySubService.getArticleByUuid(ARTICLE_UUID, OTHER_USER_ID, Role.AUTHOR)).thenReturn(expected);

            ArticleResponse actual =
                    articleService.getArticleByUuid(ARTICLE_UUID, OTHER_USER_ID, Role.AUTHOR, CLIENT_IP);

            assertThat(actual).isSameAs(expected);
            InOrder inOrder = inOrder(querySubService, articleViewSubService);
            inOrder.verify(querySubService).getArticleByUuid(ARTICLE_UUID, OTHER_USER_ID, Role.AUTHOR);
            inOrder.verify(articleViewSubService).recordView(ARTICLE_UUID, ArticleStatus.PUBLISHED, CLIENT_IP);
        }

        @Test
        @DisplayName("getArticleByUuid does not record view when query throws")
        void getArticleByUuid_queryThrows_doesNotRecordView() {
            BusinessException error = new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
            when(querySubService.getArticleByUuid(ARTICLE_UUID, OTHER_USER_ID, Role.AUTHOR)).thenThrow(error);

            assertThatThrownBy(
                    () -> articleService.getArticleByUuid(ARTICLE_UUID, OTHER_USER_ID, Role.AUTHOR, CLIENT_IP))
                    .isSameAs(error);
            verifyNoInteractions(articleViewSubService);
        }

        @Test
        @DisplayName("getArticleBySlug queries before recording response uuid")
        void getArticleBySlug_queriesBeforeRecordingResponseUuid() {
            UUID responseUuid = UUID.randomUUID();
            ArticleResponse expected = ArticleResponse.builder()
                    .uuid(responseUuid)
                    .status(ArticleStatus.PUBLISHED)
                    .build();
            when(querySubService.getArticleBySlug("slug", null, null)).thenReturn(expected);

            ArticleResponse actual = articleService.getArticleBySlug("slug", null, null, CLIENT_IP);

            assertThat(actual).isSameAs(expected);
            InOrder inOrder = inOrder(querySubService, articleViewSubService);
            inOrder.verify(querySubService).getArticleBySlug("slug", null, null);
            inOrder.verify(articleViewSubService).recordView(responseUuid, ArticleStatus.PUBLISHED, CLIENT_IP);
        }

        @Test
        @DisplayName("getArticleBySlug does not record view when query throws")
        void getArticleBySlug_queryThrows_doesNotRecordView() {
            BusinessException error = new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
            when(querySubService.getArticleBySlug("missing", null, null)).thenThrow(error);

            assertThatThrownBy(() -> articleService.getArticleBySlug("missing", null, null, CLIENT_IP))
                    .isSameAs(error);
            verifyNoInteractions(articleViewSubService);
        }
    }

    @Nested
    @DisplayName("query delegation")
    class QueryDelegationTests {

        @Test
        @DisplayName("getArticleForEdit delegates to querySubService")
        void getArticleForEdit_delegatesToQuerySubService() {
            EditorArticleResponse expected = EditorArticleResponse.builder().uuid(ARTICLE_UUID).build();
            when(querySubService.getArticleForEdit(ARTICLE_UUID, AUTHOR_ID)).thenReturn(expected);

            EditorArticleResponse actual = articleService.getArticleForEdit(ARTICLE_UUID, AUTHOR_ID);

            assertThat(actual).isSameAs(expected);
            verify(querySubService).getArticleForEdit(ARTICLE_UUID, AUTHOR_ID);
        }

        @Test
        @DisplayName("getPublishedArticles delegates to querySubService")
        void getPublishedArticles_delegatesToQuerySubService() {
            PageResult<ArticleSummaryResponse> expected = PageResult.of(1, 10, 0L, List.of());
            when(querySubService.getPublishedArticles(1, 10)).thenReturn(expected);

            PageResult<ArticleSummaryResponse> actual = articleService.getPublishedArticles(1, 10);

            assertThat(actual).isSameAs(expected);
            verify(querySubService).getPublishedArticles(1, 10);
        }

        @Test
        @DisplayName("getPublishedArticlesByCategorySlug delegates to querySubService")
        void getPublishedArticlesByCategorySlug_delegatesToQuerySubService() {
            PageResult<ArticleSummaryResponse> expected = PageResult.of(1, 10, 0L, List.of());
            when(querySubService.getPublishedArticlesByCategorySlug("tech", 1, 10)).thenReturn(expected);

            PageResult<ArticleSummaryResponse> actual =
                    articleService.getPublishedArticlesByCategorySlug("tech", 1, 10);

            assertThat(actual).isSameAs(expected);
            verify(querySubService).getPublishedArticlesByCategorySlug("tech", 1, 10);
        }

        @Test
        @DisplayName("getMyArticles delegates to querySubService")
        void getMyArticles_delegatesToQuerySubService() {
            PageResult<ArticleSummaryResponse> expected = PageResult.of(1, 10, 0L, List.of());
            when(querySubService.getMyArticles(AUTHOR_ID, 1, 10, ArticleStatus.DRAFT)).thenReturn(expected);

            PageResult<ArticleSummaryResponse> actual =
                    articleService.getMyArticles(AUTHOR_ID, 1, 10, ArticleStatus.DRAFT);

            assertThat(actual).isSameAs(expected);
            verify(querySubService).getMyArticles(AUTHOR_ID, 1, 10, ArticleStatus.DRAFT);
        }

        @Test
        @DisplayName("getPendingArticles delegates to querySubService")
        void getPendingArticles_delegatesToQuerySubService() {
            PageResult<ArticleSummaryResponse> expected = PageResult.of(1, 10, 0L, List.of());
            when(querySubService.getPendingArticles(1, 10)).thenReturn(expected);

            PageResult<ArticleSummaryResponse> actual = articleService.getPendingArticles(1, 10);

            assertThat(actual).isSameAs(expected);
            verify(querySubService).getPendingArticles(1, 10);
        }

        @Test
        @DisplayName("findIdByUuid delegates to querySubService")
        void findIdByUuid_delegatesToQuerySubService() {
            when(querySubService.findIdByUuid(ARTICLE_UUID)).thenReturn(ARTICLE_ID);

            Long actual = articleService.findIdByUuid(ARTICLE_UUID);

            assertThat(actual).isEqualTo(ARTICLE_ID);
            verify(querySubService).findIdByUuid(ARTICLE_UUID);
        }

        @Test
        @DisplayName("findById delegates to querySubService")
        void findById_delegatesToQuerySubService() {
            Article article = buildArticle();
            when(querySubService.findById(ARTICLE_ID)).thenReturn(Optional.of(article));

            Optional<Article> actual = articleService.findById(ARTICLE_ID);

            assertThat(actual).containsSame(article);
            verify(querySubService).findById(ARTICLE_ID);
        }

        @Test
        @DisplayName("findByUuid delegates to querySubService")
        void findByUuid_delegatesToQuerySubService() {
            Article article = buildArticle();
            when(querySubService.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            Optional<Article> actual = articleService.findByUuid(ARTICLE_UUID);

            assertThat(actual).containsSame(article);
            verify(querySubService).findByUuid(ARTICLE_UUID);
        }

        @Test
        @DisplayName("findByIds delegates to querySubService")
        void findByIds_delegatesToQuerySubService() {
            Article article = buildArticle();
            when(querySubService.findByIds(List.of(ARTICLE_ID))).thenReturn(List.of(article));

            List<Article> actual = articleService.findByIds(List.of(ARTICLE_ID));

            assertThat(actual).containsExactly(article);
            verify(querySubService).findByIds(List.of(ARTICLE_ID));
        }

        @Test
        @DisplayName("findBySeriesIdOrderByPosition delegates to querySubService")
        void findBySeriesIdOrderByPosition_delegatesToQuerySubService() {
            Article article = buildArticle();
            when(querySubService.findBySeriesIdOrderByPosition(SERIES_ID)).thenReturn(List.of(article));

            List<Article> actual = articleService.findBySeriesIdOrderByPosition(SERIES_ID);

            assertThat(actual).containsExactly(article);
            verify(querySubService).findBySeriesIdOrderByPosition(SERIES_ID);
        }

        @Test
        @DisplayName("getArticleSummariesByIds delegates to querySubService")
        void getArticleSummariesByIds_delegatesToQuerySubService() {
            ArticleSummaryResponse summary = ArticleSummaryResponse.builder().uuid(ARTICLE_UUID).build();
            when(querySubService.getArticleSummariesByIds(List.of(ARTICLE_ID))).thenReturn(List.of(summary));

            List<ArticleSummaryResponse> actual = articleService.getArticleSummariesByIds(List.of(ARTICLE_ID));

            assertThat(actual).containsExactly(summary);
            verify(querySubService).getArticleSummariesByIds(List.of(ARTICLE_ID));
        }
    }

    @Nested
    @DisplayName("command delegation")
    class CommandDelegationTests {

        @Test
        @DisplayName("createArticle delegates to commandSubService")
        void createArticle_delegatesToCommandSubService() {
            CreateArticleRequest request = new CreateArticleRequest();
            EditorArticleResponse expected = EditorArticleResponse.builder().uuid(ARTICLE_UUID).build();
            when(commandSubService.createArticle(AUTHOR_ID, request)).thenReturn(expected);

            EditorArticleResponse actual = articleService.createArticle(AUTHOR_ID, request);

            assertThat(actual).isSameAs(expected);
            verify(commandSubService).createArticle(AUTHOR_ID, request);
        }

        @Test
        @DisplayName("updateArticle delegates to commandSubService")
        void updateArticle_delegatesToCommandSubService() {
            UpdateArticleRequest request = new UpdateArticleRequest();
            EditorArticleResponse expected = EditorArticleResponse.builder().uuid(ARTICLE_UUID).build();
            when(commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request)).thenReturn(expected);

            EditorArticleResponse actual = articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            assertThat(actual).isSameAs(expected);
            verify(commandSubService).updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);
        }

        @Test
        @DisplayName("deleteArticle delegates to commandSubService")
        void deleteArticle_delegatesToCommandSubService() {
            articleService.deleteArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            verify(commandSubService).deleteArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);
        }

        @Test
        @DisplayName("publishArticle delegates to commandSubService")
        void publishArticle_delegatesToCommandSubService() {
            ArticleResponse expected = ArticleResponse.builder().uuid(ARTICLE_UUID).build();
            when(commandSubService.publishArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID)).thenReturn(expected);

            ArticleResponse actual = articleService.publishArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID);

            assertThat(actual).isSameAs(expected);
            verify(commandSubService).publishArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID);
        }

        @Test
        @DisplayName("rejectArticle delegates to commandSubService")
        void rejectArticle_delegatesToCommandSubService() {
            ArticleResponse expected = ArticleResponse.builder().uuid(ARTICLE_UUID).build();
            when(commandSubService.rejectArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID, "reason")).thenReturn(expected);

            ArticleResponse actual = articleService.rejectArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID, "reason");

            assertThat(actual).isSameAs(expected);
            verify(commandSubService).rejectArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID, "reason");
        }

        @Test
        @DisplayName("submitForReview delegates to commandSubService")
        void submitForReview_delegatesToCommandSubService() {
            ArticleResponse expected = ArticleResponse.builder().uuid(ARTICLE_UUID).build();
            when(commandSubService.submitForReview(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID)).thenReturn(expected);

            ArticleResponse actual = articleService.submitForReview(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            assertThat(actual).isSameAs(expected);
            verify(commandSubService).submitForReview(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);
        }

        @Test
        @DisplayName("withdrawArticle delegates to commandSubService")
        void withdrawArticle_delegatesToCommandSubService() {
            ArticleResponse expected = ArticleResponse.builder().uuid(ARTICLE_UUID).build();
            when(commandSubService.withdrawArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID)).thenReturn(expected);

            ArticleResponse actual = articleService.withdrawArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            assertThat(actual).isSameAs(expected);
            verify(commandSubService).withdrawArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);
        }

        @Test
        @DisplayName("incrementCommentCount delegates to commandSubService")
        void incrementCommentCount_delegatesToCommandSubService() {
            articleService.incrementCommentCount(ARTICLE_ID);

            verify(commandSubService).incrementCommentCount(ARTICLE_ID);
        }

        @Test
        @DisplayName("decrementCommentCount delegates to commandSubService")
        void decrementCommentCount_delegatesToCommandSubService() {
            articleService.decrementCommentCount(ARTICLE_ID);

            verify(commandSubService).decrementCommentCount(ARTICLE_ID);
        }

        @Test
        @DisplayName("incrementLikeCount delegates to commandSubService")
        void incrementLikeCount_delegatesToCommandSubService() {
            articleService.incrementLikeCount(ARTICLE_ID);

            verify(commandSubService).incrementLikeCount(ARTICLE_ID);
        }

        @Test
        @DisplayName("decrementLikeCount delegates to commandSubService")
        void decrementLikeCount_delegatesToCommandSubService() {
            articleService.decrementLikeCount(ARTICLE_ID);

            verify(commandSubService).decrementLikeCount(ARTICLE_ID);
        }

        @Test
        @DisplayName("updateSeriesAssignment delegates to commandSubService")
        void updateSeriesAssignment_delegatesToCommandSubService() {
            articleService.updateSeriesAssignment(ARTICLE_ID, SERIES_ID, 2);

            verify(commandSubService).updateSeriesAssignment(ARTICLE_ID, SERIES_ID, 2);
        }
    }
}
