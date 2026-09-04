package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleTrendingData;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.ArticleRecommendMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.ArticleSummaryRow;
import dowob.xyz.blog.module.article.model.ArticleTagRow;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.service.ArticleEventPublisher;
import dowob.xyz.blog.module.article.service.ArticleFileBinder;
import dowob.xyz.blog.module.article.service.ArticleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ArticleFacadeImpl 單元測試
 *
 * <p>驗證各推薦查詢方法的邏輯與 Mapper 呼叫行為。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArticleFacadeImplTest {

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private UserFacade userFacade;

    @Mock
    private ArticleRecommendMapper recommendMapper;

    @Mock
    private ArticleService articleService;

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private TagFacade tagFacade;

    @Mock
    private ArticleEventPublisher articleEventPublisher;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private FileFacade fileFacade;

    private ArticleFacadeImpl facade;

    private static final UUID ARTICLE_UUID = UUID.randomUUID();
    private static final Long ARTICLE_ID = 1L;

    @BeforeEach
    void setUp() {
        /**
         * 比照 ArticleCommandSubServiceTest：用「真實」的 ArticleFileBinder 包裝被 mock 的
         * fileFacade，讓 applyRestoreContent 綁定回填測試能驗證到真正的正則擷取邏輯，
         * 而不只是 mock-to-mock 的空殼呼叫。
         */
        ArticleFileBinder articleFileBinder = new ArticleFileBinder(fileFacade);

        facade = new ArticleFacadeImpl(
            articleMapper, userFacade, recommendMapper, articleService,
            articleRepository, tagFacade, articleEventPublisher, transactionTemplate,
            articleFileBinder
        );

        /** 讓 mock 的 TransactionTemplate 直接執行 callback，使受測方法主體照常運行 */
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    // ─── SP-B helper：建立測試用 Article entity ───
    private Article buildArticle(Long id, UUID uuid, ArticleStatus status, Long seriesId, Integer pos) {
        Article a = new Article();
        a.setId(id);
        a.setUuid(uuid);
        a.setAuthorId(99L);
        a.setStatus(status);
        a.setSeriesId(seriesId);
        a.setSeriesPosition(pos);
        return a;
    }

    private ArticleSummaryRow row(Long id, UUID uuid, String title) {
        ArticleSummaryRow row = new ArticleSummaryRow();
        row.setId(id);
        row.setUuid(uuid.toString());
        row.setTitle(title);
        row.setSlug("slug");
        row.setSummary("summary");
        row.setAuthorNickname("Author");
        row.setViewCount(100L);
        row.setLikeCount(10L);
        row.setPublishedAt(LocalDateTime.now());
        return row;
    }

    @Nested
    class GetPublishedArticleBasicInfo {

        @Test
        @DisplayName("文章不存在時回傳 empty")
        void whenArticleNotFound_returnsEmpty() {
            when(recommendMapper.findPublishedIdByUuid(ARTICLE_UUID)).thenReturn(null);

            Optional<ArticleBasicInfo> result = facade.getPublishedArticleBasicInfo(ARTICLE_UUID);

            assertThat(result).isEmpty();
            verify(recommendMapper, never()).findTagIdsByArticleUuid(any(UUID.class));
        }

        @Test
        @DisplayName("文章存在時回傳基本資訊含標籤")
        void whenArticleExists_returnsBasicInfoWithTags() {
            UUID tag1 = UUID.randomUUID();
            UUID tag2 = UUID.randomUUID();
            when(recommendMapper.findPublishedIdByUuid(ARTICLE_UUID)).thenReturn(ARTICLE_ID);
            when(recommendMapper.findTagIdsByArticleUuid(ARTICLE_UUID))
                    .thenReturn(List.of(tag1.toString(), tag2.toString()));

            Optional<ArticleBasicInfo> result = facade.getPublishedArticleBasicInfo(ARTICLE_UUID);

            assertThat(result).isPresent();
            assertThat(result.get().uuid()).isEqualTo(ARTICLE_UUID);
            assertThat(result.get().tagIds()).containsExactly(tag1, tag2);
        }
    }

    @Nested
    class GetArticlesByTagIds {

        @Test
        @DisplayName("tagIds 為空時不查詢直接回傳空列表")
        void whenTagIdsEmpty_skipsQueryAndReturnsEmptyList() {
            List<ArticleSummaryInfo> result = facade.getArticlesByTagIds(
                    Collections.emptyList(), ARTICLE_UUID, 5);

            assertThat(result).isEmpty();
            verify(recommendMapper, never()).findByTagIds(anyList(), any(UUID.class), anyInt());
        }

        @Test
        @DisplayName("tagIds 為 null 時不查詢直接回傳空列表")
        void whenTagIdsNull_skipsQueryAndReturnsEmptyList() {
            List<ArticleSummaryInfo> result = facade.getArticlesByTagIds(null, ARTICLE_UUID, 5);

            assertThat(result).isEmpty();
            verify(recommendMapper, never()).findByTagIds(anyList(), any(UUID.class), anyInt());
        }

        @Test
        @DisplayName("正常查詢並組裝標籤名稱")
        void queriesAndAssemblesTagNames() {
            UUID tagUuid = UUID.randomUUID();
            ArticleSummaryRow row = row(ARTICLE_ID, ARTICLE_UUID, "Test");
            ArticleTagRow tagRow = new ArticleTagRow();
            tagRow.setArticleUuid(ARTICLE_UUID.toString());
            tagRow.setTagName("Java");

            when(recommendMapper.findByTagIds(List.of(tagUuid.toString()), ARTICLE_UUID, 5))
                    .thenReturn(List.of(row));
            when(recommendMapper.findTagsByArticleUuids(List.of(ARTICLE_UUID.toString())))
                    .thenReturn(List.of(tagRow));

            List<ArticleSummaryInfo> result = facade.getArticlesByTagIds(List.of(tagUuid), ARTICLE_UUID, 5);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).title()).isEqualTo("Test");
            assertThat(result.get(0).tagNames()).containsExactly("Java");
        }

        @Test
        @DisplayName("查詢結果為空時回傳空列表")
        void whenMapperReturnsEmpty_returnsEmptyList() {
            UUID tagUuid = UUID.randomUUID();
            when(recommendMapper.findByTagIds(List.of(tagUuid.toString()), ARTICLE_UUID, 5))
                    .thenReturn(Collections.emptyList());

            List<ArticleSummaryInfo> result = facade.getArticlesByTagIds(List.of(tagUuid), ARTICLE_UUID, 5);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("文章無對應標籤時 tagNames 回傳空列表")
        void whenNoTagsForArticle_tagNamesIsEmpty() {
            UUID tagUuid = UUID.randomUUID();
            ArticleSummaryRow row = row(ARTICLE_ID, ARTICLE_UUID, "No Tags");

            when(recommendMapper.findByTagIds(List.of(tagUuid.toString()), ARTICLE_UUID, 5))
                    .thenReturn(List.of(row));
            when(recommendMapper.findTagsByArticleUuids(List.of(ARTICLE_UUID.toString())))
                    .thenReturn(Collections.emptyList());

            List<ArticleSummaryInfo> result = facade.getArticlesByTagIds(List.of(tagUuid), ARTICLE_UUID, 5);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).tagNames()).isEmpty();
        }
    }

    @Nested
    class GetPublishedArticlesByUuids {

        @Test
        @DisplayName("uuids 為空時不查詢直接回傳空列表")
        void whenUuidsEmpty_skipsQueryAndReturnsEmptyList() {
            List<ArticleSummaryInfo> result = facade.getPublishedArticlesByUuids(Collections.emptyList());

            assertThat(result).isEmpty();
            verify(recommendMapper, never()).findByUuids(anyList());
        }

        @Test
        @DisplayName("uuids 為 null 時不查詢直接回傳空列表")
        void whenUuidsNull_skipsQueryAndReturnsEmptyList() {
            List<ArticleSummaryInfo> result = facade.getPublishedArticlesByUuids(null);

            assertThat(result).isEmpty();
            verify(recommendMapper, never()).findByUuids(anyList());
        }

        @Test
        @DisplayName("正常查詢並組裝標籤名稱")
        void queriesAndAssemblesTagNames() {
            ArticleSummaryRow row = row(ARTICLE_ID, ARTICLE_UUID, "By UUID");
            ArticleTagRow tagRow = new ArticleTagRow();
            tagRow.setArticleUuid(ARTICLE_UUID.toString());
            tagRow.setTagName("Spring");

            when(recommendMapper.findByUuids(List.of(ARTICLE_UUID)))
                    .thenReturn(List.of(row));
            when(recommendMapper.findTagsByArticleUuids(List.of(ARTICLE_UUID.toString())))
                    .thenReturn(List.of(tagRow));

            List<ArticleSummaryInfo> result = facade.getPublishedArticlesByUuids(List.of(ARTICLE_UUID));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).title()).isEqualTo("By UUID");
            assertThat(result.get(0).tagNames()).containsExactly("Spring");
        }
    }

    @Nested
    class GetRecentPublishedArticles {

        @Test
        @DisplayName("正常查詢並組裝結果")
        void queriesAndAssemblesResults() {
            ArticleSummaryRow row = row(ARTICLE_ID, ARTICLE_UUID, "Recent");
            ArticleTagRow tagRow = new ArticleTagRow();
            tagRow.setArticleUuid(ARTICLE_UUID.toString());
            tagRow.setTagName("Java");

            when(recommendMapper.findRecentPublished(ARTICLE_UUID, 5))
                    .thenReturn(List.of(row));
            when(recommendMapper.findTagsByArticleUuids(List.of(ARTICLE_UUID.toString())))
                    .thenReturn(List.of(tagRow));

            List<ArticleSummaryInfo> result = facade.getRecentPublishedArticles(ARTICLE_UUID, 5);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).title()).isEqualTo("Recent");
            assertThat(result.get(0).tagNames()).containsExactly("Java");
        }

        @Test
        @DisplayName("無結果時回傳空列表")
        void whenNoResults_returnsEmptyList() {
            when(recommendMapper.findRecentPublished(ARTICLE_UUID, 5))
                    .thenReturn(Collections.emptyList());

            List<ArticleSummaryInfo> result = facade.getRecentPublishedArticles(ARTICLE_UUID, 5);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    class FindAllPublishedForIndex {

        @Test
        @DisplayName("正確呼叫 Mapper 並組裝索引資料")
        void callsMapperAndAssemblesIndexData() {
            Article article = new Article();
            article.setId(ARTICLE_ID);
            article.setUuid(ARTICLE_UUID);
            article.setTitle("Test Article");
            article.setSlug("test-article");
            article.setSummary("summary");
            article.setContent("content");
            article.setAuthorId(10L);

            TagInfo tagInfo = new TagInfo(UUID.randomUUID(), "Java", "java");

            when(articleMapper.findAllPublished()).thenReturn(List.of(article));
            when(articleMapper.findTagsByArticleUuid(ARTICLE_UUID)).thenReturn(List.of(tagInfo));
            when(userFacade.getUserUsernameById(10L)).thenReturn(Optional.of("user1"));
            when(userFacade.getUserNicknameById(10L)).thenReturn(Optional.of("User One"));

            List<ArticleIndexData> result = facade.findAllPublishedForIndex();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).articleUuid()).isEqualTo(ARTICLE_UUID);
            assertThat(result.get(0).tags()).hasSize(1);
            assertThat(result.get(0).tags().get(0).name()).isEqualTo("Java");
        }

        @Test
        @DisplayName("無已發布文章時回傳空列表")
        void whenNoPublishedArticles_returnsEmptyList() {
            when(articleMapper.findAllPublished()).thenReturn(List.of());

            assertThat(facade.findAllPublishedForIndex()).isEmpty();
        }

        @Test
        @DisplayName("文章 content 為 null 時 stripMarkdown 回傳空字串")
        void whenContentIsNull_stripMarkdownReturnsEmpty() {
            Article article = new Article();
            article.setId(ARTICLE_ID);
            article.setUuid(ARTICLE_UUID);
            article.setTitle("Null Content");
            article.setSlug("null-content");
            article.setSummary("summary");
            article.setContent(null);
            article.setAuthorId(10L);

            when(articleMapper.findAllPublished()).thenReturn(List.of(article));
            when(articleMapper.findTagsByArticleUuid(ARTICLE_UUID)).thenReturn(List.of());
            when(userFacade.getUserUsernameById(10L)).thenReturn(Optional.of("user1"));
            when(userFacade.getUserNicknameById(10L)).thenReturn(Optional.of("User One"));

            List<ArticleIndexData> result = facade.findAllPublishedForIndex();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).contentText()).isEmpty();
        }

        @Test
        @DisplayName("UserFacade 回傳 empty 時 author 欄位為 null")
        void whenUserNotFound_authorFieldsAreNull() {
            Article article = new Article();
            article.setId(ARTICLE_ID);
            article.setUuid(ARTICLE_UUID);
            article.setTitle("Unknown Author");
            article.setSlug("unknown-author");
            article.setSummary("summary");
            article.setContent("content");
            article.setAuthorId(999L);

            when(articleMapper.findAllPublished()).thenReturn(List.of(article));
            when(articleMapper.findTagsByArticleUuid(ARTICLE_UUID)).thenReturn(List.of());
            when(userFacade.getUserUsernameById(999L)).thenReturn(Optional.empty());
            when(userFacade.getUserNicknameById(999L)).thenReturn(Optional.empty());

            List<ArticleIndexData> result = facade.findAllPublishedForIndex();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).authorUsername()).isNull();
            assertThat(result.get(0).authorNickname()).isNull();
        }
    }

    @Nested
    class GetArticlesPublishedAfter {

        @Test
        @DisplayName("正確呼叫 Mapper 並回傳結果")
        void callsMapperAndReturnsResult() {
            LocalDateTime since = LocalDateTime.now().minusDays(7);
            ArticleTrendingData data = new ArticleTrendingData(ARTICLE_UUID, 100L, 10L, since);
            when(recommendMapper.findPublishedAfter(since)).thenReturn(List.of(data));

            List<ArticleTrendingData> result = facade.getArticlesPublishedAfter(since);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).uuid()).isEqualTo(ARTICLE_UUID);
        }
    }

    // ─── SP-B: 5 read method delegate + Article→ArticleData 轉換 ───

    @Nested
    @DisplayName("SP-B findIdByUuid")
    class FindIdByUuid {

        @Test
        @DisplayName("委派 articleService.findIdByUuid 並回傳結果")
        void delegatesToArticleService() {
            UUID uuid = UUID.randomUUID();
            when(articleService.findIdByUuid(uuid)).thenReturn(100L);

            Long result = facade.findIdByUuid(uuid);

            assertThat(result).isEqualTo(100L);
            verify(articleService).findIdByUuid(uuid);
        }
    }

    @Nested
    @DisplayName("SP-B findByUuid")
    class FindByUuidDelegate {

        @Test
        @DisplayName("文章存在時回傳 ArticleData（含 Article→ArticleData 轉換驗證）")
        void returnsArticleData_convertedFromEntity() {
            UUID uuid = UUID.randomUUID();
            Article a = buildArticle(100L, uuid, ArticleStatus.PUBLISHED, 50L, 3);
            when(articleService.findByUuid(uuid)).thenReturn(Optional.of(a));

            Optional<ArticleData> result = facade.findByUuid(uuid);

            assertThat(result).isPresent();
            ArticleData data = result.get();
            assertThat(data.id()).isEqualTo(100L);
            assertThat(data.uuid()).isEqualTo(uuid);
            assertThat(data.authorId()).isEqualTo(99L);
            assertThat(data.status()).isEqualTo("PUBLISHED");
            assertThat(data.seriesId()).isEqualTo(50L);
            assertThat(data.seriesPosition()).isEqualTo(3);
        }

        @Test
        @DisplayName("文章不存在時回傳 empty Optional")
        void emptyArticle_returnsEmptyOptional() {
            UUID uuid = UUID.randomUUID();
            when(articleService.findByUuid(uuid)).thenReturn(Optional.empty());

            Optional<ArticleData> result = facade.findByUuid(uuid);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("SP-B findById")
    class FindByIdDelegate {

        @Test
        @DisplayName("回傳 ArticleData（null seriesId / seriesPosition）")
        void returnsArticleData_convertedFromEntity() {
            Article a = buildArticle(100L, UUID.randomUUID(), ArticleStatus.DRAFT, null, null);
            when(articleService.findById(100L)).thenReturn(Optional.of(a));

            Optional<ArticleData> result = facade.findById(100L);

            assertThat(result).isPresent();
            assertThat(result.get().status()).isEqualTo("DRAFT");
            assertThat(result.get().seriesId()).isNull();
            assertThat(result.get().seriesPosition()).isNull();
        }
    }

    @Nested
    @DisplayName("SP-B findByIds")
    class FindByIdsDelegate {

        @Test
        @DisplayName("回傳 ArticleData 列表（含 status 驗證）")
        void returnsArticleDataList() {
            Article a1 = buildArticle(1L, UUID.randomUUID(), ArticleStatus.PUBLISHED, null, null);
            Article a2 = buildArticle(2L, UUID.randomUUID(), ArticleStatus.DRAFT, null, null);
            when(articleService.findByIds(List.of(1L, 2L))).thenReturn(List.of(a1, a2));

            List<ArticleData> result = facade.findByIds(List.of(1L, 2L));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).id()).isEqualTo(1L);
            assertThat(result.get(0).status()).isEqualTo("PUBLISHED");
            assertThat(result.get(1).id()).isEqualTo(2L);
            assertThat(result.get(1).status()).isEqualTo("DRAFT");
        }
    }

    @Nested
    @DisplayName("SP-B findBySeriesIdOrderByPosition")
    class FindBySeriesIdOrderByPositionDelegate {

        @Test
        @DisplayName("回傳 ArticleData 列表（含 seriesPosition 驗證）")
        void returnsArticleDataList() {
            Article a1 = buildArticle(1L, UUID.randomUUID(), ArticleStatus.PUBLISHED, 50L, 1);
            Article a2 = buildArticle(2L, UUID.randomUUID(), ArticleStatus.PUBLISHED, 50L, 2);
            when(articleService.findBySeriesIdOrderByPosition(50L)).thenReturn(List.of(a1, a2));

            List<ArticleData> result = facade.findBySeriesIdOrderByPosition(50L);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).seriesPosition()).isEqualTo(1);
            assertThat(result.get(1).seriesPosition()).isEqualTo(2);
        }
    }

    // ─── SP-B: 5 write method delegate verify ───

    @Nested
    @DisplayName("SP-B write method delegates")
    class WriteMethodDelegates {

        @Test
        @DisplayName("incrementCommentCount 委派 articleService")
        void incrementCommentCount_delegatesToArticleService() {
            facade.incrementCommentCount(100L);
            verify(articleService).incrementCommentCount(100L);
        }

        @Test
        @DisplayName("decrementCommentCount 委派 articleService")
        void decrementCommentCount_delegatesToArticleService() {
            facade.decrementCommentCount(100L);
            verify(articleService).decrementCommentCount(100L);
        }

        @Test
        @DisplayName("incrementLikeCount 委派 articleService")
        void incrementLikeCount_delegatesToArticleService() {
            facade.incrementLikeCount(100L);
            verify(articleService).incrementLikeCount(100L);
        }

        @Test
        @DisplayName("decrementLikeCount 委派 articleService")
        void decrementLikeCount_delegatesToArticleService() {
            facade.decrementLikeCount(100L);
            verify(articleService).decrementLikeCount(100L);
        }

        @Test
        @DisplayName("updateSeriesAssignment 委派 articleService")
        void updateSeriesAssignment_delegatesToArticleService() {
            facade.updateSeriesAssignment(100L, 50L, 3);
            verify(articleService).updateSeriesAssignment(100L, 50L, 3);
        }
    }

    // ─── SP-D 新增：findContentById + applyRestoreContent ───

    @Nested
    @DisplayName("findContentById（SP-D 新增）")
    class FindContentById {

        @Test
        @DisplayName("article 存在 → return Optional 含 ArticleContentData")
        void findContentById_existing_returnsContentData() {
            // given
            Long articleId = 100L;
            UUID articleUuid = UUID.randomUUID();
            Article article = new Article();
            article.setId(articleId);
            article.setUuid(articleUuid);
            article.setAuthorId(5L);
            article.setTitle("Test Title");
            article.setSlug("test-slug");
            article.setContent("# Hello");
            article.setSummary("summary");
            article.setCoverImageUrl("https://cdn.example/cover.jpg");
            article.setStatus(ArticleStatus.PUBLISHED);
            when(articleService.findById(articleId)).thenReturn(Optional.of(article));

            // when
            Optional<ArticleContentData> result = facade.findContentById(articleId);

            // then
            assertThat(result).isPresent();
            ArticleContentData data = result.get();
            assertThat(data.id()).isEqualTo(articleId);
            assertThat(data.uuid()).isEqualTo(articleUuid);
            assertThat(data.authorId()).isEqualTo(5L);
            assertThat(data.title()).isEqualTo("Test Title");
            assertThat(data.slug()).isEqualTo("test-slug");
            assertThat(data.content()).isEqualTo("# Hello");
            assertThat(data.summary()).isEqualTo("summary");
            assertThat(data.coverImageUrl()).isEqualTo("https://cdn.example/cover.jpg");
            assertThat(data.status()).isEqualTo("PUBLISHED");
        }

        @Test
        @DisplayName("article 不存在 → return Optional.empty")
        void findContentById_notFound_returnsEmpty() {
            when(articleService.findById(999L)).thenReturn(Optional.empty());

            Optional<ArticleContentData> result = facade.findContentById(999L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("article.status 為 null → ArticleContentData.status 為 null")
        void findContentById_statusNull_statusFieldNull() {
            Article article = new Article();
            article.setId(100L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(5L);
            article.setStatus(null);
            when(articleService.findById(100L)).thenReturn(Optional.of(article));

            Optional<ArticleContentData> result = facade.findContentById(100L);

            assertThat(result).isPresent();
            assertThat(result.get().status()).isNull();
        }
    }

    @Nested
    @DisplayName("applyRestoreContent atomic（SP-D 新增）")
    class ApplyRestoreContent {

        private Long articleId;
        private UUID articleUuid;
        private Article existing;

        @BeforeEach
        void setUpArticle() {
            articleId = 100L;
            articleUuid = UUID.randomUUID();
            existing = new Article();
            existing.setId(articleId);
            existing.setUuid(articleUuid);
            existing.setStatus(ArticleStatus.DRAFT);
            when(articleRepository.findById(articleId)).thenReturn(Optional.of(existing));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("article 不存在 → throw ARTICLE_NOT_FOUND（code A0201）")
        void applyRestoreContent_articleNotFound_throws() {
            when(articleRepository.findById(999L)).thenReturn(Optional.empty());
            ArticleRestoreData data = new ArticleRestoreData(
                "T", "s", "c", "sum", null, "<p>c</p>", "[]", List.of()
            );

            assertThatThrownBy(() -> facade.applyRestoreContent(999L, data))
                .isInstanceOf(BusinessException.class)
                .extracting(t -> ((BusinessException) t).getCode())
                .isEqualTo("A0201");

            verify(articleRepository, never()).save(any(Article.class));
            verifyNoInteractions(tagFacade, articleEventPublisher, fileFacade);
        }

        @Test
        @DisplayName("PUBLISHED article 還原 → publishContentChanged(RESTORED) + publishUpdated 都發")
        void applyRestoreContent_publishedArticle_publishesBoth() {
            /*
             * SEC-02 後：publishUpdated 的判準是「文章現在的狀態」，不再是快照當時的狀態
             * （ArticleRestoreData 已無 status 欄位），因此本案例的前提改由文章本身是 PUBLISHED 表達。
             */
            existing.setStatus(ArticleStatus.PUBLISHED);
            ArticleRestoreData data = new ArticleRestoreData(
                "New Title", "new-slug", "# New", "New summary", "https://cdn/new.jpg",
                "<p>New</p>", "[]", List.of(UUID.randomUUID(), UUID.randomUUID())
            );

            facade.applyRestoreContent(articleId, data);

            assertThat(existing.getTitle()).isEqualTo("New Title");
            assertThat(existing.getSlug()).isEqualTo("new-slug");
            assertThat(existing.getContent()).isEqualTo("# New");
            assertThat(existing.getSummary()).isEqualTo("New summary");
            assertThat(existing.getCoverImageUrl()).isEqualTo("https://cdn/new.jpg");
            assertThat(existing.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
            assertThat(existing.getContentHtml()).isEqualTo("<p>New</p>");

            verify(articleRepository).save(existing);
            verify(tagFacade).syncArticleTags(eq(articleUuid), eq(data.tags()));
            verify(articleEventPublisher).publishContentChanged(existing, ArticleContentChangedEvent.Action.RESTORED);
            verify(articleEventPublisher).publishUpdated(existing);
        }

        @Test
        @DisplayName("DRAFT article 還原 → 只發 publishContentChanged，不發 publishUpdated")
        void applyRestoreContent_draftArticle_publishesOnlyContentChanged() {
            ArticleRestoreData data = new ArticleRestoreData(
                "T", "s", "c", "sum", null, "<p>c</p>", "[]", List.of()
            );

            facade.applyRestoreContent(articleId, data);

            assertThat(existing.getStatus()).isEqualTo(ArticleStatus.DRAFT);
            verify(articleEventPublisher).publishContentChanged(existing, ArticleContentChangedEvent.Action.RESTORED);
            verify(articleEventPublisher, never()).publishUpdated(any());
        }

        @Test
        @DisplayName("還原不改動 article.status（SEC-02：還原資料已不含 status）")
        void applyRestoreContent_doesNotChangeStatus() {
            ArticleStatus before = existing.getStatus();
            ArticleRestoreData data = new ArticleRestoreData(
                "T", "s", "c", "sum", null, "<p>c</p>", "[]", List.of()
            );

            facade.applyRestoreContent(articleId, data);

            assertThat(existing.getStatus()).isEqualTo(before);
        }

        /**
         * 版本還原曾是唯一繞過 {@code validateStatusTransition} 的狀態轉換路徑：REJECTED 的文章
         * 只要還原一份舊的 PUBLISHED 快照就會直接變成 PUBLISHED，帶著 admin 寫的內部駁回評語
         * 進入匿名可讀的公開狀態。SEC-02 已從 {@code ArticleRestoreData} 移除 status 欄位堵住此路，
         * 本測試從「內部評語不得外流」的角度把該保證釘住：還原不得改狀態，rejectReason 也不受影響。
         */
        @Test
        @DisplayName("REJECTED 文章還原 → 狀態與 rejectReason 皆不動（還原不得成為駁回文章洗白成公開的路徑）")
        void applyRestoreContent_rejectedArticle_keepsStatusAndRejectReason() {
            existing.setStatus(ArticleStatus.REJECTED);
            existing.setRejectReason("內部審核評語：抄襲疑慮，勿對外");
            ArticleRestoreData data = new ArticleRestoreData(
                "T", "s", "c", "sum", null, "<p>c</p>", "[]", List.of()
            );

            facade.applyRestoreContent(articleId, data);

            assertThat(existing.getStatus()).isEqualTo(ArticleStatus.REJECTED);
            assertThat(existing.getRejectReason()).isEqualTo("內部審核評語：抄襲疑慮，勿對外");
            verify(articleEventPublisher, never()).publishUpdated(any());
        }

        @Test
        @DisplayName("tags 為 null → syncArticleTags 用空清單")
        void applyRestoreContent_tagsNull_syncWithEmptyList() {
            ArticleRestoreData data = new ArticleRestoreData(
                "T", "s", "c", "sum", null, "<p>c</p>", "[]", null
            );

            facade.applyRestoreContent(articleId, data);

            verify(tagFacade).syncArticleTags(articleUuid, List.of());
        }

        @Test
        @DisplayName("toc 一併還原：article.toc 應更新為 data.toc()，不可停留在還原前的舊 TOC")
        void applyRestoreContent_setsTocFromRestoreData() {
            existing.setToc("[{\"id\":\"heading-舊章節\",\"text\":\"舊章節\",\"level\":2}]");
            String restoredToc = "[{\"id\":\"heading-新章節\",\"text\":\"新章節\",\"level\":2}]";
            ArticleRestoreData data = new ArticleRestoreData(
                "T", "s", "## 新章節", "sum", null, "<h2 id=\"heading-新章節\">新章節</h2>",
                restoredToc, List.of()
            );

            facade.applyRestoreContent(articleId, data);

            assertThat(existing.getToc()).isEqualTo(restoredToc);
        }

        @Test
        @DisplayName("toc 為 null → 存空 JSON 陣列（維持 articles.toc 恆為合法陣列的不變量）")
        void applyRestoreContent_tocNull_storesEmptyJsonArray() {
            existing.setToc("[{\"id\":\"heading-舊章節\",\"text\":\"舊章節\",\"level\":2}]");
            ArticleRestoreData data = new ArticleRestoreData(
                "T", "s", "c", "sum", null, "<p>c</p>", null, List.of()
            );

            facade.applyRestoreContent(articleId, data);

            assertThat(existing.getToc()).isEqualTo("[]");
        }

        @Test
        @DisplayName("invocation 順序：save → syncArticleTags → publishEvents")
        void applyRestoreContent_invocationOrder_saveThenSyncTagsThenPublish() {
            /** publishUpdated 只在文章現況為 PUBLISHED 時發，故順序驗證需以 PUBLISHED 文章為前提 */
            existing.setStatus(ArticleStatus.PUBLISHED);
            ArticleRestoreData data = new ArticleRestoreData(
                "T", "s", "c", "sum", null, "<p>c</p>", "[]", List.of()
            );

            facade.applyRestoreContent(articleId, data);

            InOrder inOrder = inOrder(articleRepository, tagFacade, articleEventPublisher);
            inOrder.verify(articleRepository).save(existing);
            inOrder.verify(tagFacade).syncArticleTags(eq(articleUuid), anyList());
            inOrder.verify(articleEventPublisher).publishContentChanged(existing, ArticleContentChangedEvent.Action.RESTORED);
            inOrder.verify(articleEventPublisher).publishUpdated(existing);
        }

        /**
         * 修復：版本還原路徑繞過檔案綁定掃描，導致還原後文章圖片破圖。
         *
         * <p><strong>邊界分析場景表</strong></p>
         * <pre>
         * # | 情境                                   | 輸入                                  | 預期行為
         * 1 | 還原內容含 2 張圖                       | saved.getContent() 含 2 個檔案連結     | bindFilesToArticle 以這 2 個 uuid 呼叫一次
         *   |（且必須掃描 DB 實際存下的內容，而非 data.content()，防部分更新時漏字）|            |
         * 2 | 還原內容無圖                             | saved.getContent() 不含任何檔案連結    | 以空清單呼叫（解除該文章既有綁定，正確行為）
         * 3 | 綁定失敗（facade 拋例外）                 | bindFilesToArticle 丟 RuntimeException | 還原仍成功，不往外拋
         * 4 | 呼叫順序                                 | -                                     | save → publish events → bindFilesToArticleSafely（DB commit 後才對外呼叫）
         * 5 | article 不存在                          | -（見上方 applyRestoreContent_articleNotFound_throws） | 完全不觸發 fileFacade（verifyNoInteractions 已含 fileFacade）
         * </pre>
         */
        @Nested
        @DisplayName("檔案綁定回填（修復：版本還原繞過檔案綁定掃描）")
        class RestoreFileBindingTests {

            @Test
            @DisplayName("正常：還原內容含 2 張圖 → 以還原後實際內容（saved.getContent()，非 data.content()）呼叫綁定一次")
            void applyRestoreContent_contentWithTwoImages_bindsFileUuidsFromPersistedContent() {
                UUID fileId1 = UUID.randomUUID();
                UUID fileId2 = UUID.randomUUID();
                String persistedContent = "![a](/api/v1/files/" + fileId1 + "/content)\n"
                        + "![b](/api/v1/files/" + fileId2 + "/content)";

                ArticleRestoreData data = new ArticleRestoreData(
                        "T", "s", "data.content() 不應被拿來掃描的內容", "sum", null,
                        "<p>c</p>", "[]", List.of());

                /**
                 * 模擬 repository.save() 回傳的 entity 內容與 data.content() 不同：
                 * 綁定必須掃描 save() 回傳（DB 實際存下）的內容，而非呼叫端傳入的 data.content()，
                 * 才能防禦「將來此路徑改成部分更新」時掃到不完整內容。
                 */
                when(articleRepository.save(any(Article.class))).thenAnswer(inv -> {
                    Article a = inv.getArgument(0);
                    a.setContent(persistedContent);
                    return a;
                });

                facade.applyRestoreContent(articleId, data);

                @SuppressWarnings("unchecked")
                ArgumentCaptor<List<UUID>> captor = ArgumentCaptor.forClass(List.class);
                verify(fileFacade).bindFilesToArticle(eq(articleUuid), captor.capture());
                assertThat(captor.getValue()).containsExactlyInAnyOrder(fileId1, fileId2);
            }

            @Test
            @DisplayName("邊界：還原內容無圖 → 以空清單呼叫綁定（解除該文章既有綁定，正確行為）")
            void applyRestoreContent_contentWithoutImages_bindsEmptyList() {
                ArticleRestoreData data = new ArticleRestoreData(
                        "T", "s", "純文字內容，沒有任何圖片連結", "sum", null,
                        "<p>c</p>", "[]", List.of());

                facade.applyRestoreContent(articleId, data);

                verify(fileFacade).bindFilesToArticle(articleUuid, List.of());
            }

            @Test
            @DisplayName("穩健性：檔案綁定失敗 → 還原仍成功，不往外拋")
            void applyRestoreContent_fileBindingThrows_restoreStillSucceeds() {
                ArticleRestoreData data = new ArticleRestoreData(
                        "T", "s", "![img](/api/v1/files/" + UUID.randomUUID() + "/content)",
                        "sum", null, "<p>c</p>", "[]", List.of());
                doThrow(new RuntimeException("file service 掛了"))
                        .when(fileFacade).bindFilesToArticle(any(), any());

                assertThatCode(() -> facade.applyRestoreContent(articleId, data))
                        .doesNotThrowAnyException();

                verify(articleRepository).save(existing);
                assertThat(existing.getContent()).isNotBlank();
            }

            @Test
            @DisplayName("順序：save → publish events → bindFilesToArticleSafely（DB commit 後才對外呼叫綁定）")
            void applyRestoreContent_invocationOrder_saveThenPublishThenBind() {
                /** publishUpdated 只在文章現況為 PUBLISHED 時發，故順序驗證需以 PUBLISHED 文章為前提 */
                existing.setStatus(ArticleStatus.PUBLISHED);
                ArticleRestoreData data = new ArticleRestoreData(
                        "T", "s", "c", "sum", null, "<p>c</p>", "[]", List.of());

                facade.applyRestoreContent(articleId, data);

                InOrder inOrder = inOrder(articleRepository, articleEventPublisher, fileFacade);
                inOrder.verify(articleRepository).save(existing);
                inOrder.verify(articleEventPublisher).publishContentChanged(existing, ArticleContentChangedEvent.Action.RESTORED);
                inOrder.verify(articleEventPublisher).publishUpdated(existing);
                inOrder.verify(fileFacade).bindFilesToArticle(eq(articleUuid), anyList());
            }
        }

        /**
         * SEC-02（HIGH）：版本還原路徑繞過文章狀態守衛。
         *
         * <p><strong>攻擊面（修復前）</strong>：{@code ArticleRestoreData} 帶著快照當時的 status，
         * {@code applyRestoreContent} 直接 {@code setStatus} 套回去。作者拿一份舊的 PUBLISHED 快照
         * 按「還原」，就能把被 ADMIN 駁回（REJECTED）或送審中（PENDING_REVIEW）的文章直接變回
         * PUBLISHED，繞過 {@code ArticleCommandSubService.VALID_TRANSITIONS} 與
         * 「PENDING_REVIEW → PUBLISHED 僅 ADMIN」的限制。</p>
         *
         * <p><strong>修法（Yuan 拍板）</strong>：還原只還原內容，狀態一律不動——不是把還原接進守衛，
         * 而是移除還原改動狀態的能力：{@code ArticleRestoreData} 連 status 欄位都拿掉，讓這條路徑
         * 在型別上就無法改狀態。</p>
         *
         * <p>下表以「文章現況」為變因（快照狀態已無從傳入，這正是修法的重點）：</p>
         * <pre>
         * # | 文章現況        | 預期
         * 1 | REJECTED       | 仍為 REJECTED（不得繞過審核）
         * 2 | PENDING_REVIEW | 仍為 PENDING_REVIEW
         * 3 | ARCHIVED       | 仍為 ARCHIVED
         * 4 | DRAFT          | 仍為 DRAFT，且不發 publishUpdated（非公開文章不得被重新索引）
         * 5 | PUBLISHED      | 仍為 PUBLISHED，且照發 publishUpdated（事件判準看文章現況）
         * </pre>
         */
        @Nested
        @DisplayName("SEC-02 狀態守衛：還原只還原內容，不得改動文章狀態")
        class RestoreNeverChangesStatus {

            @Test
            @DisplayName("REJECTED 文章還原 → 狀態仍為 REJECTED（不得繞過審核）")
            void applyRestoreContent_rejectedArticle_keepsRejectedStatus() {
                existing.setStatus(ArticleStatus.REJECTED);
                ArticleRestoreData data = new ArticleRestoreData(
                        "T", "s", "c", "sum", null, "<p>c</p>", "[]", List.of());

                facade.applyRestoreContent(articleId, data);

                assertThat(existing.getStatus())
                        .as("還原不得改動文章狀態，否則可繞過 PENDING_REVIEW → PUBLISHED 僅 ADMIN 的限制")
                        .isEqualTo(ArticleStatus.REJECTED);
                assertThat(existing.getContent())
                        .as("內容仍必須被還原")
                        .isEqualTo("c");
            }

            @Test
            @DisplayName("PENDING_REVIEW 文章還原 → 狀態仍為 PENDING_REVIEW")
            void applyRestoreContent_pendingReviewArticle_keepsPendingReviewStatus() {
                existing.setStatus(ArticleStatus.PENDING_REVIEW);
                ArticleRestoreData data = new ArticleRestoreData(
                        "T", "s", "c", "sum", null, "<p>c</p>", "[]", List.of());

                facade.applyRestoreContent(articleId, data);

                assertThat(existing.getStatus()).isEqualTo(ArticleStatus.PENDING_REVIEW);
            }

            @Test
            @DisplayName("ARCHIVED 文章還原 → 狀態仍為 ARCHIVED")
            void applyRestoreContent_archivedArticle_keepsArchivedStatus() {
                existing.setStatus(ArticleStatus.ARCHIVED);
                ArticleRestoreData data = new ArticleRestoreData(
                        "T", "s", "c", "sum", null, "<p>c</p>", "[]", List.of());

                facade.applyRestoreContent(articleId, data);

                assertThat(existing.getStatus()).isEqualTo(ArticleStatus.ARCHIVED);
            }

            @Test
            @DisplayName("DRAFT 文章還原 → 狀態仍為 DRAFT，且不發 publishUpdated（非公開文章不得被重新索引）")
            void applyRestoreContent_draftArticle_keepsDraftAndDoesNotPublishUpdated() {
                existing.setStatus(ArticleStatus.DRAFT);
                ArticleRestoreData data = new ArticleRestoreData(
                        "T", "s", "c", "sum", null, "<p>c</p>", "[]", List.of());

                facade.applyRestoreContent(articleId, data);

                assertThat(existing.getStatus()).isEqualTo(ArticleStatus.DRAFT);
                verify(articleEventPublisher).publishContentChanged(existing,
                        ArticleContentChangedEvent.Action.RESTORED);
                verify(articleEventPublisher, never()).publishUpdated(any());
            }

            @Test
            @DisplayName("PUBLISHED 文章還原 → 狀態仍為 PUBLISHED 且照發 publishUpdated")
            void applyRestoreContent_publishedArticle_keepsPublishedAndPublishesUpdated() {
                existing.setStatus(ArticleStatus.PUBLISHED);
                ArticleRestoreData data = new ArticleRestoreData(
                        "T", "s", "c", "sum", null, "<p>c</p>", "[]", List.of());

                facade.applyRestoreContent(articleId, data);

                assertThat(existing.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
                verify(articleEventPublisher).publishUpdated(existing);
            }
        }
    }
}
