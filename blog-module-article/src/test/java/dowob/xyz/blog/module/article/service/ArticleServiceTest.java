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
import dowob.xyz.blog.module.article.model.dto.response.EditorArticleResponse;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionTemplate;

import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.article.mapper.CategoryMapper;
import dowob.xyz.blog.module.article.model.Category;
import dowob.xyz.blog.module.article.model.CategoryWithArticleId;
import dowob.xyz.blog.module.article.repository.CategoryRepository;
import dowob.xyz.blog.module.article.service.ViewCountService;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private ArticleEventPublisher articleEventPublisher;

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

    /** Mock：Markdown 渲染器（含 OWASP 白名單） */
    @Mock
    private ArticleMarkdownRenderer markdownRenderer;

    @Mock
    private ArticleCommandSubService commandSubService;

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

        // markdownRenderer stub：render 回傳簡單 <p> 包覆內容，toPlainText 回傳原始內容
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

        ArticleEntityFinder articleEntityFinder = new ArticleEntityFinder(articleRepository);
        ArticleResponseMapper articleResponseMapper =
                new ArticleResponseMapper(articleMapper, categoryMapper, userFacade, viewCountService);
        ArticleViewSubService articleViewSubService =
                new ArticleViewSubService(stringRedisTemplate, articleEventPublisher);
        articleService = new ArticleServiceImpl(
                articleRepository,
                articleMapper,
                articleEntityFinder,
                articleResponseMapper,
                articleViewSubService,
                commandSubService);
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
            verify(articleEventPublisher).publishViewed(ARTICLE_UUID);
        }

        @Test
        @DisplayName("正常：DRAFT 文章，作者本人可存取")
        void getArticle_draft_authorCanAccess() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            ArticleResponse response = articleService.getArticleByUuid(ARTICLE_UUID, AUTHOR_ID, Role.AUTHOR,
                    "127.0.0.1");

            assertThat(response).isNotNull();
            verify(articleEventPublisher, never()).publishViewed(any());
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
            verify(articleEventPublisher, never()).publishViewed(any());
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

            verify(articleEventPublisher).publishViewed(ARTICLE_UUID);
            verify(articleMapper, never()).incrementViewCountBatch(any(UUID.class), anyLong());
        }

        @Test
        @DisplayName("正常：相同 IP 5 分鐘內重複存取（setIfAbsent 回傳 false），不發送 MQ 瀏覽事件")
        void getArticleByUuid_shouldNotSendMqEvent_whenSameIpWithinWindow() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

            articleService.getArticleByUuid(ARTICLE_UUID, null, null, "127.0.0.1");

            verify(articleEventPublisher, never()).publishViewed(any());
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
     * Response DTO 欄位完整性測試
     */
    @Nested
    @DisplayName("Response DTO 欄位完整性")
    class ResponseDtoFieldsTests {




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

            verify(articleEventPublisher).publishViewed(any(UUID.class));
        }
    }

    /**
     * liked 欄位測試（ArticleService 層）
     *
     * <p>重構後，ArticleService 的 read 方法不再填充 liked 欄位（由 ArticleQueryService 負責）。
     * 此測試驗證 ArticleService 回傳的 liked 欄位為 null（而非 false 或 true）。
     * liked 的完整填充測試請參閱 ArticleQueryServiceTest。</p>
     */
    @Nested
    @DisplayName("liked 欄位（ArticleService 不填充）")
    class LikedFieldTests {

        @Test
        @DisplayName("正常：getPublishedArticles 回傳的 liked 為 null（填充由 ArticleQueryService 負責）")
        void getPublishedArticles_likedIsNull() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleMapper.findPublishedPage(0L, 10)).thenReturn(List.of(article));
            when(articleMapper.countPublished()).thenReturn(1L);

            PageResult<ArticleSummaryResponse> result = articleService.getPublishedArticles(1, 10);

            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getRecords().get(0).getLiked()).isNull();
        }

        @Test
        @DisplayName("正常：getArticleByUuid 回傳的 liked 為 null（填充由 ArticleQueryService 負責）")
        void getArticleByUuid_likedIsNull() {
            Article article = buildArticle(ArticleStatus.PUBLISHED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));
            when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

            ArticleResponse response = articleService.getArticleByUuid(ARTICLE_UUID, null, null, "127.0.0.1");

            assertThat(response.getLiked()).isNull();
        }
    }

    /**
     * getArticleForEdit 測試
     */
    @Nested
    @DisplayName("getArticleForEdit")
    class GetArticleForEditTests {

        @Test
        @DisplayName("正常：作者本人可取得自己的文章用於編輯")
        void getArticleForEdit_authorCanAccess() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            EditorArticleResponse response = articleService.getArticleForEdit(ARTICLE_UUID, AUTHOR_ID);

            assertThat(response).isNotNull();
            assertThat(response.getUuid()).isEqualTo(ARTICLE_UUID);
            assertThat(response.getContent()).isEqualTo("測試內容");
            assertThat(response.getStatus()).isEqualTo(ArticleStatus.DRAFT);
        }

        @Test
        @DisplayName("正常：REJECTED 狀態的文章可供作者取得編輯")
        void getArticleForEdit_rejectedArticle_authorCanAccess() {
            Article article = buildArticle(ArticleStatus.REJECTED);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            EditorArticleResponse response = articleService.getArticleForEdit(ARTICLE_UUID, AUTHOR_ID);

            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(ArticleStatus.REJECTED);
        }

        @Test
        @DisplayName("異常：文章不存在 → ARTICLE_NOT_FOUND")
        void getArticleForEdit_notFound() {
            when(articleRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> articleService.getArticleForEdit(UUID.randomUUID(), AUTHOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("異常：非作者嘗試存取他人文章 → ARTICLE_ACCESS_DENIED")
        void getArticleForEdit_otherUser_denied() {
            Article article = buildArticle(ArticleStatus.DRAFT);
            when(articleRepository.findByUuid(ARTICLE_UUID)).thenReturn(Optional.of(article));

            assertThatThrownBy(() -> articleService.getArticleForEdit(ARTICLE_UUID, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage());
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
        @DisplayName("incrementCommentCount delegates to commandSubService")
        void incrementCommentCount_delegatesToCommandSubService() {
            articleService.incrementCommentCount(1L);

            verify(commandSubService).incrementCommentCount(1L);
        }

        @Test
        @DisplayName("decrementCommentCount delegates to commandSubService")
        void decrementCommentCount_delegatesToCommandSubService() {
            articleService.decrementCommentCount(1L);

            verify(commandSubService).decrementCommentCount(1L);
        }

        @Test
        @DisplayName("incrementLikeCount delegates to commandSubService")
        void incrementLikeCount_delegatesToCommandSubService() {
            articleService.incrementLikeCount(1L);

            verify(commandSubService).incrementLikeCount(1L);
        }

        @Test
        @DisplayName("decrementLikeCount delegates to commandSubService")
        void decrementLikeCount_delegatesToCommandSubService() {
            articleService.decrementLikeCount(1L);

            verify(commandSubService).decrementLikeCount(1L);
        }

        @Test
        @DisplayName("updateSeriesAssignment delegates to commandSubService")
        void updateSeriesAssignment_delegatesToCommandSubService() {
            articleService.updateSeriesAssignment(1L, 10L, 2);

            verify(commandSubService).updateSeriesAssignment(1L, 10L, 2);
        }
    }
}
