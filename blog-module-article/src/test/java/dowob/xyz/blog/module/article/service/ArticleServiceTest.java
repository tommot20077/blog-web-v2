package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionTemplate;

import dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleTagEvent;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.module.article.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.article.event.ArticleUpdatedEvent;
import dowob.xyz.blog.module.article.event.ArticleViewedEvent;
import dowob.xyz.blog.module.article.mapper.CategoryMapper;
import dowob.xyz.blog.module.article.model.Category;
import dowob.xyz.blog.module.article.model.CategoryWithArticleId;
import dowob.xyz.blog.module.article.repository.CategoryRepository;
import dowob.xyz.blog.module.article.service.ViewCountService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

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

    @Mock
    private ViewCountService viewCountService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private TagFacade tagFacade;

    /** Mock：Spring 宣告式事務模板（TransactionTemplate） */
    @Mock
    private TransactionTemplate transactionTemplate;

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

    /** 瀏覽計數 Redis ValueOps（Mock，用於 setIfAbsent 防刷測試） */
    @Mock
    private ValueOperations<String, String> valueOps;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        when(userFacade.getUserUuidById(AUTHOR_ID)).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(AUTHOR_ID)).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(AUTHOR_ID)).thenReturn(Optional.of("testuser"));
        when(viewCountService.getViewCount(any())).thenReturn(0L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(categoryMapper.findCategoriesByArticleIds(any())).thenReturn(List.of());

        /** TransactionTemplate mock：直接執行回調 */
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        doAnswer(inv -> {
            Consumer<org.springframework.transaction.TransactionStatus> action = inv.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
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
        @DisplayName("安全：建立文章時傳入 PENDING_REVIEW 狀態應強制為 DRAFT")
        void createArticle_withPendingReviewStatus_shouldForceDraft() {
            // 傳入 PENDING_REVIEW 模擬試圖繞過草稿階段
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("待審文章");
            request.setContent("內容");
            request.setStatus(ArticleStatus.PENDING_REVIEW);

            // save 直接回傳傳入的 Article，以便驗證實際存入的狀態
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            articleService.createArticle(AUTHOR_ID, request);

            // 無論傳入何種狀態，儲存至 DB 前都必須是 DRAFT
            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ArticleStatus.DRAFT);
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

        @Test
        @DisplayName("正常：建立文章時應自動產生 contentHtml（含 <p> 標籤）")
        void createArticle_shouldGenerateContentHtml() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("HTML 渲染測試");
            request.setContent("這是一段文字");
            request.setSummary("摘要");

            Article saved = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            ArticleResponse response = articleService.createArticle(AUTHOR_ID, request);

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

            articleService.createArticle(AUTHOR_ID, request);

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

            articleService.createArticle(AUTHOR_ID, request);

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

            articleService.createArticle(AUTHOR_ID, request);

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

            articleService.createArticle(AUTHOR_ID, request);

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

            articleService.createArticle(AUTHOR_ID, request);

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

            articleService.createArticle(AUTHOR_ID, request);

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

            articleService.createArticle(AUTHOR_ID, request);

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

            articleService.createArticle(AUTHOR_ID, request);

            // 捕捉實際傳入 save() 的 Article，驗證 status 已被強制覆寫為 DRAFT
            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus())
                    .as("即使 request 傳入 PUBLISHED，實際儲存的 Article 狀態必須為 DRAFT")
                    .isEqualTo(ArticleStatus.DRAFT);
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
        @DisplayName("異常：updateArticle 時傳入不存在的 categoryUuid → CATEGORY_NOT_FOUND")
        void updateArticle_withNonExistentCategoryUuid_throwsCategoryNotFound() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UUID nonExistentUuid = UUID.randomUUID();
            when(categoryRepository.findByUuid(nonExistentUuid)).thenReturn(Optional.empty());

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setCategoryIds(List.of(nonExistentUuid));

            assertThatThrownBy(
                    () -> articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.CATEGORY_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("正常：UpdateArticleRequest 全欄位為 null 時，現有資料不變")
        void updateArticle_allNullRequest_preservesExistingData() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest emptyRequest = new UpdateArticleRequest();

            articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, emptyRequest);

            org.mockito.ArgumentCaptor<Article> articleCaptor = org.mockito.ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(articleCaptor.capture());
            assertThat(articleCaptor.getValue().getTitle()).isEqualTo("測試標題");
            assertThat(articleCaptor.getValue().getContent()).isEqualTo("測試內容");
        }

        @Test
        @DisplayName("異常：insertArticleCategory 拋出 DataIntegrityViolationException 時，syncCategories 應往外傳播")
        void updateArticle_propagatesException_whenInsertCategoryFails() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
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
                    () -> articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("正常：content 更新後，contentHtml 應重新渲染（含 <p> 標籤）")
        void updateArticle_shouldReRenderHtml_whenContentChanges() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setContent("更新後的 Markdown 內容");

            articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            org.mockito.ArgumentCaptor<Article> captor = org.mockito.ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getContentHtml()).isNotNull();
            assertThat(captor.getValue().getContentHtml()).contains("<p>");
        }

        @Test
        @DisplayName("異常：發生樂觀鎖例外時，應拋出 ARTICLE_CONCURRENT_UPDATE 業務例外")
        void updateArticle_concurrentUpdate_throwsException() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class)))
                    .thenThrow(new OptimisticLockingFailureException("Optimistic lock"));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTitle("並發更新");

            assertThatThrownBy(() -> articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_CONCURRENT_UPDATE.getMessage());
        }

        @Test
        @DisplayName("正常：updateArticle 時 tagNames 非 null 非空，應呼叫 syncArticleTags")
        void updateArticle_withTagNames_callsSyncArticleTags() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UUID tagId1 = UUID.randomUUID();
            when(tagFacade.findOrCreateTags(List.of("Spring")))
                    .thenReturn(List.of(new TagInfo(tagId1, "Spring", "spring")));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTagNames(List.of("Spring"));

            articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            verify(tagFacade).findOrCreateTags(List.of("Spring"));
            verify(tagFacade).syncArticleTags(ARTICLE_UUID, List.of(tagId1));
        }

        @Test
        @DisplayName("正常：updateArticle 時 tagNames 為 null，不呼叫 tagFacade 任何方法")
        void updateArticle_withNullTagNames_doesNotCallTagFacade() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            /** tagNames 為 null，不更新 */

            articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            verify(tagFacade, never()).findOrCreateTags(any());
            verify(tagFacade, never()).syncArticleTags(any(), any());
            verify(tagFacade, never()).deleteArticleTags(any());
        }

        @Test
        @DisplayName("邊界：updateArticle 時 tagNames 為空列表，應呼叫 deleteArticleTags（清除所有標籤）")
        void updateArticle_withEmptyTagNames_callsDeleteArticleTags() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTagNames(List.of());

            articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            verify(tagFacade).deleteArticleTags(ARTICLE_UUID);
            verify(tagFacade, never()).syncArticleTags(any(), any());
        }

        @Test
        @DisplayName("正常：updateArticle 時指定 coverImageUrl，Article 應包含該 URL")
        void updateArticle_withCoverImageUrl_setsOnArticle() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setCoverImageUrl("https://example.com/cover.jpg");

            articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getCoverImageUrl()).isEqualTo("https://example.com/cover.jpg");
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
        @DisplayName("正常：發布事件包含 slug、摘要、純文字內容、作者資訊與標籤")
        void publishArticle_eventContainsEnrichedData() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userFacade.getUserUsernameById(AUTHOR_ID)).thenReturn(Optional.of("testuser"));
            when(userFacade.getUserNicknameById(AUTHOR_ID)).thenReturn(Optional.of("TestAuthor"));
            when(articleMapper.findTagsByArticleUuid(ARTICLE_UUID)).thenReturn(
                    List.of(new TagInfo(UUID.randomUUID(), "Spring", "spring")));

            articleService.publishArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            ArgumentCaptor<ArticlePublishedEvent> captor = ArgumentCaptor.forClass(ArticlePublishedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_PUBLISHED),
                    captor.capture());

            ArticlePublishedEvent event = captor.getValue();
            assertThat(event.slug()).isEqualTo(article.getSlug());
            assertThat(event.summary()).isEqualTo("測試摘要");
            assertThat(event.contentText()).isNotBlank();
            assertThat(event.authorUsername()).isEqualTo("testuser");
            assertThat(event.authorNickname()).isEqualTo("TestAuthor");
            assertThat(event.tags()).hasSize(1);
            assertThat(event.tags().get(0).name()).isEqualTo("Spring");
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
        @DisplayName("正常：PUBLISHED 文章，匿名用戶可存取，新 IP 透過 MQ 發送瀏覽事件")
        void getArticle_published_anonymous() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

            ArticleResponse response = articleService.getArticleByUuid(ARTICLE_UUID, null, null, "127.0.0.1");

            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
            verify(rabbitTemplate).convertAndSend(eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_VIEWED), any(ArticleViewedEvent.class));
        }

        @Test
        @DisplayName("正常：DRAFT 文章，作者本人可存取")
        void getArticle_draft_authorCanAccess() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            ArticleResponse response = articleService.getArticleByUuid(ARTICLE_UUID, AUTHOR_ID, Role.AUTHOR,
                    "127.0.0.1");

            assertThat(response).isNotNull();
            verify(rabbitTemplate, never()).convertAndSend(eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_VIEWED), any(ArticleViewedEvent.class));
        }

        @Test
        @DisplayName("正常：DRAFT 文章，ADMIN 可存取")
        void getArticle_draft_adminCanAccess() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            ArticleResponse response = articleService.getArticleByUuid(ARTICLE_UUID, OTHER_USER_ID, Role.ADMIN,
                    "127.0.0.1");

            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("異常：DRAFT 文章，其他用戶存取 → ARTICLE_NOT_FOUND")
        void getArticle_draft_otherUserDenied() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            assertThatThrownBy(
                    () -> articleService.getArticleByUuid(ARTICLE_UUID, OTHER_USER_ID, Role.AUTHOR, "127.0.0.1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("正常：PENDING_REVIEW 文章，作者本人可存取，不增加瀏覽數")
        void getArticle_pendingReview_authorCanAccess_noViewCountIncrement() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            ArticleResponse response = articleService.getArticleByUuid(ARTICLE_UUID, AUTHOR_ID, Role.AUTHOR,
                    "127.0.0.1");

            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(ArticleStatus.PENDING_REVIEW);
            verify(rabbitTemplate, never()).convertAndSend(eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_VIEWED), any(ArticleViewedEvent.class));
        }

        @Test
        @DisplayName("異常：PENDING_REVIEW 文章，其他用戶存取 → ARTICLE_NOT_FOUND")
        void getArticle_pendingReview_otherUserDenied() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            assertThatThrownBy(
                    () -> articleService.getArticleByUuid(ARTICLE_UUID, OTHER_USER_ID, Role.AUTHOR, "127.0.0.1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("正常：新 IP 存取 PUBLISHED 文章（setIfAbsent 回傳 true），發送 MQ 瀏覽事件")
        void getArticleByUuid_shouldSendMqEvent_whenNewIp() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

            articleService.getArticleByUuid(ARTICLE_UUID, null, null, "127.0.0.1");

            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_VIEWED),
                    any(ArticleViewedEvent.class));
            verify(articleMapper, never()).incrementViewCountBatch(any(UUID.class), anyLong());
        }

        @Test
        @DisplayName("正常：相同 IP 5 分鐘內重複存取（setIfAbsent 回傳 false），不發送 MQ 瀏覽事件")
        void getArticleByUuid_shouldNotSendMqEvent_whenSameIpWithinWindow() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

            articleService.getArticleByUuid(ARTICLE_UUID, null, null, "127.0.0.1");

            verify(rabbitTemplate, never()).convertAndSend(
                    eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_VIEWED),
                    any(ArticleViewedEvent.class));
        }

        @Test
        @DisplayName("正常：getArticleByUuid 應使用批次查詢 findCategoriesByArticleIds 取得分類")
        void getArticleByUuid_shouldUseBatchCategoryQuery() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

            UUID catUuid = UUID.randomUUID();
            CategoryWithArticleId catWithId = new CategoryWithArticleId();
            catWithId.setId(10L);
            catWithId.setUuid(catUuid);
            catWithId.setName("技術");
            catWithId.setSlug("tech");
            catWithId.setArticleId(1L);
            when(categoryMapper.findCategoriesByArticleIds(List.of(1L))).thenReturn(List.of(catWithId));

            ArticleResponse response = articleService.getArticleByUuid(ARTICLE_UUID, null, null, "127.0.0.1");

            assertThat(response.getCategories()).hasSize(1);
            assertThat(response.getCategories().get(0).getUuid()).isEqualTo(catUuid);
            assertThat(response.getCategories().get(0).getName()).isEqualTo("技術");
        }

        @Test
        @DisplayName("XSS 安全：convertToHtml 應對 script 標籤進行 escape，不輸出可執行腳本")
        void createArticle_convertToHtml_shouldEscapeScriptTags() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("XSS 測試");
            request.setContent("<script>alert('xss')</script>這是正常文字");
            request.setSummary("摘要");

            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            articleService.createArticle(AUTHOR_ID, request);

            org.mockito.ArgumentCaptor<Article> captor = org.mockito.ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            String html = captor.getValue().getContentHtml();
            assertThat(html).isNotNull();
            assertThat(html).doesNotContain("<script>");
            assertThat(html).doesNotContain("</script>");
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
            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getTotal()).isEqualTo(1L);
            assertThat(result.getCurrent()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常：第二頁查詢，偏移量正確")
        void getPublishedArticles_secondPage() {
            when(articleMapper.findPublishedPage(10L, 10)).thenReturn(List.of());
            when(articleMapper.countPublished()).thenReturn(5L);

            PageResult<ArticleSummaryResponse> result = articleService.getPublishedArticles(2, 10);

            assertThat(result.getRecords()).isEmpty();
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

        @Test
        @DisplayName("正常：rejectArticle 應將 rejectReason 設定到文章實體")
        void rejectArticle_shouldPersistRejectReason() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            articleService.rejectArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID, "內容不符合規範");

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getRejectReason()).isEqualTo("內容不符合規範");
        }

        @Test
        @DisplayName("正常：rejectArticle 回應中包含 rejectReason")
        void rejectArticle_responseShouldContainRejectReason() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            ArticleResponse response = articleService.rejectArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID, "內容違規");

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
            assertThat(result.getRecords()).hasSize(1);
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

        @Test
        @DisplayName("正常：刪除文章時應發送 ArticleDeletedEvent 至 MQ")
        void deleteArticle_shouldPublishDeletedEvent() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            articleService.deleteArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            ArgumentCaptor<ArticleDeletedEvent> captor = ArgumentCaptor.forClass(ArticleDeletedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_DELETED),
                    captor.capture());

            ArticleDeletedEvent event = captor.getValue();
            assertThat(event.articleUuid()).isEqualTo(ARTICLE_UUID);
            assertThat(event.deletedAt()).isNotNull();
        }
    }

    /**
     * Response DTO 欄位完整性測試
     */
    @Nested
    @DisplayName("Response DTO 欄位完整性")
    class ResponseDtoFieldsTests {

        @Test
        @DisplayName("正常：createArticle 回應包含 slug 欄位")
        void createArticle_responseShouldIncludeSlug() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("Slug 測試文章");
            request.setContent("內容");

            Article saved = buildArticle(ArticleStatus.DRAFT);
            saved.setSlug("slug-test-abcd1234");
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            ArticleResponse response = articleService.createArticle(AUTHOR_ID, request);

            assertThat(response.getSlug()).isEqualTo("slug-test-abcd1234");
        }

        @Test
        @DisplayName("正常：createArticle 回應包含 likeCount 欄位")
        void createArticle_responseShouldIncludeLikeCount() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("LikeCount 測試");
            request.setContent("內容");

            Article saved = buildArticle(ArticleStatus.DRAFT);
            saved.setLikeCount(42L);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            ArticleResponse response = articleService.createArticle(AUTHOR_ID, request);

            assertThat(response.getLikeCount()).isEqualTo(42L);
        }

        @Test
        @DisplayName("正常：createArticle 回應包含 tags 欄位（有標籤時）")
        void createArticle_responseShouldIncludeTags_whenTagsExist() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("Tags 測試");
            request.setContent("內容");

            Article saved = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            UUID tagId = UUID.randomUUID();
            when(articleMapper.findTagsByArticleUuid(ARTICLE_UUID))
                    .thenReturn(List.of(new TagInfo(tagId, "Spring", "spring")));

            ArticleResponse response = articleService.createArticle(AUTHOR_ID, request);

            assertThat(response.getTags()).hasSize(1);
            assertThat(response.getTags().get(0).getName()).isEqualTo("Spring");
            assertThat(response.getTags().get(0).getSlug()).isEqualTo("spring");
        }

        @Test
        @DisplayName("正常：createArticle 回應 tags 為空列表（無標籤時）")
        void createArticle_responseShouldHaveEmptyTags_whenNoTags() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("無標籤測試");
            request.setContent("內容");

            Article saved = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);
            when(articleMapper.findTagsByArticleUuid(ARTICLE_UUID)).thenReturn(List.of());

            ArticleResponse response = articleService.createArticle(AUTHOR_ID, request);

            assertThat(response.getTags()).isEmpty();
        }

        @Test
        @DisplayName("正常：getPublishedArticles 使用批次 tag 查詢（findTagsByArticleUuids），不產生 N+1")
        void getPublishedArticles_shouldUseBatchTagQuery() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleMapper.findPublishedPage(0L, 10)).thenReturn(List.of(article));
            when(articleMapper.countPublished()).thenReturn(1L);
            when(articleMapper.findTagsByArticleUuids(List.of(ARTICLE_UUID))).thenReturn(List.of());

            articleService.getPublishedArticles(1, 10);

            verify(articleMapper).findTagsByArticleUuids(List.of(ARTICLE_UUID));
            verify(articleMapper, never()).findTagsByArticleUuid(any());
        }

        @Test
        @DisplayName("正常：toSummaryResponse 包含 updatedAt 欄位")
        void getPublishedArticles_summaryShouldIncludeUpdatedAt() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            LocalDateTime updatedTime = LocalDateTime.of(2025, 1, 15, 10, 0);
            article.setUpdatedAt(updatedTime);
            when(articleMapper.findPublishedPage(0L, 10)).thenReturn(List.of(article));
            when(articleMapper.countPublished()).thenReturn(1L);
            when(articleMapper.findTagsByArticleUuids(any())).thenReturn(List.of());

            PageResult<ArticleSummaryResponse> result = articleService.getPublishedArticles(1, 10);

            assertThat(result.getRecords().get(0).getUpdatedAt()).isEqualTo(updatedTime);
        }
    }

    /**
     * 更新已發布文章時的搜尋同步測試
     */
    @Nested
    @DisplayName("updateArticle - Search Sync")
    class UpdateArticleSearchSyncTests {

        @Test
        @DisplayName("正常：更新已發布文章的標題時，應發送 ArticleUpdatedEvent")
        void updatePublishedArticle_shouldPublishUpdatedEvent() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            article.setPublishedAt(LocalDateTime.now().minusDays(1));
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));
            when(articleMapper.findTagsByArticleUuid(ARTICLE_UUID)).thenReturn(
                    List.of(new TagInfo(UUID.randomUUID(), "Spring", "spring")));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTitle("更新後標題");

            articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            ArgumentCaptor<ArticleUpdatedEvent> captor = ArgumentCaptor.forClass(ArticleUpdatedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_UPDATED),
                    captor.capture());

            ArticleUpdatedEvent event = captor.getValue();
            assertThat(event.articleUuid()).isEqualTo(ARTICLE_UUID);
            assertThat(event.title()).isEqualTo("更新後標題");
            assertThat(event.tags()).hasSize(1);
        }

        @Test
        @DisplayName("正常：更新 DRAFT 文章時，不應發送 ArticleUpdatedEvent")
        void updateDraftArticle_shouldNotPublishUpdatedEvent() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTitle("更新草稿標題");

            articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            verify(rabbitTemplate, never()).convertAndSend(
                    eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_UPDATED),
                    any(ArticleUpdatedEvent.class));
        }
    }

    /**
     * getMyArticles 狀態篩選測試
     */
    @Nested
    @DisplayName("getMyArticles - 狀態篩選")
    class GetMyArticlesFilterTests {

        @Test
        @DisplayName("正常：status 為 null 時查詢全部文章")
        void getMyArticles_statusNull_returnsAll() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleMapper.findByAuthorIdPaged(AUTHOR_ID, 0L, 10)).thenReturn(List.of(article));
            when(articleMapper.countByAuthorId(AUTHOR_ID)).thenReturn(1L);
            when(articleMapper.findTagsByArticleUuids(any())).thenReturn(List.of());

            PageResult<ArticleSummaryResponse> result = articleService.getMyArticles(AUTHOR_ID, 1, 10, null);

            assertThat(result.getTotal()).isEqualTo(1L);
            verify(articleMapper).findByAuthorIdPaged(AUTHOR_ID, 0L, 10);
            verify(articleMapper, never()).findByAuthorIdAndStatus(any(), any(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("正常：status 為 DRAFT 時只查詢草稿文章")
        void getMyArticles_statusDraft_returnsDraftOnly() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleMapper.findByAuthorIdAndStatus(AUTHOR_ID, ArticleStatus.DRAFT, 0L, 10))
                    .thenReturn(List.of(article));
            when(articleMapper.countByAuthorIdAndStatus(AUTHOR_ID, ArticleStatus.DRAFT)).thenReturn(1L);
            when(articleMapper.findTagsByArticleUuids(any())).thenReturn(List.of());

            PageResult<ArticleSummaryResponse> result = articleService.getMyArticles(AUTHOR_ID, 1, 10, ArticleStatus.DRAFT);

            assertThat(result.getTotal()).isEqualTo(1L);
            verify(articleMapper).findByAuthorIdAndStatus(AUTHOR_ID, ArticleStatus.DRAFT, 0L, 10);
            verify(articleMapper).countByAuthorIdAndStatus(AUTHOR_ID, ArticleStatus.DRAFT);
            verify(articleMapper, never()).findByAuthorIdPaged(any(), anyLong(), anyInt());
        }
    }

    /**
     * submitForReview 測試
     */
    @Nested
    @DisplayName("submitForReview")
    class SubmitForReviewTests {

        @Test
        @DisplayName("正常：DRAFT → PENDING_REVIEW 成功")
        void submitForReview_draftToPendingReview_success() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            ArticleResponse response = articleService.submitForReview(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.PENDING_REVIEW);
        }

        @Test
        @DisplayName("異常：PUBLISHED → PENDING_REVIEW（非法轉換）→ ARTICLE_STATUS_TRANSITION_INVALID")
        void submitForReview_publishedToPendingReview_invalidTransition() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            assertThatThrownBy(() -> articleService.submitForReview(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("異常：非作者提交他人文章 → ARTICLE_ACCESS_DENIED")
        void submitForReview_otherUserDenied() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            assertThatThrownBy(() -> articleService.submitForReview(OTHER_USER_ID, Role.AUTHOR, ARTICLE_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage());
        }
    }

    /**
     * getPendingArticleCount 測試
     */
    @Nested
    @DisplayName("getPendingArticleCount")
    class GetPendingArticleCountTests {

        @Test
        @DisplayName("正常：回傳待審文章總筆數")
        void getPendingArticleCount_returnsCorrectCount() {
            when(articleMapper.countPendingReview()).thenReturn(5L);

            long count = articleService.getPendingArticleCount();

            assertThat(count).isEqualTo(5L);
            verify(articleMapper).countPendingReview();
        }
    }

    /**
     * 狀態機邊界：合法轉換（未覆蓋路徑）
     */
    @Nested
    @DisplayName("狀態機邊界 - 合法轉換")
    class StateMachineLegalTransitionTests {

        @Test
        @DisplayName("正常：PUBLISHED → ARCHIVED 合法轉換，updateArticle 成功")
        void updateArticle_publishedToArchived_validTransition() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            article.setPublishedAt(LocalDateTime.now().minusDays(1));
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.ARCHIVED);

            ArticleResponse response = articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.ARCHIVED);
        }

        @Test
        @DisplayName("正常：ARCHIVED → DRAFT 合法轉換，updateArticle 成功")
        void updateArticle_archivedToDraft_validTransition() {
            Article article = buildArticle(ArticleStatus.ARCHIVED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.DRAFT);

            ArticleResponse response = articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.DRAFT);
        }

        @Test
        @DisplayName("正常：updateArticle 更新 status → PUBLISHED 且 publishedAt 為 null，應自動設置 publishedAt")
        void updateArticle_statusToPublished_whenPublishedAtIsNull_setsPublishedAt() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            article.setPublishedAt(null);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.PUBLISHED);

            articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getPublishedAt()).isNotNull();
        }

        @Test
        @DisplayName("正常：updateArticle 更新 status → PUBLISHED 且 publishedAt 已存在，不重設 publishedAt")
        void updateArticle_statusToPublished_whenPublishedAtAlreadySet_doesNotReset() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            LocalDateTime originalPublishedAt = LocalDateTime.of(2024, 1, 1, 12, 0);
            article.setPublishedAt(originalPublishedAt);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.PUBLISHED);

            articleService.updateArticle(AUTHOR_ID, Role.ADMIN, ARTICLE_UUID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getPublishedAt()).isEqualTo(originalPublishedAt);
        }

        @Test
        @DisplayName("正常：publishArticle 時文章已有 publishedAt，不重設（保留首次發布時間）")
        void publishArticle_whenPublishedAtAlreadySet_doesNotReset() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            LocalDateTime originalPublishedAt = LocalDateTime.of(2023, 6, 15, 9, 30);
            article.setPublishedAt(originalPublishedAt);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));
            when(articleMapper.findTagsByArticleUuid(ARTICLE_UUID)).thenReturn(List.of());

            articleService.publishArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

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

            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenReturn(savedArticle);
            when(articleMapper.findTagsByArticleUuid(ARTICLE_UUID)).thenReturn(List.of());

            ArticleResponse response = articleService.publishArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            assertThat(response.getUpdatedAt()).isEqualTo(savedUpdatedAt);
        }
    }

    /**
     * 狀態機邊界：非法轉換（未覆蓋路徑）
     */
    @Nested
    @DisplayName("狀態機邊界 - 非法轉換")
    class StateMachineInvalidTransitionTests {

        @Test
        @DisplayName("異常：DRAFT → ARCHIVED 非法轉換 → ARTICLE_STATUS_TRANSITION_INVALID")
        void updateArticle_draftToArchived_invalidTransition() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.ARCHIVED);

            assertThatThrownBy(() -> articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("異常：PUBLISHED → DRAFT 非法轉換 → ARTICLE_STATUS_TRANSITION_INVALID")
        void updateArticle_publishedToDraft_invalidTransition() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.DRAFT);

            assertThatThrownBy(() -> articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("異常：REJECTED → PENDING_REVIEW 非法轉換 → ARTICLE_STATUS_TRANSITION_INVALID")
        void updateArticle_rejectedToPendingReview_invalidTransition() {
            Article article = buildArticle(ArticleStatus.REJECTED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.PENDING_REVIEW);

            assertThatThrownBy(() -> articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("異常：ARCHIVED → PUBLISHED 非法轉換 → ARTICLE_STATUS_TRANSITION_INVALID")
        void updateArticle_archivedToPublished_invalidTransition() {
            Article article = buildArticle(ArticleStatus.ARCHIVED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.PUBLISHED);

            assertThatThrownBy(() -> articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("異常：DRAFT → REJECTED 非法轉換 → ARTICLE_STATUS_TRANSITION_INVALID")
        void updateArticle_draftToRejected_invalidTransition() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.REJECTED);

            assertThatThrownBy(() -> articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage());
        }

        @Test
        @DisplayName("異常：PENDING_REVIEW → DRAFT (非 ADMIN) → 合法轉換應成功（非 ADMIN 仍可執行）")
        void updateArticle_pendingReviewToDraft_byAuthor_shouldSucceed() {
            Article article = buildArticle(ArticleStatus.PENDING_REVIEW);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setStatus(ArticleStatus.DRAFT);

            ArticleResponse response = articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            assertThat(response.getStatus()).isEqualTo(ArticleStatus.DRAFT);
        }
    }

    /**
     * getPublishedArticlesByCategorySlug 測試
     */
    @Nested
    @DisplayName("getPublishedArticlesByCategorySlug")
    class GetPublishedArticlesByCategorySlugTests {

        @Test
        @DisplayName("正常：根據分類 slug 分頁查詢已發布文章")
        void getPublishedArticlesByCategorySlug_success() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            String categorySlug = "tech";
            when(articleMapper.findPublishedPageByCategorySlug(categorySlug, 0L, 10)).thenReturn(List.of(article));
            when(articleMapper.countPublishedByCategorySlug(categorySlug)).thenReturn(1L);
            when(articleMapper.findTagsByArticleUuids(List.of(ARTICLE_UUID))).thenReturn(List.of());

            PageResult<ArticleSummaryResponse> result = articleService.getPublishedArticlesByCategorySlug(categorySlug, 1, 10);

            assertThat(result).isNotNull();
            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getTotal()).isEqualTo(1L);
            assertThat(result.getCurrent()).isEqualTo(1);
            verify(articleMapper).findPublishedPageByCategorySlug(categorySlug, 0L, 10);
            verify(articleMapper).countPublishedByCategorySlug(categorySlug);
        }

        @Test
        @DisplayName("正常：第二頁查詢，偏移量正確")
        void getPublishedArticlesByCategorySlug_secondPage_correctOffset() {
            String categorySlug = "java";
            when(articleMapper.findPublishedPageByCategorySlug(categorySlug, 10L, 10)).thenReturn(List.of());
            when(articleMapper.countPublishedByCategorySlug(categorySlug)).thenReturn(3L);
            when(articleMapper.findTagsByArticleUuids(any())).thenReturn(List.of());

            PageResult<ArticleSummaryResponse> result = articleService.getPublishedArticlesByCategorySlug(categorySlug, 2, 10);

            assertThat(result.getRecords()).isEmpty();
            verify(articleMapper).findPublishedPageByCategorySlug(categorySlug, 10L, 10);
        }

        @Test
        @DisplayName("正常：查詢結果包含標籤資訊（批次查詢）")
        void getPublishedArticlesByCategorySlug_withTags() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            String categorySlug = "spring";
            UUID tagId = UUID.randomUUID();

            when(articleMapper.findPublishedPageByCategorySlug(categorySlug, 0L, 10)).thenReturn(List.of(article));
            when(articleMapper.countPublishedByCategorySlug(categorySlug)).thenReturn(1L);

            dowob.xyz.blog.module.article.model.TagWithArticleUuid tagWithUuid =
                    new dowob.xyz.blog.module.article.model.TagWithArticleUuid();
            tagWithUuid.setArticleUuid(ARTICLE_UUID);
            tagWithUuid.setId(tagId);
            tagWithUuid.setName("Spring");
            tagWithUuid.setSlug("spring");

            when(articleMapper.findTagsByArticleUuids(List.of(ARTICLE_UUID))).thenReturn(List.of(tagWithUuid));

            PageResult<ArticleSummaryResponse> result = articleService.getPublishedArticlesByCategorySlug(categorySlug, 1, 10);

            assertThat(result.getRecords().get(0).getTags()).hasSize(1);
            assertThat(result.getRecords().get(0).getTags().get(0).getName()).isEqualTo("Spring");
        }
    }

    /**
     * extractSummary / summary 邊界測試
     */
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

            articleService.createArticle(AUTHOR_ID, request);

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

            articleService.createArticle(AUTHOR_ID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getSummary()).isNotNull();
            assertThat(captor.getValue().getSummary().length()).isLessThanOrEqualTo(200);
        }

        @Test
        @DisplayName("邊界：updateArticle 同時傳入 content 與 summary 時，summary 應以傳入值為主")
        void updateArticle_bothContentAndSummaryProvided_usesSummary() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setContent("新內容");
            request.setSummary("自訂摘要");

            articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getSummary()).isEqualTo("自訂摘要");
        }

        @Test
        @DisplayName("邊界：updateArticle 只更新 summary（content 為 null），應使用現有 content 計算自動摘要")
        void updateArticle_onlySummaryBlank_usesExistingContent() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            article.setContent("現有文章內容，用來自動生成摘要");
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setSummary("");  // 空白觸發自動摘要，content 為 null 時用現有 content

            articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
            verify(articleRepository).save(captor.capture());
            assertThat(captor.getValue().getSummary()).isNotNull();
            assertThat(captor.getValue().getSummary()).isNotBlank();
        }
    }

    /**
     * getArticleBySlug 測試
     */
    @Nested
    @DisplayName("getArticleBySlug")
    class GetArticleBySlugTests {

        @Test
        @DisplayName("正常：透過 slug 取得 PUBLISHED 文章")
        void getArticleBySlug_published_success() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            article.setSlug("test-slug-abcd1234");
            when(articleRepository.findBySlug("test-slug-abcd1234")).thenReturn(Optional.of(article));
            when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

            ArticleResponse response = articleService.getArticleBySlug("test-slug-abcd1234", null, null, "127.0.0.1");

            assertThat(response).isNotNull();
            assertThat(response.getSlug()).isEqualTo("test-slug-abcd1234");
            assertThat(response.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
        }

        @Test
        @DisplayName("異常：slug 不存在 → ARTICLE_NOT_FOUND")
        void getArticleBySlug_notFound() {
            when(articleRepository.findBySlug("nonexistent-slug")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> articleService.getArticleBySlug("nonexistent-slug", null, null, "127.0.0.1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("異常：DRAFT 文章 slug，其他用戶存取 → ARTICLE_NOT_FOUND")
        void getArticleBySlug_draftArticle_otherUserDenied() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            article.setSlug("draft-slug-1234");
            when(articleRepository.findBySlug("draft-slug-1234")).thenReturn(Optional.of(article));

            assertThatThrownBy(() -> articleService.getArticleBySlug("draft-slug-1234", OTHER_USER_ID, Role.AUTHOR, "127.0.0.1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("正常：PUBLISHED 文章透過 slug 存取，新 IP 發送 MQ 瀏覽事件")
        void getArticleBySlug_published_newIp_sendsMqEvent() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            article.setSlug("view-slug-1234");
            when(articleRepository.findBySlug("view-slug-1234")).thenReturn(Optional.of(article));
            when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

            articleService.getArticleBySlug("view-slug-1234", null, null, "127.0.0.1");

            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_VIEWED),
                    any(ArticleViewedEvent.class));
        }
    }

    /**
     * ArticleTagEvent 發送測試（Task 4：article.tagged 事件 Producer）
     */
    @Nested
    @DisplayName("ArticleTagEvent - article.tagged 事件發送")
    class ArticleTagEventTests {

        @Test
        @DisplayName("正常：publishArticle 有 tags 時，應發送 ArticleTagEvent 至 article.tagged")
        void publishArticle_withTags_shouldSendArticleTagEvent() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));

            UUID tagId1 = UUID.randomUUID();
            UUID tagId2 = UUID.randomUUID();
            when(articleMapper.findTagsByArticleUuid(ARTICLE_UUID)).thenReturn(
                    List.of(new TagInfo(tagId1, "Spring", "spring"),
                            new TagInfo(tagId2, "Java", "java")));

            articleService.publishArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            ArgumentCaptor<ArticleTagEvent> captor = ArgumentCaptor.forClass(ArticleTagEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_TAGGED),
                    captor.capture());

            ArticleTagEvent event = captor.getValue();
            assertThat(event.articleId()).isEqualTo(ARTICLE_UUID);
            assertThat(event.tagIds()).containsExactlyInAnyOrder(tagId1, tagId2);
        }

        @Test
        @DisplayName("邊界：publishArticle 無 tags 時，不發送 ArticleTagEvent")
        void publishArticle_withoutTags_shouldNotSendArticleTagEvent() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));
            when(articleMapper.findTagsByArticleUuid(ARTICLE_UUID)).thenReturn(List.of());

            articleService.publishArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID);

            verify(rabbitTemplate, never()).convertAndSend(
                    eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_TAGGED),
                    any(ArticleTagEvent.class));
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

            articleService.createArticle(AUTHOR_ID, request);

            ArgumentCaptor<ArticleTagEvent> captor = ArgumentCaptor.forClass(ArticleTagEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_TAGGED),
                    captor.capture());

            ArticleTagEvent event = captor.getValue();
            assertThat(event.articleId()).isEqualTo(ARTICLE_UUID);
            assertThat(event.tagIds()).containsExactlyInAnyOrder(tagId1, tagId2);
        }

        @Test
        @DisplayName("邊界：createArticle 無 tags 時，不發送 ArticleTagEvent")
        void createArticle_withoutTags_shouldNotSendArticleTagEvent() {
            CreateArticleRequest request = new CreateArticleRequest();
            request.setTitle("無標籤文章");
            request.setContent("內容");

            Article saved = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.save(any(Article.class))).thenReturn(saved);

            articleService.createArticle(AUTHOR_ID, request);

            verify(rabbitTemplate, never()).convertAndSend(
                    eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_TAGGED),
                    any(ArticleTagEvent.class));
        }

        @Test
        @DisplayName("正常：updateArticle 有 tags 時，應發送 ArticleTagEvent 至 article.tagged")
        void updateArticle_withTags_shouldSendArticleTagEvent() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UUID tagId1 = UUID.randomUUID();
            when(tagFacade.findOrCreateTags(List.of("Spring")))
                    .thenReturn(List.of(new TagInfo(tagId1, "Spring", "spring")));

            UpdateArticleRequest request = new UpdateArticleRequest();
            request.setTagNames(List.of("Spring"));

            articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            ArgumentCaptor<ArticleTagEvent> captor = ArgumentCaptor.forClass(ArticleTagEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_TAGGED),
                    captor.capture());

            ArticleTagEvent event = captor.getValue();
            assertThat(event.articleId()).isEqualTo(ARTICLE_UUID);
            assertThat(event.tagIds()).containsExactly(tagId1);
        }

        @Test
        @DisplayName("邊界：updateArticle tagNames 為 null 時，不發送 ArticleTagEvent")
        void updateArticle_withNullTagNames_shouldNotSendArticleTagEvent() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(articleRepository.save(any(Article.class))).thenReturn(article);

            UpdateArticleRequest request = new UpdateArticleRequest();

            articleService.updateArticle(AUTHOR_ID, Role.AUTHOR, ARTICLE_UUID, request);

            verify(rabbitTemplate, never()).convertAndSend(
                    eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_TAGGED),
                    any(ArticleTagEvent.class));
        }
    }
}
