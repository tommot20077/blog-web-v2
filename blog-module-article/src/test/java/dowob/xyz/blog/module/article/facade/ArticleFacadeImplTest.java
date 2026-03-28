package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleTrendingData;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.ArticleRecommendMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.ArticleSummaryRow;
import dowob.xyz.blog.module.article.model.ArticleTagRow;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

    private ArticleFacadeImpl facade;

    private static final UUID ARTICLE_UUID = UUID.randomUUID();
    private static final Long ARTICLE_ID = 1L;

    @BeforeEach
    void setUp() {
        facade = new ArticleFacadeImpl(articleMapper, userFacade, recommendMapper);
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
            when(recommendMapper.findByTagIds(List.of(1L), ARTICLE_UUID, 5))
                    .thenReturn(Collections.emptyList());

            List<ArticleSummaryInfo> result = facade.getArticlesByTagIds(List.of(1L), ARTICLE_UUID, 5);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("文章無對應標籤時 tagNames 回傳空列表")
        void whenNoTagsForArticle_tagNamesIsEmpty() {
            ArticleSummaryRow row = row(ARTICLE_ID, ARTICLE_UUID, "No Tags");

            when(recommendMapper.findByTagIds(List.of(1L), ARTICLE_UUID, 5))
                    .thenReturn(List.of(row));
            when(recommendMapper.findTagsByArticleIds(List.of(ARTICLE_ID)))
                    .thenReturn(Collections.emptyList());

            List<ArticleSummaryInfo> result = facade.getArticlesByTagIds(List.of(1L), ARTICLE_UUID, 5);

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
            tagRow.setArticleId(ARTICLE_ID);
            tagRow.setTagName("Spring");

            when(recommendMapper.findByUuids(List.of(ARTICLE_UUID)))
                    .thenReturn(List.of(row));
            when(recommendMapper.findTagsByArticleIds(List.of(ARTICLE_ID)))
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
            tagRow.setArticleId(ARTICLE_ID);
            tagRow.setTagName("Java");

            when(recommendMapper.findRecentPublished(ARTICLE_UUID, 5))
                    .thenReturn(List.of(row));
            when(recommendMapper.findTagsByArticleIds(List.of(ARTICLE_ID)))
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
}
