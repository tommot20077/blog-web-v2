package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.CategoryMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.Category;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.EditorArticleResponse;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleCommandSubService tests")
class ArticleCommandSubServiceTest {

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private ArticleEventPublisher articleEventPublisher;

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private TagFacade tagFacade;

    @Mock
    private ArticleMarkdownRenderer markdownRenderer;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ArticleEntityFinder entityFinder;

    @Mock
    private ArticleResponseMapper articleResponseMapper;

    private ArticleCommandSubService commandSubService;

    private static final Long AUTHOR_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final UUID ARTICLE_UUID = UUID.randomUUID();

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

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        when(markdownRenderer.render(any())).thenAnswer(inv -> {
            String md = inv.getArgument(0);
            if (md == null) return null;
            if (md.isEmpty()) return "";
            return "<p>" + md + "</p>\n";
        });
        when(markdownRenderer.toPlainText(any())).thenAnswer(inv -> {
            String md = inv.getArgument(0);
            if (md == null) return null;
            return md;
        });
        when(articleResponseMapper.toEditorResponse(any(Article.class)))
                .thenAnswer(inv -> toEditorResponse(inv.getArgument(0)));
        when(articleResponseMapper.toResponse(any(Article.class)))
                .thenAnswer(inv -> toResponse(inv.getArgument(0)));
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        doAnswer(inv -> {
            Consumer<org.springframework.transaction.TransactionStatus> action = inv.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        commandSubService = new ArticleCommandSubService(
                articleRepository,
                articleMapper,
                articleEventPublisher,
                categoryMapper,
                categoryRepository,
                tagFacade,
                markdownRenderer,
                transactionTemplate,
                entityFinder,
                articleResponseMapper);
    }

    private EditorArticleResponse toEditorResponse(Article article) {
        return EditorArticleResponse.builder()
                .uuid(article.getUuid())
                .title(article.getTitle())
                .summary(article.getSummary())
                .content(article.getContent())
                .coverImageUrl(article.getCoverImageUrl())
                .status(article.getStatus())
                .rejectReason(article.getRejectReason())
                .createdAt(article.getCreatedAt())
                .updatedAt(article.getUpdatedAt())
                .build();
    }

    private ArticleResponse toResponse(Article article) {
        return ArticleResponse.builder()
                .uuid(article.getUuid())
                .title(article.getTitle())
                .content(article.getContent())
                .contentHtml(article.getContentHtml())
                .summary(article.getSummary())
                .coverImageUrl(article.getCoverImageUrl())
                .status(article.getStatus())
                .viewCount(article.getViewCount())
                .likeCount(article.getLikeCount())
                .commentCount(article.getCommentCount())
                .slug(article.getSlug())
                .publishedAt(article.getPublishedAt())
                .rejectReason(article.getRejectReason())
                .createdAt(article.getCreatedAt())
                .updatedAt(article.getUpdatedAt())
                .build();
    }

    @Nested
    @DisplayName("createArticle")
    class CreateArticleTests {

        @Test
        @DisplayName("正常：建立文章成功，回傳 EditorArticleResponse（含 uuid、status）")
        void createArticle_success() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("新文章");
            request.setContent("文章內容");
            request.setSummary("摘要");

            Article saved = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            EditorArticleResponse response = commandSubService.createArticle(AUTHOR_ID, request);

            assertThat(response).isNotNull();
            assertThat(response.getUuid()).isEqualTo(ARTICLE_UUID);
            assertThat(response.getStatus()).isEqualTo(ArticleStatus.DRAFT);
            verify(articleRepository).save(any(Article.class));
        }

        @Test
        @DisplayName("安全：建立文章時傳入 PENDING_REVIEW 狀態應強制為 DRAFT")
        void createArticle_withPendingReviewStatus_shouldForceDraft() {
            // 傳入 PENDING_REVIEW 模擬試圖繞過草稿階段
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("待審文章");
            request.setContent("內容");
            request.setStatus(ArticleStatus.PENDING_REVIEW);

            // save 直接回傳傳入的 Article，以便驗證實際存入的狀態
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            commandSubService.createArticle(AUTHOR_ID, request);

            // 無論傳入何種狀態，儲存至 DB 前都必須是 DRAFT
            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ArticleStatus.DRAFT);
        }

        @Test
        @DisplayName("正常：getUserNicknameById 回傳 empty 時，建立文章不拋例外")
        void createArticle_userNicknameNotFound_noException() {

            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("暱稱查無用戶的文章");
            request.setContent("內容");

            Article saved = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            EditorArticleResponse response = commandSubService.createArticle(AUTHOR_ID, request);

            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("正常：建立文章時應自動產生 contentHtml（含 <p> 標籤）")
        void createArticle_shouldGenerateContentHtml() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("HTML 渲染測試");
            request.setContent("這是一段文字");
            request.setSummary("摘要");

            Article saved = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            EditorArticleResponse response = commandSubService.createArticle(AUTHOR_ID, request);

            org.mockito.ArgumentCaptor<Article> captor = org.mockito.ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getContentHtml()).isNotNull();
            assertThat(captor.getValue().getContentHtml()).contains("<p>");
        }

        @Test
        @DisplayName("正常：summary 為空白時，自動截取 Markdown 純文字前 200 字")
        void createArticle_shouldAutoGenerateSummary_whenSummaryIsBlank() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("自動摘要測試");
            request.setContent("這是一段測試內容，用來驗證自動摘要功能。");
            request.setSummary("");

            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            commandSubService.createArticle(AUTHOR_ID, request);

            org.mockito.ArgumentCaptor<Article> captor = org.mockito.ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            String summary = captor.getValue().getSummary();
            assertThat(summary).isNotNull();
            assertThat(summary).doesNotContain("<p>");
            assertThat(summary.length()).isLessThanOrEqualTo(200);
        }

        @Test
        @DisplayName("正常：summary 非空白時保留原值")
        void createArticle_shouldUseSummaryAsProvided_whenNotBlank() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("保留摘要測試");
            request.setContent("文章內容");
            request.setSummary("自訂摘要");

            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            commandSubService.createArticle(AUTHOR_ID, request);

            org.mockito.ArgumentCaptor<Article> captor = org.mockito.ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getSummary()).isEqualTo("自訂摘要");
        }

        @Test
        @DisplayName("正常：建立文章時指定 tagNames，應呼叫 tagFacade.syncArticleTags")
        void createArticle_withTagNames_callsSyncArticleTags() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("有標籤的文章");
            request.setContent("內容");
            request.setTagNames(List.of("Spring", "Java"));

            Article saved = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            UUID tagId1 = UUID.randomUUID();
            UUID tagId2 = UUID.randomUUID();
            when(tagFacade.findOrCreateTags(List.of("Spring", "Java")))
                    .thenReturn(List.of(
                            new TagInfo(tagId1, "Spring", "spring"),
                            new TagInfo(tagId2, "Java", "java")));

            commandSubService.createArticle(AUTHOR_ID, request);

            verify(tagFacade).findOrCreateTags(List.of("Spring", "Java"));
            verify(tagFacade).syncArticleTags(ARTICLE_UUID, List.of(tagId1, tagId2));
        }

        @Test
        @DisplayName("正常：建立文章時 tagNames 為 null，不呼叫 tagFacade 任何方法")
        void createArticle_withNullTagNames_doesNotCallTagFacade() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("無標籤文章");
            request.setContent("內容");
            /** tagNames 預設為 null */

            Article saved = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            commandSubService.createArticle(AUTHOR_ID, request);

            verify(tagFacade, never()).findOrCreateTags(any());
            verify(tagFacade, never()).syncArticleTags(any(), any());
        }

        @Test
        @DisplayName("邊界：建立文章時 tagNames 為空列表，不呼叫 tagFacade 任何方法")
        void createArticle_withEmptyTagNames_doesNotCallTagFacade() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("空標籤文章");
            request.setContent("內容");
            request.setTagNames(List.of());

            Article saved = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            commandSubService.createArticle(AUTHOR_ID, request);

            verify(tagFacade, never()).findOrCreateTags(any());
            verify(tagFacade, never()).syncArticleTags(any(), any());
        }

        @Test
        @DisplayName("正常：建立文章時指定 coverImageUrl，Article 應包含該 URL")
        void createArticle_withCoverImageUrl_setsOnArticle() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("有封面圖的文章");
            request.setContent("內容");
            request.setCoverImageUrl("https://example.com/cover.jpg");

            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            commandSubService.createArticle(AUTHOR_ID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getCoverImageUrl()).isEqualTo("https://example.com/cover.jpg");
        }

        @Test
        @DisplayName("邊界：建立文章時 coverImageUrl 為 null，Article 的 coverImageUrl 應為 null")
        void createArticle_withNullCoverImageUrl_doesNotSetCoverImageUrl() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("無封面文章");
            request.setContent("內容");
            /** coverImageUrl 預設為 null */

            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            commandSubService.createArticle(AUTHOR_ID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getCoverImageUrl()).isNull();
        }

        @Test
        @DisplayName("建立文章時傳入 PUBLISHED 狀態應強制為 DRAFT")
        void createArticle_withPublishedStatus_shouldForceDraft() {
            // 建立傳入 PUBLISHED 狀態的請求，模擬惡意繞過審核流程
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("惡意文章");
            request.setContent("試圖繞過審核直接發布");
            request.setStatus(ArticleStatus.PUBLISHED);

            // save 直接回傳傳入的 Article，以便驗證實際存入的狀態
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            commandSubService.createArticle(AUTHOR_ID, request);

            // 捕捉實際傳入 save() 的 Article，驗證 status 已被強制覆寫為 DRAFT
            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus())
                    .as("即使 request 傳入 PUBLISHED，實際儲存的 Article 狀態必須為 DRAFT")
                    .isEqualTo(ArticleStatus.DRAFT);
        }
    }

    @Nested
    @DisplayName("updateArticle")
    class UpdateArticleTests {

        @Test
        @DisplayName("正常：作者本人更新自己的文章")
        void updateArticle_authorCanUpdateOwn() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTitle("更新後標題");

            EditorArticleResponse response = commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            assertThat(response).isNotNull();
            verify(articleRepository).save(any(Article.class));
        }

        @Test
        @DisplayName("正常：ADMIN 可更新任何人的文章")
        void updateArticle_adminCanUpdateAny() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTitle("Admin 更新");

            EditorArticleResponse response = commandSubService.updateArticle(OTHER_USER_ID, Role.ADMIN, ARTICLE_UUID, request);

            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("異常：非作者嘗試更新他人文章 → ARTICLE_ACCESS_DENIED")
        void updateArticle_otherUserDenied() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTitle("試圖篡改");

            assertThatThrownBy(
                    () -> commandSubService.updateArticle(OTHER_USER_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage());
        }

        @Test
        @DisplayName("異常：文章不存在 → ARTICLE_NOT_FOUND")
        void updateArticle_articleNotFound() {
            when(entityFinder.findByUuidOrThrow(any())).thenThrow(new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND));

            UpdateArticleRequest request = new UpdateArticleRequest();

            assertThatThrownBy(
                    () -> commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, UUID.randomUUID(), request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("異常：updateArticle 時傳入不存在的 categoryUuid → CATEGORY_NOT_FOUND")
        void updateArticle_withNonExistentCategoryUuid_throwsCategoryNotFound() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UUID nonExistentUuid = UUID.randomUUID();
            when(categoryRepository.findByUuid(nonExistentUuid)).thenReturn(Optional.empty());

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setCategoryIds(List.of(nonExistentUuid));

            assertThatThrownBy(
                    () -> commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.CATEGORY_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("正常：UpdateArticleRequest 全欄位為 null 時，現有資料不變")
        void updateArticle_allNullRequest_preservesExistingData() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest emptyRequest = new UpdateArticleRequest();

            commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, emptyRequest);

            org.mockito.ArgumentCaptor<Article> articleCaptor = org.mockito.ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(articleCaptor.capture());
            assertThat(articleCaptor.getValue().getTitle()).isEqualTo("測試標題");
            assertThat(articleCaptor.getValue().getContent()).isEqualTo("測試內容");
        }

        @Test
        @DisplayName("異常：insertArticleCategory 拋出 DataIntegrityViolationException 時，syncCategories 應往外傳播")
        void updateArticle_propagatesException_whenInsertCategoryFails() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UUID catUuid = UUID.randomUUID();
            Category category = new Category();
            category.setId(5L);
            category.setUuid(catUuid);
            when(categoryRepository.findByUuid(catUuid)).thenReturn(Optional.of(category));
            doThrow(new DataIntegrityViolationException("duplicate key"))
                    .when(categoryMapper).insertArticleCategory(anyLong(), anyLong());

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setCategoryIds(List.of(catUuid));

            assertThatThrownBy(
                    () -> commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("正常：content 更新後，contentHtml 應重新渲染（含 <p> 標籤）")
        void updateArticle_shouldReRenderHtml_whenContentChanges() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setContent("更新後的 Markdown 內容");

            commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            org.mockito.ArgumentCaptor<Article> captor = org.mockito.ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getContentHtml()).isNotNull();
            assertThat(captor.getValue().getContentHtml()).contains("<p>");
        }

        @Test
        @DisplayName("異常：發生樂觀鎖例外時，應拋出 ARTICLE_CONCURRENT_UPDATE 業務例外")
        void updateArticle_concurrentUpdate_throwsException() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class)))
                    .thenThrow(new OptimisticLockingFailureException("Optimistic lock"));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTitle("並發更新");

            assertThatThrownBy(() -> commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_CONCURRENT_UPDATE.getMessage());
        }

        @Test
        @DisplayName("正常：updateArticle 時 tagNames 非 null 非空，應呼叫 syncArticleTags")
        void updateArticle_withTagNames_callsSyncArticleTags() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UUID tagId1 = UUID.randomUUID();
            when(tagFacade.findOrCreateTags(List.of("Spring")))
                    .thenReturn(List.of(new TagInfo(tagId1, "Spring", "spring")));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTagNames(List.of("Spring"));

            commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            verify(tagFacade).findOrCreateTags(List.of("Spring"));
            verify(tagFacade).syncArticleTags(ARTICLE_UUID, List.of(tagId1));
        }

        @Test
        @DisplayName("正常：updateArticle 時 tagNames 為 null，不呼叫 tagFacade 任何方法")
        void updateArticle_withNullTagNames_doesNotCallTagFacade() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            /** tagNames 為 null，不更新 */

            commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            verify(tagFacade, never()).findOrCreateTags(any());
            verify(tagFacade, never()).syncArticleTags(any(), any());
            verify(tagFacade, never()).deleteArticleTags(any());
        }

        @Test
        @DisplayName("邊界：updateArticle 時 tagNames 為空列表，應呼叫 deleteArticleTags（清除所有標籤）")
        void updateArticle_withEmptyTagNames_callsDeleteArticleTags() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTagNames(List.of());

            commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            verify(tagFacade).deleteArticleTags(ARTICLE_UUID);
            verify(tagFacade, never()).syncArticleTags(any(), any());
        }

        @Test
        @DisplayName("正常：updateArticle 時指定 coverImageUrl，Article 應包含該 URL")
        void updateArticle_withCoverImageUrl_setsOnArticle() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setCoverImageUrl("https://example.com/cover.jpg");

            commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getCoverImageUrl()).isEqualTo("https://example.com/cover.jpg");
        }
    }

    @Nested
    @DisplayName("publishArticle")
    class PublishArticleTests {

        @Test
        @DisplayName("正常：DRAFT → PUBLISHED 成功，並發送 MQ 事件")
        void publishArticle_draftToPublished() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            ArticleResponse response = commandSubService.publishArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
            verify(articleEventPublisher).publishPublished(any(Article.class), any());
        }

        @Test
        @DisplayName("正常：PENDING_REVIEW → PUBLISHED (ADMIN 操作)")
        void publishArticle_pendingToPublished_byAdmin() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            ArticleResponse response = commandSubService.publishArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID);

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
        }

        @Test
        @DisplayName("異常：PENDING_REVIEW → PUBLISHED (非 ADMIN) → ARTICLE_ACCESS_DENIED")
        void publishArticle_pendingToPublished_byAuthorDenied() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(
                    () -> commandSubService.publishArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage());
        }

        @Test
        @DisplayName("異常：PUBLISHED → PUBLISHED (非法轉換) → ARTICLE_STATUS_TRANSITION_INVALID")
        void publishArticle_publishedToPublished_invalid() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(
                    () -> commandSubService.publishArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("異常：ARCHIVED → PUBLISHED (非法轉換) → ARTICLE_STATUS_TRANSITION_INVALID")
        void publishArticle_archivedToPublished_invalid() {
            Article article = buildArticle(ArticleStatus.ARCHIVED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(
                    () -> commandSubService.publishArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("正常：發布事件包含 slug、摘要、純文字內容、作者資訊與標籤")
        void publishArticle_eventContainsEnrichedData() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));
            when(articleMapper.findTagsByArticleUuid(ARTICLE_UUID)).thenReturn(
                    List.of(new TagInfo(UUID.randomUUID(), "Spring", "spring")));

            commandSubService.publishArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            ArgumentCaptor<Article> articleCaptor = ArgumentCaptor.forClass(Article.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.List<TagInfo>> tagsCaptor = ArgumentCaptor.forClass(java.util.List.class);
            verify(articleEventPublisher).publishPublished(articleCaptor.capture(), tagsCaptor.capture());

            Article publishedArticle = articleCaptor.getValue();
            java.util.List<TagInfo> capturedTags = tagsCaptor.getValue();
            assertThat(publishedArticle.getSlug()).isEqualTo(article.getSlug());
            assertThat(publishedArticle.getSummary()).isEqualTo("測試摘要");
            assertThat(capturedTags).hasSize(1);
            assertThat(capturedTags.get(0).name()).isEqualTo("Spring");
        }

        @Test
        @DisplayName("異常：非法狀態轉換時，MQ 事件不應發送")
        void publishArticle_invalidTransition_noMqEventSent() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(
                    () -> commandSubService.publishArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());

            verify(articleEventPublisher, never()).publishPublished(any(), any());
            verify(articleEventPublisher, never()).publishContentChanged(any(), any());
        }
    }

    @Nested
    @DisplayName("rejectArticle")
    class RejectArticleTests {

        @Test
        @DisplayName("正常：PENDING_REVIEW → REJECTED (ADMIN 操作) 成功")
        void rejectArticle_pendingToRejected_byAdmin_success() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            ArticleResponse response = commandSubService.rejectArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID, "內容不符合規範");

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.REJECTED);
        }

        @Test
        @DisplayName("異常：PENDING_REVIEW → REJECTED (非 ADMIN) → ARTICLE_ACCESS_DENIED")
        void rejectArticle_pendingToRejected_byAuthor_denied() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(
                    () -> commandSubService.rejectArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, "原因"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage());
        }

        @Test
        @DisplayName("異常：PUBLISHED → REJECTED → ARTICLE_STATUS_TRANSITION_INVALID")
        void rejectArticle_publishedToRejected_invalid() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(
                    () -> commandSubService.rejectArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID, "原因"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("正常：REJECTED → DRAFT (作者可退回草稿) 成功")
        void rejectArticle_rejectedToDraft_byAuthor_success() {
            Article article = buildArticle(ArticleStatus.REJECTED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            EditorArticleResponse response = commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID,
                    buildUpdateRequest(ArticleStatus.DRAFT));

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.DRAFT);
        }

        @Test
        @DisplayName("正常：rejectArticle 應將 rejectReason 設定到文章實體")
        void rejectArticle_shouldPersistRejectReason() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            commandSubService.rejectArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID, "內容不符合規範");

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getRejectReason()).isEqualTo("內容不符合規範");
        }

        @Test
        @DisplayName("正常：rejectArticle 回應中包含 rejectReason")
        void rejectArticle_responseShouldContainRejectReason() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            ArticleResponse response = commandSubService.rejectArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID, "內容違規");

            assertThat(response.getRejectReason()).isEqualTo("內容違規");
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

    @Nested
    @DisplayName("deleteArticle")
    class DeleteArticleTests {

        private static final UUID CATEGORY_UUID = UUID.randomUUID();
        private static final UUID TAG_UUID = UUID.randomUUID();

        @Test
        @DisplayName("正常：作者刪除自己的文章")
        void deleteArticle_authorDeleteOwn() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleMapper.findCategoryUuidsByArticleId(article.getId())).thenReturn(List.of());
            when(articleMapper.findTagUuidsByArticleId(article.getId())).thenReturn(List.of());

            commandSubService.deleteArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            verify(articleRepository).delete(article);
        }

        @Test
        @DisplayName("異常：非作者刪除他人文章 → ARTICLE_ACCESS_DENIED")
        void deleteArticle_otherUserDenied() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> commandSubService.deleteArticle(OTHER_USER_ID, Role.AUTHOR, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage());

            verify(articleRepository, never()).delete(any());
        }

        @Test
        @DisplayName("正常：刪除文章時應發送 ArticleDeletedEvent（精準驗 4 params）")
        void deleteArticle_shouldPublishDeletedEvent() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleMapper.findCategoryUuidsByArticleId(article.getId())).thenReturn(List.of(CATEGORY_UUID));
            when(articleMapper.findTagUuidsByArticleId(article.getId())).thenReturn(List.of(TAG_UUID));

            commandSubService.deleteArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            verify(articleEventPublisher).publishDeleted(
                    eq(article),
                    eq(article.getSeriesId()),
                    eq(List.of(CATEGORY_UUID)),
                    eq(List.of(TAG_UUID))
            );
        }

        @Test
        @DisplayName("正常：delete 前讀 categoryIds/tagIds 並正確傳給 publisher")
        void deleteArticle_collectsCategoryAndTagIds_passesToPublisher() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            article.setSeriesId(50L);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleMapper.findCategoryUuidsByArticleId(article.getId())).thenReturn(List.of(CATEGORY_UUID));
            when(articleMapper.findTagUuidsByArticleId(article.getId())).thenReturn(List.of(TAG_UUID));

            commandSubService.deleteArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            verify(articleMapper).findCategoryUuidsByArticleId(article.getId());
            verify(articleMapper).findTagUuidsByArticleId(article.getId());
            verify(articleEventPublisher).publishDeleted(
                    eq(article),
                    eq(50L),
                    eq(List.of(CATEGORY_UUID)),
                    eq(List.of(TAG_UUID))
            );
        }

        @Test
        @DisplayName("邊界：刪除無 seriesId 的文章時 publisher 收到 null seriesId")
        void deleteArticle_withoutSeriesId_publisherReceivesNullSeriesId() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            // seriesId 為 null（buildArticle 預設未設置）
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleMapper.findCategoryUuidsByArticleId(article.getId())).thenReturn(List.of());
            when(articleMapper.findTagUuidsByArticleId(article.getId())).thenReturn(List.of());

            commandSubService.deleteArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            verify(articleRepository).delete(article);
            verify(articleEventPublisher).publishDeleted(
                    eq(article),
                    eq((Long) null),
                    eq(List.of()),
                    eq(List.of())
            );
        }
    }

    @Nested
    @DisplayName("submitForReview")
    class SubmitForReviewTests {

        @Test
        @DisplayName("正常：DRAFT → PENDING_REVIEW 成功")
        void submitForReview_draftToPendingReview_success() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            ArticleResponse response = commandSubService.submitForReview(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.PENDING_REVIEW);
        }

        @Test
        @DisplayName("異常：PUBLISHED → PENDING_REVIEW（非法轉換）→ ARTICLE_STATUS_TRANSITION_INVALID")
        void submitForReview_publishedToPendingReview_invalidTransition() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> commandSubService.submitForReview(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("異常：非作者提交他人文章 → ARTICLE_ACCESS_DENIED")
        void submitForReview_otherUserDenied() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> commandSubService.submitForReview(OTHER_USER_ID, Role.AUTHOR, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage());
        }
    }

    @Nested
    @DisplayName("updateArticle - Search Sync")
    class UpdateArticleSearchSyncTests {

        @Test
        @DisplayName("異常：PUBLISHED 狀態 → PUT 守衛阻擋，不發送 ArticleUpdatedEvent")
        void updatePublishedArticle_blockedByGuard_noEvent() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            article.setPublishedAt(LocalDateTime.now().minusDays(1));
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTitle("更新後標題");

            assertThatThrownBy(() -> commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_EDIT_NOT_ALLOWED.getMessage());

            verify(articleEventPublisher, never()).publishUpdated(any());
        }

        @Test
        @DisplayName("正常：更新 DRAFT 文章時，不應發送 ArticleUpdatedEvent；但仍發送 ContentChanged(SAVED)")
        void updateDraftArticle_shouldNotPublishUpdatedEvent_butShouldPublishContentChanged() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTitle("更新草稿標題");

            commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            verify(articleEventPublisher, never()).publishUpdated(any());
            verify(articleEventPublisher).publishContentChanged(any(), eq(ArticleContentChangedEvent.Action.SAVED));
        }
    }

    @Nested
    @DisplayName("狀態機邊界 - 合法轉換")
    class StateMachineLegalTransitionTests {

        @Test
        @DisplayName("異常：PUBLISHED 狀態 → PUT 狀態守衛阻擋 → ARTICLE_EDIT_NOT_ALLOWED")
        void updateArticle_published_blockedByGuard() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            article.setPublishedAt(LocalDateTime.now().minusDays(1));
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.ARCHIVED);

            assertThatThrownBy(() -> commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_EDIT_NOT_ALLOWED.getMessage());
        }

        @Test
        @DisplayName("異常：ARCHIVED 狀態 → PUT 狀態守衛阻擋 → ARTICLE_EDIT_NOT_ALLOWED")
        void updateArticle_archived_blockedByGuard() {
            Article article = buildArticle(ArticleStatus.ARCHIVED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.DRAFT);

            assertThatThrownBy(() -> commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_EDIT_NOT_ALLOWED.getMessage());
        }

        @Test
        @DisplayName("正常：updateArticle 更新 status → PUBLISHED 且 publishedAt 為 null，應自動設置 publishedAt")
        void updateArticle_statusToPublished_whenPublishedAtIsNull_setsPublishedAt() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            article.setPublishedAt(null);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.PUBLISHED);

            commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getPublishedAt()).isNotNull();
        }

        @Test
        @DisplayName("正常：publishArticle 時文章已有 publishedAt，不重設（保留首次發布時間）")
        void publishArticle_whenPublishedAtAlreadySet_doesNotReset() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            LocalDateTime originalPublishedAt = LocalDateTime.of(2023, 6, 15, 9, 30);
            article.setPublishedAt(originalPublishedAt);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));
            when(articleMapper.findTagsByArticleUuid(ARTICLE_UUID)).thenReturn(List.of());

            commandSubService.publishArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getPublishedAt()).isEqualTo(originalPublishedAt);
        }

        @Test
        @DisplayName("正常：publishArticle 應使用 save() 回傳的 entity 產生 response（確保 updatedAt 為最新）")
        void publishArticle_shouldUseSavedEntityForResponse() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            article.setUpdatedAt(LocalDateTime.of(2023, 1, 1, 0, 0));

            Article savedArticle = buildArticle(ArticleStatus.PUBLISHED);
            LocalDateTime savedUpdatedAt = LocalDateTime.of(2026, 3, 21, 12, 0);
            savedArticle.setUpdatedAt(savedUpdatedAt);

            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenReturn(savedArticle);
            when(articleMapper.findTagsByArticleUuid(ARTICLE_UUID)).thenReturn(List.of());

            ArticleResponse response = commandSubService.publishArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            assertThat(response.getUpdatedAt()).isEqualTo(savedUpdatedAt);
        }
    }

    @Nested
    @DisplayName("狀態機邊界 - 非法轉換")
    class StateMachineInvalidTransitionTests {

        @Test
        @DisplayName("異常：DRAFT → ARCHIVED 非法轉換 → ARTICLE_STATUS_TRANSITION_INVALID")
        void updateArticle_draftToArchived_invalidTransition() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.ARCHIVED);

            assertThatThrownBy(() -> commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("異常：PUBLISHED 狀態 → PUT 守衛阻擋（先於狀態機驗證）→ ARTICLE_EDIT_NOT_ALLOWED")
        void updateArticle_publishedToDraft_blockedByGuard() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.DRAFT);

            assertThatThrownBy(() -> commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_EDIT_NOT_ALLOWED.getMessage());
        }

        @Test
        @DisplayName("異常：REJECTED → PENDING_REVIEW 非法轉換 → ARTICLE_STATUS_TRANSITION_INVALID")
        void updateArticle_rejectedToPendingReview_invalidTransition() {
            Article article = buildArticle(ArticleStatus.REJECTED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.PENDING_REVIEW);

            assertThatThrownBy(() -> commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("異常：ARCHIVED 狀態 → PUT 守衛阻擋（先於狀態機驗證）→ ARTICLE_EDIT_NOT_ALLOWED")
        void updateArticle_archivedToPublished_blockedByGuard() {
            Article article = buildArticle(ArticleStatus.ARCHIVED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.PUBLISHED);

            assertThatThrownBy(() -> commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_EDIT_NOT_ALLOWED.getMessage());
        }

        @Test
        @DisplayName("異常：DRAFT → REJECTED 非法轉換 → ARTICLE_STATUS_TRANSITION_INVALID")
        void updateArticle_draftToRejected_invalidTransition() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.REJECTED);

            assertThatThrownBy(() -> commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("異常：PENDING_REVIEW 狀態 → PUT 守衛阻擋 → ARTICLE_EDIT_NOT_ALLOWED")
        void updateArticle_pendingReview_blockedByGuard() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.DRAFT);

            assertThatThrownBy(() -> commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_EDIT_NOT_ALLOWED.getMessage());
        }
    }

    @Nested
    @DisplayName("extractSummary 邊界")
    class ExtractSummaryBoundaryTests {

        @Test
        @DisplayName("邊界：content 為 null 且 summary 為 null，extractSummary 回傳 null")
        void createArticle_contentNull_summaryNull_extractSummaryReturnsNull() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("空內容文章");
            request.setContent(null);
            request.setSummary(null);

            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            commandSubService.createArticle(AUTHOR_ID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getSummary()).isNull();
        }

        @Test
        @DisplayName("邊界：content 超過 200 字，summary 自動截取前 200 字")
        void createArticle_longContent_summaryTruncatedTo200Chars() {
            String longContent = "a".repeat(300);
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("長內容文章");
            request.setContent(longContent);
            request.setSummary(null);

            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            commandSubService.createArticle(AUTHOR_ID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getSummary()).isNotNull();
            assertThat(captor.getValue().getSummary().length()).isLessThanOrEqualTo(200);
        }

        @Test
        @DisplayName("邊界：updateArticle 同時傳入 content 與 summary 時，summary 應以傳入值為主")
        void updateArticle_bothContentAndSummaryProvided_usesSummary() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setContent("新內容");
            request.setSummary("自訂摘要");

            commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getSummary()).isEqualTo("自訂摘要");
        }

        @Test
        @DisplayName("邊界：updateArticle 只更新 summary（content 為 null），應使用現有 content 計算自動摘要")
        void updateArticle_onlySummaryBlank_usesExistingContent() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            article.setContent("現有文章內容，用來自動生成摘要");
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setSummary("");  // 空白觸發自動摘要，content 為 null 時用現有 content

            commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getSummary()).isNotNull();
            assertThat(captor.getValue().getSummary()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("ArticleTagEvent - article.tagged 事件發送")
    class ArticleTagEventTests {

        @Test
        @DisplayName("正常：publishArticle 有 tags 時，應發送 ArticleTagEvent 至 article.tagged")
        void publishArticle_withTags_shouldSendArticleTagEvent() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UUID tagId1 = UUID.randomUUID();
            UUID tagId2 = UUID.randomUUID();
            when(articleMapper.findTagsByArticleUuid(ARTICLE_UUID)).thenReturn(
                    List.of(new TagInfo(tagId1, "Spring", "spring"),
                            new TagInfo(tagId2, "Java", "java")));

            commandSubService.publishArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            ArgumentCaptor<Article> articleCaptor2 = ArgumentCaptor.forClass(Article.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.List<UUID>> tagIdsCaptor = ArgumentCaptor.forClass(java.util.List.class);
            verify(articleEventPublisher).publishTagged(articleCaptor2.capture(), tagIdsCaptor.capture());

            assertThat(articleCaptor2.getValue().getUuid()).isEqualTo(ARTICLE_UUID);
            assertThat(tagIdsCaptor.getValue()).containsExactlyInAnyOrder(tagId1, tagId2);
        }

        @Test
        @DisplayName("邊界：publishArticle 無 tags 時，不發送 ArticleTagEvent")
        void publishArticle_withoutTags_shouldNotSendArticleTagEvent() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));
            when(articleMapper.findTagsByArticleUuid(ARTICLE_UUID)).thenReturn(List.of());

            commandSubService.publishArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            verify(articleEventPublisher, never()).publishTagged(any(), any());
        }

        @Test
        @DisplayName("正常：createArticle 有 tags 時，應發送 ArticleTagEvent 至 article.tagged")
        void createArticle_withTags_shouldSendArticleTagEvent() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("有標籤文章");
            request.setContent("內容");
            request.setTagNames(List.of("Spring", "Java"));

            Article saved = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            UUID tagId1 = UUID.randomUUID();
            UUID tagId2 = UUID.randomUUID();
            when(tagFacade.findOrCreateTags(List.of("Spring", "Java")))
                    .thenReturn(List.of(
                            new TagInfo(tagId1, "Spring", "spring"),
                            new TagInfo(tagId2, "Java", "java")));

            commandSubService.createArticle(AUTHOR_ID, request);

            ArgumentCaptor<Article> articleCaptorCreate = ArgumentCaptor.forClass(Article.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.List<UUID>> tagIdsCaptorCreate = ArgumentCaptor.forClass(java.util.List.class);
            verify(articleEventPublisher).publishTagged(articleCaptorCreate.capture(), tagIdsCaptorCreate.capture());

            assertThat(articleCaptorCreate.getValue().getUuid()).isEqualTo(ARTICLE_UUID);
            assertThat(tagIdsCaptorCreate.getValue()).containsExactlyInAnyOrder(tagId1, tagId2);
        }

        @Test
        @DisplayName("邊界：createArticle 無 tags 時，不發送 ArticleTagEvent")
        void createArticle_withoutTags_shouldNotSendArticleTagEvent() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("無標籤文章");
            request.setContent("內容");

            Article saved = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            commandSubService.createArticle(AUTHOR_ID, request);

            verify(articleEventPublisher, never()).publishTagged(any(), any());
        }

        @Test
        @DisplayName("正常：updateArticle 有 tags 時，應發送 ArticleTagEvent 至 article.tagged")
        void updateArticle_withTags_shouldSendArticleTagEvent() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UUID tagId1 = UUID.randomUUID();
            when(tagFacade.findOrCreateTags(List.of("Spring")))
                    .thenReturn(List.of(new TagInfo(tagId1, "Spring", "spring")));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTagNames(List.of("Spring"));

            commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            ArgumentCaptor<Article> articleCaptorUpdate = ArgumentCaptor.forClass(Article.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.List<UUID>> tagIdsCaptorUpdate = ArgumentCaptor.forClass(java.util.List.class);
            verify(articleEventPublisher).publishTagged(articleCaptorUpdate.capture(), tagIdsCaptorUpdate.capture());

            assertThat(articleCaptorUpdate.getValue().getUuid()).isEqualTo(ARTICLE_UUID);
            assertThat(tagIdsCaptorUpdate.getValue()).containsExactly(tagId1);
        }

        @Test
        @DisplayName("邊界：updateArticle tagNames 為 null 時，不發送 ArticleTagEvent")
        void updateArticle_withNullTagNames_shouldNotSendArticleTagEvent() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();

            commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            verify(articleEventPublisher, never()).publishTagged(any(), any());
        }
    }


    @Nested
    @DisplayName("createArticle command boundaries")
    class CreateArticleCommandBoundaryTests {

        @Test
        @DisplayName("XSS 安全：convertToHtml 委派給 ArticleMarkdownRenderer，應剝除 script 標籤（由 OWASP 處理）")
        void createArticle_convertToHtml_shouldEscapeScriptTags() {
            // 直接驗證 ArticleMarkdownRenderer 的 XSS 防護，由 ArticleMarkdownRendererTest 全面覆蓋
            // 此處驗證 ArticleServiceImpl 有正確委派給 markdownRenderer.render()
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("XSS 測試");
            request.setContent("<script>alert('xss')</script>這是正常文字");
            request.setSummary("摘要");

            when(markdownRenderer.render("<script>alert('xss')</script>這是正常文字"))
                    .thenReturn("這是正常文字");  // 模擬 OWASP 剝除 script 後的結果
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            commandSubService.createArticle(AUTHOR_ID, request);

            org.mockito.ArgumentCaptor<Article> captor = org.mockito.ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            String html = captor.getValue().getContentHtml();
            assertThat(html).isNotNull();
            assertThat(html).doesNotContain("<script>");
            assertThat(html).doesNotContain("</script>");
        }

        @Test
        @DisplayName("正常：createArticle 應自動產生 slug 並寫入 Article 實體")
        void createArticle_shouldGenerateSlug() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("Slug 測試文章");
            request.setContent("內容");

            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            commandSubService.createArticle(AUTHOR_ID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getSlug()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("incrementCommentCount")
    class IncrementCommentCountTests {

        @Test
        @DisplayName("delegates to ArticleMapper.incrementCommentCount")
        void incrementCommentCount_delegatesToMapper() {
            commandSubService.incrementCommentCount(1L);

            verify(articleMapper).incrementCommentCount(1L);
        }
    }

    @Nested
    @DisplayName("decrementCommentCount")
    class DecrementCommentCountTests {

        @Test
        @DisplayName("delegates to ArticleMapper.decrementCommentCount")
        void decrementCommentCount_delegatesToMapper() {
            commandSubService.decrementCommentCount(1L);

            verify(articleMapper).decrementCommentCount(1L);
        }
    }

    @Nested
    @DisplayName("incrementLikeCount")
    class IncrementLikeCountTests {

        @Test
        @DisplayName("delegates to ArticleMapper.incrementLikeCount")
        void incrementLikeCount_delegatesToMapper() {
            commandSubService.incrementLikeCount(1L);

            verify(articleMapper).incrementLikeCount(1L);
        }
    }

    @Nested
    @DisplayName("decrementLikeCount")
    class DecrementLikeCountTests {

        @Test
        @DisplayName("delegates to ArticleMapper.decrementLikeCount")
        void decrementLikeCount_delegatesToMapper() {
            commandSubService.decrementLikeCount(1L);

            verify(articleMapper).decrementLikeCount(1L);
        }
    }

    @Nested
    @DisplayName("updateSeriesAssignment")
    class OtherCrossModuleTests {

        @Test
        @DisplayName("updates series assignment when article exists")
        void updateSeriesAssignment_articleExists_updatesAndSaves() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findById(1L)).thenReturn(Optional.of(article));

            commandSubService.updateSeriesAssignment(1L, 10L, 2);

            assertThat(article.getSeriesId()).isEqualTo(10L);
            assertThat(article.getSeriesPosition()).isEqualTo(2);
            verify(articleRepository).save(article);
        }

        @Test
        @DisplayName("does nothing when article is missing")
        void updateSeriesAssignment_articleMissing_doesNotSave() {
            when(articleRepository.findById(1L)).thenReturn(Optional.empty());

            commandSubService.updateSeriesAssignment(1L, 10L, 2);

            verify(articleRepository, never()).save(any());
        }
    }

}
