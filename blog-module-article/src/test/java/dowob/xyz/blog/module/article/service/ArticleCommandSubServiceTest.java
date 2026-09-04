package dowob.xyz.blog.module.article.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
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
import dowob.xyz.blog.module.article.model.dto.response.TocEntry;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private FileFacade fileFacade;

    @Mock
    private ArticleMarkdownRenderer markdownRenderer;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ArticleEntityFinder entityFinder;

    @Mock
    private ArticleResponseMapper articleResponseMapper;

    private ObjectMapper objectMapper;

    /** 真實 codec：TOC 序列化行為本身是待驗證對象，不可 mock */
    private ArticleTocCodec articleTocCodec;

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
            if (md.isEmpty()) return new RenderResult("", List.of());
            return new RenderResult("<p>" + md + "</p>\n", List.of());
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

        /**
         * ArticleFileBinder 抽出後，ArticleCommandSubService 改依賴 ArticleFileBinder
         * 而非直接依賴 FileFacade。這裡用「真實」的 ArticleFileBinder 包裝被 mock 的
         * fileFacade，讓既有 FileBindingTests 的 verify(fileFacade)... 斷言不必修改就能
         * 繼續通過——正則擷取與 try/catch 邏輯真的被執行，只是最終外部呼叫落在 mock 上。
         */
        ArticleFileBinder articleFileBinder = new ArticleFileBinder(fileFacade);

        objectMapper = new ObjectMapper();
        articleTocCodec = new ArticleTocCodec(objectMapper);

        commandSubService = new ArticleCommandSubService(
                articleRepository,
                articleMapper,
                articleEventPublisher,
                categoryMapper,
                categoryRepository,
                tagFacade,
                articleFileBinder,
                markdownRenderer,
                transactionTemplate,
                entityFinder,
                articleResponseMapper,
                articleTocCodec);
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

        @Test
        @DisplayName("正常：建立文章成功後，應發送 ContentChanged(SAVED) 事件（供 version 模組建立初版快照，BUG-003 回歸）")
        void createArticle_shouldPublishContentChangedSaved() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("初版快照測試");
            request.setContent("初版內容");

            Article saved = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            commandSubService.createArticle(AUTHOR_ID, request);

            verify(articleEventPublisher).publishContentChanged(eq(saved), eq(ArticleContentChangedEvent.Action.SAVED));
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
    @DisplayName("TOC 持久化")
    class TocPersistenceTests {

        @Test
        @DisplayName("正常：create 文章含 heading 時，toc 應持久化為對應的 JSON 字串")
        void create_含heading_持久化toc為JSON() throws Exception {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("含標題文章");
            request.setContent("## 安裝步驟\n內容");

            List<TocEntry> tocEntries = List.of(new TocEntry("heading-安裝步驟", "安裝步驟", 2));
            when(markdownRenderer.render(request.getContent()))
                    .thenReturn(new RenderResult("<h2 id=\"heading-安裝步驟\">安裝步驟</h2>", tocEntries));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            commandSubService.createArticle(AUTHOR_ID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            String toc = captor.getValue().getToc();
            assertThat(toc).isNotNull();
            List<TocEntry> parsed = objectMapper.readValue(toc, new TypeReference<List<TocEntry>>() {});
            assertThat(parsed).containsExactly(new TocEntry("heading-安裝步驟", "安裝步驟", 2));
        }

        @Test
        @DisplayName("邊界：create 文章無 heading 時，toc 應持久化為空陣列字串 []（非 null）")
        void create_無heading_持久化空陣列字串() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("無標題文章");
            request.setContent("純文字內容，無任何標題");

            when(markdownRenderer.render(request.getContent()))
                    .thenReturn(new RenderResult("<p>純文字內容，無任何標題</p>\n", List.of()));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            commandSubService.createArticle(AUTHOR_ID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getToc()).isEqualTo("[]");
        }

        @Test
        @DisplayName("正常：update 更新 content 時，toc 應重新計算並持久化新的 JSON")
        void update_更新內容時重算toc() throws Exception {
            Article article = buildArticle(ArticleStatus.DRAFT);
            article.setToc("[]");
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setContent("## 新章節\n內容");

            List<TocEntry> newToc = List.of(new TocEntry("heading-新章節", "新章節", 2));
            when(markdownRenderer.render(request.getContent()))
                    .thenReturn(new RenderResult("<h2 id=\"heading-新章節\">新章節</h2>", newToc));

            commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            String toc = captor.getValue().getToc();
            assertThat(toc).isNotEqualTo("[]");
            List<TocEntry> parsed = objectMapper.readValue(toc, new TypeReference<List<TocEntry>>() {});
            assertThat(parsed).containsExactly(new TocEntry("heading-新章節", "新章節", 2));
        }

        @Test
        @DisplayName("正常：update 不含 content 時，不應重算或覆寫既有 toc")
        void update_不含content時不覆寫既有toc() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            String existingToc = "[{\"id\":\"heading-既有\",\"text\":\"既有\",\"level\":2}]";
            article.setToc(existingToc);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTitle("只改標題");
            // request.content 為 null，不應觸發 toc 重算

            commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getToc()).isEqualTo(existingToc);
            verify(markdownRenderer, never()).render(any());
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
        @DisplayName("正常：REJECTED → PENDING_REVIEW 成功並清除 rejectReason")
        void submitForReview_rejectedToPendingReview_successAndClearsRejectReason() {
            Article article = buildArticle(ArticleStatus.REJECTED);
            article.setRejectReason("請補充實作細節與審核依據");
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            ArticleResponse response = commandSubService.submitForReview(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.PENDING_REVIEW);
            assertThat(response.getRejectReason()).isNull();
            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getRejectReason()).isNull();
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
    @DisplayName("withdrawArticle")
    class WithdrawArticleTests {

        @Test
        @DisplayName("正常：作者抽回自己的 PENDING_REVIEW 文章 → DRAFT")
        void withdrawArticle_pendingReviewToDraft_success() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            ArticleResponse response = commandSubService.withdrawArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.DRAFT);
            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ArticleStatus.DRAFT);
        }

        @Test
        @DisplayName("異常：ADMIN 抽回他人文章 → ARTICLE_ACCESS_DENIED（職責分離：ADMIN 應走 reject）")
        void withdrawArticle_adminOnOthersArticle_denied() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> commandSubService.withdrawArticle(OTHER_USER_ID, Role.ADMIN, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage());
            verify(articleRepository, never()).save(any(Article.class));
        }

        @Test
        @DisplayName("正常：ADMIN 抽回自己送審的文章 → DRAFT（守衛依作者身分而非角色）")
        void withdrawArticle_adminWithdrawsOwnArticle_success() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            ArticleResponse response = commandSubService.withdrawArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID);

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.DRAFT);
        }

        @Test
        @DisplayName("正常：抽回不發送任何 MQ 事件（與 submitForReview 對稱）")
        void withdrawArticle_publishesNoEvent() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            commandSubService.withdrawArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            verifyNoInteractions(articleEventPublisher);
        }

        @Test
        @DisplayName("異常：DRAFT 狀態抽回 → ARTICLE_STATUS_TRANSITION_INVALID")
        void withdrawArticle_draftStatus_invalidTransition() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> commandSubService.withdrawArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
            verify(articleRepository, never()).save(any(Article.class));
        }

        @Test
        @DisplayName("異常：PUBLISHED 狀態抽回 → ARTICLE_STATUS_TRANSITION_INVALID")
        void withdrawArticle_publishedStatus_invalidTransition() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> commandSubService.withdrawArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
            verify(articleRepository, never()).save(any(Article.class));
        }

        @Test
        @DisplayName("異常：REJECTED 狀態抽回 → ARTICLE_STATUS_TRANSITION_INVALID")
        void withdrawArticle_rejectedStatus_invalidTransition() {
            Article article = buildArticle(ArticleStatus.REJECTED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> commandSubService.withdrawArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
            verify(articleRepository, never()).save(any(Article.class));
        }

        @Test
        @DisplayName("異常：ARCHIVED 狀態抽回 → ARTICLE_STATUS_TRANSITION_INVALID")
        void withdrawArticle_archivedStatus_invalidTransition() {
            Article article = buildArticle(ArticleStatus.ARCHIVED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> commandSubService.withdrawArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
            verify(articleRepository, never()).save(any(Article.class));
        }

        @Test
        @DisplayName("異常：非作者抽回他人文章 → ARTICLE_ACCESS_DENIED")
        void withdrawArticle_otherUserDenied() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> commandSubService.withdrawArticle(OTHER_USER_ID, Role.AUTHOR, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage());
            verify(articleRepository, never()).save(any(Article.class));
        }

        @Test
        @DisplayName("異常：文章不存在 → ARTICLE_NOT_FOUND")
        void withdrawArticle_articleNotFound() {
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID))
                    .thenThrow(new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND));

            assertThatThrownBy(() -> commandSubService.withdrawArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage());
        }
    }


    @Nested
    @DisplayName("archiveArticle")
    class ArchiveArticleTests {

        @Test
        @DisplayName("正常：ADMIN 將 PUBLISHED 文章下架 → ARCHIVED")
        void archiveArticle_publishedToArchived_byAdmin_success() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            ArticleResponse response = commandSubService.archiveArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID);

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.ARCHIVED);
            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ArticleStatus.ARCHIVED);
        }

        @Test
        @DisplayName("正常：下架成功後應發送 ArticleArchivedEvent（供 search 模組移除 ES 索引）")
        void archiveArticle_shouldPublishArchivedEvent() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            commandSubService.archiveArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleEventPublisher).publishArchived(captor.capture());
            assertThat(captor.getValue().getUuid()).isEqualTo(ARTICLE_UUID);
            assertThat(captor.getValue().getStatus()).isEqualTo(ArticleStatus.ARCHIVED);
        }

        @Test
        @DisplayName("異常：AUTHOR 本人下架自己的文章 → ARTICLE_ACCESS_DENIED（下架非作者自助操作）")
        void archiveArticle_byAuthor_denied() {
            assertThatThrownBy(() -> commandSubService.archiveArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage());

            /** 角色守衛先於實體查詢，避免非 ADMIN 藉回應差異探測文章是否存在（與 rejectArticle 一致） */
            verifyNoInteractions(entityFinder);
            verifyNoInteractions(articleRepository);
            verifyNoInteractions(articleEventPublisher);
        }

        @Test
        @DisplayName("異常：operatorRole 為 null（未解析出角色）→ ARTICLE_ACCESS_DENIED")
        void archiveArticle_nullRole_denied() {
            assertThatThrownBy(() -> commandSubService.archiveArticle(AUTHOR_ID, null, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage());
        }

        @Test
        @DisplayName("異常：DRAFT → ARCHIVED（非法轉換）→ ARTICLE_STATUS_TRANSITION_INVALID")
        void archiveArticle_draftStatus_invalidTransition() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> commandSubService.archiveArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("異常：PENDING_REVIEW → ARCHIVED（非法轉換）→ ARTICLE_STATUS_TRANSITION_INVALID")
        void archiveArticle_pendingReviewStatus_invalidTransition() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> commandSubService.archiveArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("異常：REJECTED → ARCHIVED（非法轉換）→ ARTICLE_STATUS_TRANSITION_INVALID")
        void archiveArticle_rejectedStatus_invalidTransition() {
            Article article = buildArticle(ArticleStatus.REJECTED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> commandSubService.archiveArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("異常：ARCHIVED → ARCHIVED（重複下架）→ ARTICLE_STATUS_TRANSITION_INVALID")
        void archiveArticle_alreadyArchived_invalidTransition() {
            Article article = buildArticle(ArticleStatus.ARCHIVED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> commandSubService.archiveArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("異常：非法轉換被守衛擋下時，不得寫入 DB 也不得發送 MQ 事件")
        void archiveArticle_invalidTransition_noSaveNoEvent() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> commandSubService.archiveArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class);

            verify(articleRepository, never()).save(any(Article.class));
            verify(articleEventPublisher, never()).publishArchived(any(Article.class));
        }

        @Test
        @DisplayName("異常：文章不存在 → ARTICLE_NOT_FOUND")
        void archiveArticle_articleNotFound() {
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID))
                    .thenThrow(new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND));

            assertThatThrownBy(() -> commandSubService.archiveArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage());
        }
    }

    @Nested
    @DisplayName("unarchiveArticle")
    class UnarchiveArticleTests {

        @Test
        @DisplayName("正常：ADMIN 將 ARCHIVED 文章復原 → DRAFT")
        void unarchiveArticle_archivedToDraft_byAdmin_success() {
            Article article = buildArticle(ArticleStatus.ARCHIVED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            ArticleResponse response = commandSubService.unarchiveArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID);

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.DRAFT);
            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ArticleStatus.DRAFT);
        }

        @Test
        @DisplayName("正常：復原不發送任何 MQ 事件（DRAFT 不進索引，與 submitForReview / withdraw 對稱）")
        void unarchiveArticle_publishesNoEvent() {
            Article article = buildArticle(ArticleStatus.ARCHIVED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            commandSubService.unarchiveArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID);

            verifyNoInteractions(articleEventPublisher);
        }

        @Test
        @DisplayName("異常：AUTHOR 本人復原自己的文章 → ARTICLE_ACCESS_DENIED")
        void unarchiveArticle_byAuthor_denied() {
            assertThatThrownBy(() -> commandSubService.unarchiveArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage());

            verifyNoInteractions(entityFinder);
            verifyNoInteractions(articleRepository);
        }

        @Test
        @DisplayName("異常：PUBLISHED → DRAFT（非法轉換）→ ARTICLE_STATUS_TRANSITION_INVALID")
        void unarchiveArticle_publishedStatus_invalidTransition() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> commandSubService.unarchiveArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
            verify(articleRepository, never()).save(any(Article.class));
        }

        @Test
        @DisplayName("異常：DRAFT 文章復原（來源非 ARCHIVED）→ ARTICLE_STATUS_TRANSITION_INVALID")
        void unarchiveArticle_draftStatus_invalidTransition() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> commandSubService.unarchiveArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("異常：REJECTED 文章復原（表中 REJECTED → DRAFT 合法，但不屬復原語意）→ ARTICLE_STATUS_TRANSITION_INVALID")
        void unarchiveArticle_rejectedStatus_invalidTransition() {
            Article article = buildArticle(ArticleStatus.REJECTED);
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);

            assertThatThrownBy(() -> commandSubService.unarchiveArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
            verify(articleRepository, never()).save(any(Article.class));
        }

        @Test
        @DisplayName("異常：文章不存在 → ARTICLE_NOT_FOUND")
        void unarchiveArticle_articleNotFound() {
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID))
                    .thenThrow(new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND));

            assertThatThrownBy(() -> commandSubService.unarchiveArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage());
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
                    .thenReturn(new RenderResult("這是正常文字", List.of()));  // 模擬 OWASP 剝除 script 後的結果
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

    /**
     * 檔案綁定回填測試（Task B6）
     *
     * <p>
     * 文章 create/update 完成後，掃描 content 中出現的 {@code /api/v1/files/{uuid}/content}
     * 連結，透過 FileFacade 將完整檔案清單回填綁定至該文章。
     * </p>
     *
     * <p><strong>邊界分析場景表</strong></p>
     * <pre>
     * # | 情境                               | 輸入                                    | 預期行為
     * 1 | 建立文章，內文含 3 張圖            | content 含 3 個不同檔案連結             | bindFilesToArticle 以 3 個 uuid 呼叫一次
     * 2 | update 後內文只剩 1 張              | content 只剩 1 個連結                   | 以該 1 個 uuid 呼叫（完整替換語意交給 facade 解除舊綁定）
     * 3 | 內文無圖                            | content 不含任何檔案連結                | 以空清單呼叫（不是不呼叫）
     * 4 | 內文含格式錯誤 uuid                | not-a-uuid + 1 個正常連結               | 不拋錯，只綁定正常那個
     * 5 | 同一張圖在內文出現兩次              | content 含相同 uuid 兩次                | 去重後只傳一個
     * 6 | facade 拋例外（create）             | bindFilesToArticle 丟 RuntimeException  | createArticle 仍正常回傳
     * 6'| facade 拋例外（update）             | bindFilesToArticle 丟 RuntimeException  | updateArticle 仍正常回傳
     * 7 | updateArticle 未更新 content（僅改標題）| request.content 為 null              | 掃描既有 article.content，仍呼叫 facade
     * </pre>
     */
    @Nested
    @DisplayName("檔案綁定回填 (FileFacade.bindFilesToArticle)")
    class FileBindingTests {

        @Test
        @DisplayName("正常：建立文章，內文含 3 張圖 → 以 3 個 uuid 呼叫 facade 一次")
        void createArticle_contentWithThreeImages_bindsAllThreeFileUuids() {
            UUID fileId1 = UUID.randomUUID();
            UUID fileId2 = UUID.randomUUID();
            UUID fileId3 = UUID.randomUUID();
            String content = "![a](/api/v1/files/" + fileId1 + "/content)\n"
                    + "文字\n"
                    + "![b](/api/v1/files/" + fileId2 + "/content)\n"
                    + "![c](/api/v1/files/" + fileId3 + "/content)";

            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("含三張圖的文章");
            request.setContent(content);

            Article saved = buildArticle(ArticleStatus.DRAFT);
            saved.setContent(content);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            commandSubService.createArticle(AUTHOR_ID, request);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<UUID>> captor = ArgumentCaptor.forClass(List.class);
            verify(fileFacade).bindFilesToArticle(eq(ARTICLE_UUID), captor.capture());
            assertThat(captor.getValue()).containsExactlyInAnyOrder(fileId1, fileId2, fileId3);
        }

        @Test
        @DisplayName("正常：update 後內文只剩 1 張圖 → 以該 1 個 uuid 呼叫（驗證完整替換語意交給 facade 解除舊綁定）")
        void updateArticle_contentReducedToOneImage_bindsOnlyRemainingFileUuid() {
            UUID keptFileId = UUID.randomUUID();
            UUID removedFileId = UUID.randomUUID();
            Article article = buildArticle(ArticleStatus.DRAFT);
            article.setContent("![old1](/api/v1/files/" + keptFileId + "/content)\n"
                    + "![old2](/api/v1/files/" + removedFileId + "/content)");
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            String newContent = "![kept](/api/v1/files/" + keptFileId + "/content)";
            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setContent(newContent);

            commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<UUID>> captor = ArgumentCaptor.forClass(List.class);
            verify(fileFacade).bindFilesToArticle(eq(ARTICLE_UUID), captor.capture());
            assertThat(captor.getValue()).containsExactly(keptFileId);
        }

        @Test
        @DisplayName("邊界：內文無圖 → 以空清單呼叫 facade（而非不呼叫）")
        void createArticle_contentWithoutImages_bindsEmptyList() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("無圖文章");
            request.setContent("純文字內容，沒有任何圖片連結");

            Article saved = buildArticle(ArticleStatus.DRAFT);
            saved.setContent("純文字內容，沒有任何圖片連結");
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            commandSubService.createArticle(AUTHOR_ID, request);

            verify(fileFacade).bindFilesToArticle(ARTICLE_UUID, List.of());
        }

        @Test
        @DisplayName("穩健性：內文含格式錯誤的 uuid → 不拋錯，其餘正常綁定")
        void createArticle_contentWithMalformedUuid_skipsSilentlyAndBindsRest() {
            UUID validFileId = UUID.randomUUID();
            String content = "![bad](/api/v1/files/not-a-uuid/content)\n"
                    + "![good](/api/v1/files/" + validFileId + "/content)";

            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("含壞連結的文章");
            request.setContent(content);

            Article saved = buildArticle(ArticleStatus.DRAFT);
            saved.setContent(content);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            EditorArticleResponse response = commandSubService.createArticle(AUTHOR_ID, request);

            assertThat(response).isNotNull();
            verify(fileFacade).bindFilesToArticle(ARTICLE_UUID, List.of(validFileId));
        }

        @Test
        @DisplayName("穩健性：同一張圖在內文出現兩次 → 去重，只傳一個")
        void createArticle_sameFileUuidAppearsTwice_dedupesBeforeBind() {
            UUID fileId = UUID.randomUUID();
            String content = "![first](/api/v1/files/" + fileId + "/content)\n"
                    + "重複貼了一次\n"
                    + "![second](/api/v1/files/" + fileId + "/content)";

            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("重複圖片的文章");
            request.setContent(content);

            Article saved = buildArticle(ArticleStatus.DRAFT);
            saved.setContent(content);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            commandSubService.createArticle(AUTHOR_ID, request);

            verify(fileFacade).bindFilesToArticle(ARTICLE_UUID, List.of(fileId));
        }

        @Test
        @DisplayName("穩健性：facade 拋例外 → 文章建立仍成功（不往外拋）")
        void createArticle_fileFacadeThrows_articleSaveStillSucceeds() {
            UUID fileId = UUID.randomUUID();
            String content = "![img](/api/v1/files/" + fileId + "/content)";

            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("facade 失敗的文章");
            request.setContent(content);

            Article saved = buildArticle(ArticleStatus.DRAFT);
            saved.setContent(content);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);
            doThrow(new RuntimeException("file service 掛了"))
                    .when(fileFacade).bindFilesToArticle(any(), any());

            EditorArticleResponse response = commandSubService.createArticle(AUTHOR_ID, request);

            assertThat(response).isNotNull();
            assertThat(response.getUuid()).isEqualTo(ARTICLE_UUID);
        }

        @Test
        @DisplayName("穩健性：updateArticle 時 facade 拋例外 → 更新仍成功回傳")
        void updateArticle_fileFacadeThrows_updateStillSucceeds() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            article.setContent("![img](/api/v1/files/" + UUID.randomUUID() + "/content)");
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));
            doThrow(new RuntimeException("file service 掛了"))
                    .when(fileFacade).bindFilesToArticle(any(), any());

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTitle("更新標題但 facade 會失敗");

            EditorArticleResponse response = commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("邊界：updateArticle 未更新 content（title-only）→ 仍以既有 content 掃描並呼叫 facade")
        void updateArticle_titleOnlyUpdate_scansExistingContentAndBinds() {
            UUID existingFileId = UUID.randomUUID();
            Article article = buildArticle(ArticleStatus.DRAFT);
            article.setContent("![existing](/api/v1/files/" + existingFileId + "/content)");
            when(entityFinder.findByUuidOrThrow(ARTICLE_UUID)).thenReturn(article);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTitle("只改標題");
            /** content 為 null，代表不更新內文 */

            commandSubService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            verify(fileFacade).bindFilesToArticle(ARTICLE_UUID, List.of(existingFileId));
        }
    }

    /**
     * 狀態轉換矩陣：{@code VALID_TRANSITIONS} 的完整 5×5 覆蓋（含 ADMIN 角色分支）。
     *
     * <p><strong>為什麼要有這組測試</strong>（SEC-02 收斂的配套）：守衛收斂成單一真相之後，
     * 這張表就是整個文章狀態機的唯一規格。將來新增狀態、或有人「順手」增減一條邊，
     * 漏改的路徑會在這裡立刻紅，而不是等到某個端點被線上使用者踩到。</p>
     *
     * <p><strong>覆蓋方式</strong>：測資由 {@code ArticleStatus.values()} 的笛卡兒積產生，
     * 而非手寫 25 行——新增第 6 個狀態時測資自動長到 36 組，其中 11 組查不到期望值會直接紅
     * （見 {@code EXPECTED} 的 assertion 訊息），逼人補齊，不會靜默漏測。</p>
     *
     * <p><strong>為何直接測 validateStatusTransition</strong>：表裡的 PUBLISHED → ARCHIVED、
     * ARCHIVED → DRAFT 目前沒有任何公開端點可觸發（PUT 對這兩個狀態會先被
     * ARTICLE_EDIT_NOT_ALLOWED 擋下），透過 public 方法無法覆蓋全矩陣。</p>
     */
    @Nested
    @DisplayName("狀態轉換矩陣（VALID_TRANSITIONS 單一真相）")
    class StatusTransitionMatrix {

        /**
         * 每組轉換的期望結果。
         *
         * <p>ALLOWED：任何角色皆可；ADMIN_ONLY：ADMIN 通過、非 ADMIN 拋 ARTICLE_ACCESS_DENIED；
         * INVALID：任何角色皆拋 ARTICLE_STATUS_TRANSITION_INVALID。</p>
         */
        private enum Expectation {
            ALLOWED, ADMIN_ONLY, INVALID
        }

        private static final Map<ArticleStatus, Map<ArticleStatus, Expectation>> EXPECTED = Map.of(
                ArticleStatus.DRAFT, Map.of(
                        ArticleStatus.DRAFT, Expectation.INVALID,
                        ArticleStatus.PENDING_REVIEW, Expectation.ALLOWED,
                        ArticleStatus.PUBLISHED, Expectation.ALLOWED,
                        ArticleStatus.ARCHIVED, Expectation.INVALID,
                        ArticleStatus.REJECTED, Expectation.INVALID),
                ArticleStatus.PENDING_REVIEW, Map.of(
                        ArticleStatus.DRAFT, Expectation.ALLOWED,
                        ArticleStatus.PENDING_REVIEW, Expectation.INVALID,
                        ArticleStatus.PUBLISHED, Expectation.ADMIN_ONLY,
                        ArticleStatus.ARCHIVED, Expectation.INVALID,
                        ArticleStatus.REJECTED, Expectation.ADMIN_ONLY),
                ArticleStatus.PUBLISHED, Map.of(
                        ArticleStatus.DRAFT, Expectation.INVALID,
                        ArticleStatus.PENDING_REVIEW, Expectation.INVALID,
                        ArticleStatus.PUBLISHED, Expectation.INVALID,
                        ArticleStatus.ARCHIVED, Expectation.ALLOWED,
                        ArticleStatus.REJECTED, Expectation.INVALID),
                ArticleStatus.ARCHIVED, Map.of(
                        ArticleStatus.DRAFT, Expectation.ALLOWED,
                        ArticleStatus.PENDING_REVIEW, Expectation.INVALID,
                        ArticleStatus.PUBLISHED, Expectation.INVALID,
                        ArticleStatus.ARCHIVED, Expectation.INVALID,
                        ArticleStatus.REJECTED, Expectation.INVALID),
                ArticleStatus.REJECTED, Map.of(
                        ArticleStatus.DRAFT, Expectation.ALLOWED,
                        /* 被駁回後改一改重新送審，submitForReview 的既有行為（SEC-02 補進表） */
                        ArticleStatus.PENDING_REVIEW, Expectation.ALLOWED,
                        ArticleStatus.PUBLISHED, Expectation.INVALID,
                        ArticleStatus.ARCHIVED, Expectation.INVALID,
                        ArticleStatus.REJECTED, Expectation.INVALID));

        /**
         * 產生 5×5 全部組合的測資（新增狀態時自動擴張）。
         *
         * @return 每筆為 [from, to] 的 Arguments 串流
         */
        static Stream<Arguments> allTransitions() {
            return Arrays.stream(ArticleStatus.values())
                    .flatMap(from -> Arrays.stream(ArticleStatus.values())
                            .map(to -> Arguments.of(from, to)));
        }

        @ParameterizedTest(name = "[{index}] {0} → {1}")
        @MethodSource("allTransitions")
        @DisplayName("以 AUTHOR 身分執行：ALLOWED 通過、ADMIN_ONLY 拒絕、INVALID 拒絕")
        void validateStatusTransition_asAuthor_matchesMatrix(ArticleStatus from, ArticleStatus to) {
            Expectation expectation = expectationOf(from, to);

            switch (expectation) {
                case ALLOWED -> assertThatCode(
                        () -> commandSubService.validateStatusTransition(from, to, Role.AUTHOR))
                        .as("%s → %s 應允許", from, to)
                        .doesNotThrowAnyException();
                case ADMIN_ONLY -> assertThatThrownBy(
                        () -> commandSubService.validateStatusTransition(from, to, Role.AUTHOR))
                        .as("%s → %s 僅 ADMIN 可執行", from, to)
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage());
                case INVALID -> assertThatThrownBy(
                        () -> commandSubService.validateStatusTransition(from, to, Role.AUTHOR))
                        .as("%s → %s 為非法轉換", from, to)
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining(
                                ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
            }
        }

        @ParameterizedTest(name = "[{index}] {0} → {1}")
        @MethodSource("allTransitions")
        @DisplayName("以 ADMIN 身分執行：ALLOWED / ADMIN_ONLY 皆通過、INVALID 仍拒絕")
        void validateStatusTransition_asAdmin_matchesMatrix(ArticleStatus from, ArticleStatus to) {
            Expectation expectation = expectationOf(from, to);

            if (expectation == Expectation.INVALID) {
                assertThatThrownBy(
                        () -> commandSubService.validateStatusTransition(from, to, Role.ADMIN))
                        .as("%s → %s 為非法轉換，ADMIN 也不得執行", from, to)
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining(
                                ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
            } else {
                assertThatCode(
                        () -> commandSubService.validateStatusTransition(from, to, Role.ADMIN))
                        .as("%s → %s 對 ADMIN 應允許", from, to)
                        .doesNotThrowAnyException();
            }
        }

        /**
         * 取出期望值；查無代表新增了狀態卻沒補矩陣，直接讓該組紅掉。
         *
         * @param from 來源狀態
         * @param to   目標狀態
         * @return 該組轉換的期望結果
         */
        private Expectation expectationOf(ArticleStatus from, ArticleStatus to) {
            Expectation expectation = EXPECTED.getOrDefault(from, Map.of()).get(to);
            assertThat(expectation)
                    .as("轉換矩陣未定義 %s → %s：新增狀態時必須同步補上 VALID_TRANSITIONS 與本矩陣", from, to)
                    .isNotNull();
            return expectation;
        }
    }

}
