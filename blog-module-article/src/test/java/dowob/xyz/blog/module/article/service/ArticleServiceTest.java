package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
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
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.module.article.event.ArticlePublishedEvent;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArticleService 單元測試
 *
 * @author Yuan
 * @version 1.0
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleService 單元測試")
class ArticleServiceTest {

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private UserFacade userFacade;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private ArticleServiceImpl articleService;

    /**
     * 預設測試資料
     */
    private static final Long AUTHOR_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final UUID AUTHOR_UUID = UUID.randomUUID();
    private static final UUID ARTICLE_UUID = UUID.randomUUID();

    /**
     * 建立測試用文章
     */
    private Article buildArticle(ArticleStatus status) {
        Article article = new Article();
        article.setId(1L);
        article.setUuid(ARTICLE_UUID);
        article.setAuthorId(AUTHOR_ID);
        article.setTitle("測試標題");
        article.setContent("測試內容");
        article.setSummary("測試摘要");
        article.setSlug("test-slug-abcd1234");
        article.setStatus(status);
        article.setViewCount(0L);
        article.setLikeCount(0L);
        article.setCommentCount(0);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        return article;
    }

    @BeforeEach
    void setUp() {
        when(userFacade.getUserUuidById(AUTHOR_ID)).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(AUTHOR_ID)).thenReturn(Optional.of("TestAuthor"));
    }

    /**
     * 建立文章測試
     */
    @Nested
    @DisplayName("createArticle")
    class CreateArticleTests {

        @Test
        @DisplayName("正常：建立文章成功，回傳 ArticleResponse（含 authorNickname）")
        void createArticle_success() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("新文章");
            request.setContent("文章內容");
            request.setSummary("摘要");

            Article saved = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            ArticleResponse response = articleService.createArticle(AUTHOR_ID, request);

            assertThat(response).isNotNull();
            assertThat(response.getUuid()).isEqualTo(ARTICLE_UUID);
            assertThat(response.getStatus()).isEqualTo(ArticleStatus.DRAFT);
            assertThat(response.getAuthorNickname()).isEqualTo("TestAuthor");
            verify(articleRepository).save(any(Article.class));
        }

        @Test
        @DisplayName("正常：建立文章時明確指定 PENDING_REVIEW 狀態")
        void createArticle_withPendingReviewStatus() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("待審文章");
            request.setContent("內容");
            request.setStatus(ArticleStatus.PENDING_REVIEW);

            Article saved = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            ArticleResponse response = articleService.createArticle(AUTHOR_ID, request);

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.PENDING_REVIEW);
        }

        @Test
        @DisplayName("正常：getUserNicknameById 回傳 empty 時，authorNickname 為 null，不拋例外")
        void createArticle_userNicknameNotFound_authorNicknameIsNull() {
            when(userFacade.getUserNicknameById(AUTHOR_ID)).thenReturn(Optional.empty());

            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("暱稱查無用戶的文章");
            request.setContent("內容");

            Article saved = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            ArticleResponse response = articleService.createArticle(AUTHOR_ID, request);

            assertThat(response).isNotNull();
            assertThat(response.getAuthorNickname()).isNull();
        }
    }

    /**
     * 更新文章測試
     */
    @Nested
    @DisplayName("updateArticle")
    class UpdateArticleTests {

        @Test
        @DisplayName("正常：作者本人更新自己的文章")
        void updateArticle_authorCanUpdateOwn() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTitle("更新後標題");

            ArticleResponse response = articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            assertThat(response).isNotNull();
            verify(articleRepository).save(any(Article.class));
        }

        @Test
        @DisplayName("正常：ADMIN 可更新任何人的文章")
        void updateArticle_adminCanUpdateAny() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTitle("Admin 更新");

            ArticleResponse response = articleService.updateArticle(OTHER_USER_ID, Role.ADMIN, ARTICLE_UUID, request);

            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("異常：非作者嘗試更新他人文章 → ARTICLE_ACCESS_DENIED")
        void updateArticle_otherUserDenied() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTitle("試圖篡改");

            assertThatThrownBy(
                    () -> articleService.updateArticle(OTHER_USER_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage());
        }

        @Test
        @DisplayName("異常：文章不存在 → ARTICLE_NOT_FOUND")
        void updateArticle_articleNotFound() {
            when(articleRepository.findByUuid(any())).thenReturn(Optional.empty());

            UpdateArticleRequest request = new UpdateArticleRequest();

            assertThatThrownBy(
                    () -> articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, UUID.randomUUID(), request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("正常：UpdateArticleRequest 全欄位為 null 時，現有資料不變")
        void updateArticle_allNullRequest_preservesExistingData() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest emptyRequest = new UpdateArticleRequest();

            articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, emptyRequest);

            org.mockito.ArgumentCaptor<Article> articleCaptor =
                    org.mockito.ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(articleCaptor.capture());
            assertThat(articleCaptor.getValue().getTitle()).isEqualTo("測試標題");
            assertThat(articleCaptor.getValue().getContent()).isEqualTo("測試內容");
        }
    }

    /**
     * 發布文章測試（狀態轉換）
     */
    @Nested
    @DisplayName("publishArticle")
    class PublishArticleTests {

        @Test
        @DisplayName("正常：DRAFT → PUBLISHED 成功，並發送 MQ 事件")
        void publishArticle_draftToPublished() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            ArticleResponse response = articleService.publishArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_PUBLISHED),
                    any(ArticlePublishedEvent.class));
        }

        @Test
        @DisplayName("正常：PENDING_REVIEW → PUBLISHED (ADMIN 操作)")
        void publishArticle_pendingToPublished_byAdmin() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            ArticleResponse response = articleService.publishArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID);

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
        }

        @Test
        @DisplayName("異常：PENDING_REVIEW → PUBLISHED (非 ADMIN) → ARTICLE_ACCESS_DENIED")
        void publishArticle_pendingToPublished_byAuthorDenied() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            assertThatThrownBy(
                    () -> articleService.publishArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage());
        }

        @Test
        @DisplayName("異常：PUBLISHED → PUBLISHED (非法轉換) → ARTICLE_STATUS_TRANSITION_INVALID")
        void publishArticle_publishedToPublished_invalid() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            assertThatThrownBy(
                    () -> articleService.publishArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("異常：ARCHIVED → PUBLISHED (非法轉換) → ARTICLE_STATUS_TRANSITION_INVALID")
        void publishArticle_archivedToPublished_invalid() {
            Article article = buildArticle(ArticleStatus.ARCHIVED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            assertThatThrownBy(
                    () -> articleService.publishArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("異常：非法狀態轉換時，MQ 事件不應發送")
        void publishArticle_invalidTransition_noMqEventSent() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            assertThatThrownBy(
                    () -> articleService.publishArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());

            verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        }
    }

    /**
     * 取得文章詳情測試（可見性規則）
     */
    @Nested
    @DisplayName("getArticleByUuid")
    class GetArticleByUuidTests {

        @Test
        @DisplayName("正常：PUBLISHED 文章，匿名用戶可存取")
        void getArticle_published_anonymous() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            ArticleResponse response = articleService.getArticleByUuid(ARTICLE_UUID, null, null);

            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
            verify(articleMapper).incrementViewCount(article.getId());
        }

        @Test
        @DisplayName("正常：DRAFT 文章，作者本人可存取")
        void getArticle_draft_authorCanAccess() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            ArticleResponse response = articleService.getArticleByUuid(ARTICLE_UUID, AUTHOR_ID, Role.AUTHOR);

            assertThat(response).isNotNull();
            verify(articleMapper, never()).incrementViewCount(anyLong());
        }

        @Test
        @DisplayName("正常：DRAFT 文章，ADMIN 可存取")
        void getArticle_draft_adminCanAccess() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            ArticleResponse response = articleService.getArticleByUuid(ARTICLE_UUID, OTHER_USER_ID, Role.ADMIN);

            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("異常：DRAFT 文章，其他用戶存取 → ARTICLE_NOT_FOUND")
        void getArticle_draft_otherUserDenied() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            assertThatThrownBy(
                    () -> articleService.getArticleByUuid(ARTICLE_UUID, OTHER_USER_ID, Role.AUTHOR))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("正常：PENDING_REVIEW 文章，作者本人可存取，不增加瀏覽數")
        void getArticle_pendingReview_authorCanAccess_noViewCountIncrement() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            ArticleResponse response = articleService.getArticleByUuid(ARTICLE_UUID, AUTHOR_ID, Role.AUTHOR);

            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(ArticleStatus.PENDING_REVIEW);
            verify(articleMapper, never()).incrementViewCount(anyLong());
        }

        @Test
        @DisplayName("異常：PENDING_REVIEW 文章，其他用戶存取 → ARTICLE_NOT_FOUND")
        void getArticle_pendingReview_otherUserDenied() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            assertThatThrownBy(
                    () -> articleService.getArticleByUuid(ARTICLE_UUID, OTHER_USER_ID, Role.AUTHOR))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage());
        }
    }

    /**
     * 分頁查詢測試
     */
    @Nested
    @DisplayName("getPublishedArticles")
    class GetPublishedArticlesTests {

        @Test
        @DisplayName("正常：分頁查詢已發布文章")
        void getPublishedArticles_success() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleMapper.findPublishedPage(0L, 10)).thenReturn(List.of(article));
            when(articleMapper.countPublished()).thenReturn(1L);

            PageResult<ArticleSummaryResponse> result = articleService.getPublishedArticles(1, 10);

            assertThat(result).isNotNull();
            assertThat(result.getList()).hasSize(1);
            assertThat(result.getTotal()).isEqualTo(1L);
            assertThat(result.getPageNum()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常：第二頁查詢，偏移量正確")
        void getPublishedArticles_secondPage() {
            when(articleMapper.findPublishedPage(10L, 10)).thenReturn(List.of());
            when(articleMapper.countPublished()).thenReturn(5L);

            PageResult<ArticleSummaryResponse> result = articleService.getPublishedArticles(2, 10);

            assertThat(result.getList()).isEmpty();
            verify(articleMapper).findPublishedPage(10L, 10);
        }
    }

    /**
     * 駁回文章測試（REJECTED 狀態）
     */
    @Nested
    @DisplayName("rejectArticle")
    class RejectArticleTests {

        @Test
        @DisplayName("正常：PENDING_REVIEW → REJECTED (ADMIN 操作) 成功")
        void rejectArticle_pendingToRejected_byAdmin_success() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            ArticleResponse response = articleService.rejectArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID, "內容不符合規範");

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.REJECTED);
        }

        @Test
        @DisplayName("異常：PENDING_REVIEW → REJECTED (非 ADMIN) → ARTICLE_ACCESS_DENIED")
        void rejectArticle_pendingToRejected_byAuthor_denied() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            assertThatThrownBy(
                    () -> articleService.rejectArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, "原因"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage());
        }

        @Test
        @DisplayName("異常：PUBLISHED → REJECTED → ARTICLE_STATUS_TRANSITION_INVALID")
        void rejectArticle_publishedToRejected_invalid() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            assertThatThrownBy(
                    () -> articleService.rejectArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID, "原因"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("正常：REJECTED → DRAFT (作者可退回草稿) 成功")
        void rejectArticle_rejectedToDraft_byAuthor_success() {
            Article article = buildArticle(ArticleStatus.REJECTED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            ArticleResponse response = articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID,
                    buildUpdateRequest(ArticleStatus.DRAFT));

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.DRAFT);
        }

        /**
         * 建立指定狀態的更新請求
         *
         * @param status 目標狀態
         * @return UpdateArticleRequest
         */
        private UpdateArticleRequest buildUpdateRequest(ArticleStatus status) {
            UpdateArticleRequest req = new UpdateArticleRequest();
            req.setStatus(status);
            return req;
        }
    }

    /**
     * 取得待審文章測試（Admin）
     */
    @Nested
    @DisplayName("getPendingArticles")
    class GetPendingArticlesTests {

        @Test
        @DisplayName("正常：分頁查詢待審文章")
        void getPendingArticles_success() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(articleMapper.findPendingReviewPage(0L, 10)).thenReturn(List.of(article));
            when(articleMapper.countPendingReview()).thenReturn(1L);

            PageResult<ArticleSummaryResponse> result = articleService.getPendingArticles(1, 10);

            assertThat(result).isNotNull();
            assertThat(result.getList()).hasSize(1);
            assertThat(result.getTotal()).isEqualTo(1L);
        }
    }

    /**
     * 刪除文章測試
     */
    @Nested
    @DisplayName("deleteArticle")
    class DeleteArticleTests {

        @Test
        @DisplayName("正常：作者刪除自己的文章")
        void deleteArticle_authorDeleteOwn() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            articleService.deleteArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            verify(articleRepository).delete(article);
        }

        @Test
        @DisplayName("異常：非作者刪除他人文章 → ARTICLE_ACCESS_DENIED")
        void deleteArticle_otherUserDenied() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            assertThatThrownBy(() -> articleService.deleteArticle(OTHER_USER_ID, Role.AUTHOR, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage());

            verify(articleRepository, never()).delete(any());
        }
    }
}
