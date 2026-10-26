package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.ArticleRecommendMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.service.ArticleEventPublisher;
import dowob.xyz.blog.module.article.service.ArticleFileBinder;
import dowob.xyz.blog.module.article.service.ArticleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleFacadeImpl} 回傳 DTO 的 UUID 欄位測試（設計 §3.1；M6-a 起 authorUuid 直接取自 articles.author_uuid）。
 *
 * <p>驗證 facade 的每個 ArticleData / ArticleContentData / ArticleIndexData 產出點都填入
 * authorUuid（與 seriesUuid），且批次方法一次取得。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ArticleFacadeImpl 過渡期 UUID 欄位")
class ArticleFacadeUuidFieldsTest {

    @Mock private ArticleMapper articleMapper;
    @Mock private UserFacade userFacade;
    @Mock private ArticleRecommendMapper recommendMapper;
    @Mock private ArticleService articleService;
    @Mock private ArticleRepository articleRepository;
    @Mock private TagFacade tagFacade;
    @Mock private ArticleEventPublisher articleEventPublisher;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private FileFacade fileFacade;

    private ArticleFacadeImpl facade;

    private static final UUID AUTHOR_UUID = UUID.randomUUID();
    private static final UUID SERIES_UUID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ArticleDataAssembler assembler = new ArticleDataAssembler();
        facade = new ArticleFacadeImpl(
                articleMapper, userFacade, recommendMapper, articleService,
                articleRepository, tagFacade, articleEventPublisher, transactionTemplate,
                new ArticleFileBinder(fileFacade), assembler, null, null, null);
    }

    private static Article article(long id, UUID seriesUuid) {
        Article a = new Article();
        a.setId(id);
        a.setUuid(UUID.randomUUID());
        a.setAuthorUuid(AUTHOR_UUID);
        a.setStatus(ArticleStatus.PUBLISHED);
        a.setTitle("t");
        a.setSlug("s");
        a.setSeriesUuid(seriesUuid);
        a.setSeriesPosition(seriesUuid == null ? null : 1);
        return a;
    }

    @Test
    @DisplayName("findById → authorUuid 與 seriesUuid 非 null 且正確")
    void findById_fillsAuthorUuidAndSeriesUuid() {
        when(articleService.findById(1L)).thenReturn(Optional.of(article(1L, SERIES_UUID)));

        ArticleData data = facade.findById(1L).orElseThrow();

        assertThat(data.authorUuid()).isEqualTo(AUTHOR_UUID);
        assertThat(data.seriesUuid()).isEqualTo(SERIES_UUID);
    }

    @Test
    @DisplayName("findByUuid → authorUuid 非 null；無系列時 seriesUuid 為 null")
    void findByUuid_noSeries_seriesUuidNull() {
        Article a = article(1L, null);
        when(articleService.findByUuid(a.getUuid())).thenReturn(Optional.of(a));

        ArticleData data = facade.findByUuid(a.getUuid()).orElseThrow();

        assertThat(data.authorUuid()).isEqualTo(AUTHOR_UUID);
        assertThat(data.seriesUuid()).isNull();
    }

    @Test
    @DisplayName("findByIds → 作者 uuid 與系列 uuid 都直接取自文章（M6-b：不再經任何 facade 轉換）；不呼叫 UserFacade")
    void findByIds_readsUuidsStraightFromArticles() {
        when(articleService.findByIds(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(article(1L, SERIES_UUID), article(2L, SERIES_UUID), article(3L, null)));

        List<ArticleData> result = facade.findByIds(List.of(1L, 2L, 3L));

        assertThat(result).extracting(ArticleData::authorUuid).containsOnly(AUTHOR_UUID);
        assertThat(result).extracting(ArticleData::seriesUuid).containsExactly(SERIES_UUID, SERIES_UUID, null);
        verifyNoInteractions(userFacade);
    }

    @Test
    @DisplayName("findBySeriesUuidOrderByPosition → 每篇 authorUuid / seriesUuid 都被填值")
    void findBySeriesUuidOrderByPosition_fillsUuids() {
        when(articleService.findBySeriesUuidOrderByPosition(SERIES_UUID))
                .thenReturn(List.of(article(1L, SERIES_UUID), article(2L, SERIES_UUID)));

        List<ArticleData> result = facade.findBySeriesUuidOrderByPosition(SERIES_UUID);

        assertThat(result).extracting(ArticleData::authorUuid).containsOnly(AUTHOR_UUID);
        assertThat(result).extracting(ArticleData::seriesUuid).containsOnly(SERIES_UUID);
    }

    @Test
    @DisplayName("findContentById → ArticleContentData.authorUuid 非 null")
    void findContentById_fillsAuthorUuid() {
        when(articleService.findById(1L)).thenReturn(Optional.of(article(1L, null)));

        ArticleContentData data = facade.findContentById(1L).orElseThrow();
        assertThat(data.authorUuid()).isEqualTo(AUTHOR_UUID);
    }

    @Test
    @DisplayName("findAllPublishedForIndex → 每筆 authorUuid 取自文章，整批作者暱稱只呼叫 UserFacade 一次（不逐篇 N+1）")
    void findAllPublishedForIndex_fillsAuthorUuid_andResolvesNicknamesWithSingleCall() {
        when(articleMapper.findAllPublished()).thenReturn(List.of(article(1L, null), article(2L, null)));
        when(articleMapper.findTagsByArticleUuid(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(userFacade.getDisplayInfoByUuids(anyCollection())).thenReturn(
                Map.of(AUTHOR_UUID, new UserDisplayInfo(AUTHOR_UUID, "愛麗絲", null)));

        List<ArticleIndexData> result = facade.findAllPublishedForIndex();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ArticleIndexData::authorUuid).containsOnly(AUTHOR_UUID);
        assertThat(result).extracting(ArticleIndexData::authorNickname).containsOnly("愛麗絲");
        verify(userFacade, times(1)).getDisplayInfoByUuids(anyCollection());
    }

    @Test
    @DisplayName("findContentById → 不查任何使用者資料（authorUuid 直接取自文章）")
    void findContentById_doesNotQueryUsers() {
        when(articleService.findById(1L)).thenReturn(Optional.of(article(1L, null)));

        facade.findContentById(1L);

        verifyNoInteractions(userFacade);
    }
}
