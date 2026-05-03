package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
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
import dowob.xyz.blog.module.article.service.ArticleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

    private ArticleFacadeImpl facade;

    private static final UUID ARTICLE_UUID = UUID.randomUUID();
    private static final Long ARTICLE_ID = 1L;

    @BeforeEach
    void setUp() {
        facade = new ArticleFacadeImpl(
            articleMapper, userFacade, recommendMapper, articleService,
            articleRepository, tagFacade, articleEventPublisher
        );
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
                "T", "s", "c", "sum", null, "DRAFT", "<p>c</p>", List.of()
            );

            assertThatThrownBy(() -> facade.applyRestoreContent(999L, data))
                .isInstanceOf(BusinessException.class)
                .extracting(t -> ((BusinessException) t).getCode())
                .isEqualTo("A0201");

            verify(articleRepository, never()).save(any(Article.class));
            verifyNoInteractions(tagFacade, articleEventPublisher);
        }

        @Test
        @DisplayName("PUBLISHED article 還原 → publishContentChanged(RESTORED) + publishUpdated 都發")
        void applyRestoreContent_publishedArticle_publishesBoth() {
            ArticleRestoreData data = new ArticleRestoreData(
                "New Title", "new-slug", "# New", "New summary", "https://cdn/new.jpg",
                "PUBLISHED", "<p>New</p>", List.of(UUID.randomUUID(), UUID.randomUUID())
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
                "T", "s", "c", "sum", null, "DRAFT", "<p>c</p>", List.of()
            );

            facade.applyRestoreContent(articleId, data);

            assertThat(existing.getStatus()).isEqualTo(ArticleStatus.DRAFT);
            verify(articleEventPublisher).publishContentChanged(existing, ArticleContentChangedEvent.Action.RESTORED);
            verify(articleEventPublisher, never()).publishUpdated(any());
        }

        @Test
        @DisplayName("status 為 null → 不更新 article.status")
        void applyRestoreContent_statusNull_doesNotChangeStatus() {
            ArticleStatus before = existing.getStatus();
            ArticleRestoreData data = new ArticleRestoreData(
                "T", "s", "c", "sum", null, null, "<p>c</p>", List.of()
            );

            facade.applyRestoreContent(articleId, data);

            assertThat(existing.getStatus()).isEqualTo(before);
        }

        @Test
        @DisplayName("tags 為 null → syncArticleTags 用空清單")
        void applyRestoreContent_tagsNull_syncWithEmptyList() {
            ArticleRestoreData data = new ArticleRestoreData(
                "T", "s", "c", "sum", null, "DRAFT", "<p>c</p>", null
            );

            facade.applyRestoreContent(articleId, data);

            verify(tagFacade).syncArticleTags(articleUuid, List.of());
        }

        @Test
        @DisplayName("invocation 順序：save → syncArticleTags → publishEvents")
        void applyRestoreContent_invocationOrder_saveThenSyncTagsThenPublish() {
            ArticleRestoreData data = new ArticleRestoreData(
                "T", "s", "c", "sum", null, "PUBLISHED", "<p>c</p>", List.of()
            );

            facade.applyRestoreContent(articleId, data);

            InOrder inOrder = inOrder(articleRepository, tagFacade, articleEventPublisher);
            inOrder.verify(articleRepository).save(existing);
            inOrder.verify(tagFacade).syncArticleTags(eq(articleUuid), anyList());
            inOrder.verify(articleEventPublisher).publishContentChanged(existing, ArticleContentChangedEvent.Action.RESTORED);
            inOrder.verify(articleEventPublisher).publishUpdated(existing);
        }
    }
}
