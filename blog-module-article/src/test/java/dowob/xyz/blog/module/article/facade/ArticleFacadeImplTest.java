package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleTrendingData;
import dowob.xyz.blog.module.article.event.TagInfo;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.ArticleRecommendMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.ArticleSummaryRow;
import dowob.xyz.blog.module.article.model.ArticleTagRow;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        row.setUuid(uuid);
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
        void 文章不存在時回傳empty() {
            when(recommendMapper.findPublishedIdByUuid(ARTICLE_UUID)).thenReturn(null);

            Optional<ArticleBasicInfo> result = facade.getPublishedArticleBasicInfo(ARTICLE_UUID);

            assertThat(result).isEmpty();
            verify(recommendMapper, never()).findTagIdsByArticleId(anyLong());
        }

        @Test
        void 文章存在時回傳基本資訊含標籤() {
            when(recommendMapper.findPublishedIdByUuid(ARTICLE_UUID)).thenReturn(ARTICLE_ID);
            when(recommendMapper.findTagIdsByArticleId(ARTICLE_ID)).thenReturn(List.of(1L, 2L));

            Optional<ArticleBasicInfo> result = facade.getPublishedArticleBasicInfo(ARTICLE_UUID);

            assertThat(result).isPresent();
            assertThat(result.get().uuid()).isEqualTo(ARTICLE_UUID);
            assertThat(result.get().tagIds()).containsExactly(1L, 2L);
        }
    }

    @Nested
    class GetArticlesByTagIds {

        @Test
        void tagIds為空時不查詢直接回傳空列表() {
            List<ArticleSummaryInfo> result = facade.getArticlesByTagIds(
                    Collections.emptyList(), ARTICLE_UUID, 5);

            assertThat(result).isEmpty();
            verify(recommendMapper, never()).findByTagIds(anyList(), any(UUID.class), anyInt());
        }

        @Test
        void 正常查詢並組裝標籤名稱() {
            ArticleSummaryRow row = row(ARTICLE_ID, ARTICLE_UUID, "Test");
            ArticleTagRow tagRow = new ArticleTagRow();
            tagRow.setArticleId(ARTICLE_ID);
            tagRow.setTagName("Java");

            when(recommendMapper.findByTagIds(List.of(1L), ARTICLE_UUID, 5))
                    .thenReturn(List.of(row));
            when(recommendMapper.findTagsByArticleIds(List.of(ARTICLE_ID)))
                    .thenReturn(List.of(tagRow));

            List<ArticleSummaryInfo> result = facade.getArticlesByTagIds(List.of(1L), ARTICLE_UUID, 5);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).title()).isEqualTo("Test");
            assertThat(result.get(0).tagNames()).containsExactly("Java");
        }
    }

    @Nested
    class GetPublishedArticlesByUuids {

        @Test
        void uuids為空時不查詢直接回傳空列表() {
            List<ArticleSummaryInfo> result = facade.getPublishedArticlesByUuids(Collections.emptyList());

            assertThat(result).isEmpty();
            verify(recommendMapper, never()).findByUuids(anyList());
        }
    }

    @Nested
    class FindAllPublishedForIndex {

        @Test
        void 正確呼叫Mapper並組裝索引資料() {
            Article article = new Article();
            article.setId(ARTICLE_ID);
            article.setUuid(ARTICLE_UUID);
            article.setTitle("Test Article");
            article.setSlug("test-article");
            article.setSummary("summary");
            article.setContent("content");
            article.setAuthorId(10L);

            TagInfo tagInfo = new TagInfo(1L, "Java", "java");

            when(articleMapper.findAllPublished()).thenReturn(List.of(article));
            when(articleMapper.findTagsByArticleId(ARTICLE_ID)).thenReturn(List.of(tagInfo));
            when(userFacade.getUserUsernameById(10L)).thenReturn(Optional.of("user1"));
            when(userFacade.getUserNicknameById(10L)).thenReturn(Optional.of("User One"));

            List<ArticleIndexData> result = facade.findAllPublishedForIndex();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).articleUuid()).isEqualTo(ARTICLE_UUID);
            assertThat(result.get(0).tags()).hasSize(1);
            assertThat(result.get(0).tags().get(0).name()).isEqualTo("Java");
        }

        @Test
        void 無已發布文章時回傳空列表() {
            when(articleMapper.findAllPublished()).thenReturn(List.of());

            assertThat(facade.findAllPublishedForIndex()).isEmpty();
        }
    }

    @Nested
    class GetArticlesPublishedAfter {

        @Test
        void 正確呼叫Mapper並回傳結果() {
            LocalDateTime since = LocalDateTime.now().minusDays(7);
            ArticleTrendingData data = new ArticleTrendingData(ARTICLE_UUID, 100L, 10L, since);
            when(recommendMapper.findPublishedAfter(since)).thenReturn(List.of(data));

            List<ArticleTrendingData> result = facade.getArticlesPublishedAfter(since);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).uuid()).isEqualTo(ARTICLE_UUID);
        }
    }
}
