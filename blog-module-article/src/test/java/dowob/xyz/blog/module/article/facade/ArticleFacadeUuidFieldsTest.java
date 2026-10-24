package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.module.article.service.ArticleIdentityResolver;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.ArticleRecommendMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.service.ArticleEventPublisher;
import dowob.xyz.blog.module.article.service.ArticleFileBinder;
import dowob.xyz.blog.module.article.service.ArticleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleFacadeImpl} 回傳 DTO 的過渡期 UUID 欄位測試（設計 §3.1）。
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
    @Mock private ObjectProvider<SeriesFacade> seriesFacadeProvider;
    @Mock private SeriesFacade seriesFacade;

    private ArticleFacadeImpl facade;
    private WarnLogCapture logCapture;

    private static final UUID AUTHOR_UUID = UUID.randomUUID();
    private static final UUID SERIES_UUID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ArticleDataAssembler assembler = new ArticleDataAssembler(new ArticleIdentityResolver(articleMapper, seriesFacadeProvider));
        facade = new ArticleFacadeImpl(
                articleMapper, userFacade, recommendMapper, articleService,
                articleRepository, tagFacade, articleEventPublisher, transactionTemplate,
                new ArticleFileBinder(fileFacade), assembler);
        when(seriesFacadeProvider.getObject()).thenReturn(seriesFacade);
        when(articleMapper.findUserUuidsByIds(anyCollection()))
                .thenReturn(List.of(new ArticleMapper.UserUuidRow(7L, AUTHOR_UUID)));
        when(seriesFacade.findUuidsByIds(anyCollection())).thenReturn(Map.of(3L, SERIES_UUID));

        logCapture = new WarnLogCapture(ArticleDataAssembler.class);
    }

    @AfterEach
    void detachLogAppender() {
        logCapture.close();
    }

    private List<String> warnings() {
        return logCapture.warnings();
    }

    private static Article article(long id, Long seriesId) {
        Article a = new Article();
        a.setId(id);
        a.setUuid(UUID.randomUUID());
        a.setAuthorId(7L);
        a.setStatus(ArticleStatus.PUBLISHED);
        a.setTitle("t");
        a.setSlug("s");
        a.setSeriesId(seriesId);
        a.setSeriesPosition(seriesId == null ? null : 1);
        return a;
    }

    @Test
    @DisplayName("findById → authorUuid 與 seriesUuid 非 null 且正確")
    void findById_fillsAuthorUuidAndSeriesUuid() {
        when(articleService.findById(1L)).thenReturn(Optional.of(article(1L, 3L)));

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
        verify(seriesFacade, never()).findUuidsByIds(anyCollection());
    }

    @Test
    @DisplayName("findByIds → 整批只查一次作者 uuid 與一次系列 uuid（不逐筆），也不呼叫 UserFacade 逐筆查詢")
    void findByIds_resolvesBatchOnce() {
        when(articleService.findByIds(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(article(1L, 3L), article(2L, 3L), article(3L, null)));

        List<ArticleData> result = facade.findByIds(List.of(1L, 2L, 3L));

        assertThat(result).extracting(ArticleData::authorUuid).containsOnly(AUTHOR_UUID);
        assertThat(result).extracting(ArticleData::seriesUuid).containsExactly(SERIES_UUID, SERIES_UUID, null);
        verify(articleMapper, times(1)).findUserUuidsByIds(anyCollection());
        verify(seriesFacade, times(1)).findUuidsByIds(anyCollection());
        verify(userFacade, never()).getUserUuidById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("findBySeriesIdOrderByPosition → 每篇 authorUuid / seriesUuid 都被填值")
    void findBySeriesIdOrderByPosition_fillsUuids() {
        when(articleService.findBySeriesIdOrderByPosition(3L))
                .thenReturn(List.of(article(1L, 3L), article(2L, 3L)));

        List<ArticleData> result = facade.findBySeriesIdOrderByPosition(3L);

        assertThat(result).extracting(ArticleData::authorUuid).containsOnly(AUTHOR_UUID);
        assertThat(result).extracting(ArticleData::seriesUuid).containsOnly(SERIES_UUID);
        verify(articleMapper, times(1)).findUserUuidsByIds(anyCollection());
        verify(seriesFacade, times(1)).findUuidsByIds(anyCollection());
    }

    @Test
    @DisplayName("findContentById → ArticleContentData.authorUuid 非 null")
    void findContentById_fillsAuthorUuid() {
        when(articleService.findById(1L)).thenReturn(Optional.of(article(1L, null)));

        ArticleContentData data = facade.findContentById(1L).orElseThrow();

        assertThat(data.authorId()).isEqualTo(7L);
        assertThat(data.authorUuid()).isEqualTo(AUTHOR_UUID);
    }

    @Test
    @DisplayName("findAllPublishedForIndex → 每筆 authorUuid 非 null，且整批作者只查一次")
    void findAllPublishedForIndex_fillsAuthorUuidWithSingleBatch() {
        when(articleMapper.findAllPublished()).thenReturn(List.of(article(1L, null), article(2L, null)));
        when(articleMapper.findTagsByArticleUuid(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        List<ArticleIndexData> result = facade.findAllPublishedForIndex();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ArticleIndexData::authorUuid).containsOnly(AUTHOR_UUID);
        assertThat(result).extracting(ArticleIndexData::authorId).containsOnly(7L);
        verify(articleMapper, times(1)).findUserUuidsByIds(anyCollection());
    }

    @Test
    @DisplayName("findContentById 查不到作者 uuid（資料異常）→ authorUuid 為 null，並以 warn 記錄 article id 與 author id")
    void findContentById_authorUuidNotFound_logsWarn() {
        when(articleMapper.findUserUuidsByIds(anyCollection())).thenReturn(List.of());
        when(articleService.findById(21L)).thenReturn(Optional.of(article(21L, null)));

        ArticleContentData data = facade.findContentById(21L).orElseThrow();

        assertThat(data.authorUuid()).isNull();
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("21").contains("7");
    }

    @Test
    @DisplayName("findAllPublishedForIndex 查不到作者 uuid（資料異常）→ authorUuid 為 null，並以 warn 記錄")
    void findAllPublishedForIndex_authorUuidNotFound_logsWarn() {
        when(articleMapper.findUserUuidsByIds(anyCollection())).thenReturn(List.of());
        when(articleMapper.findAllPublished()).thenReturn(List.of(article(31L, null)));
        when(articleMapper.findTagsByArticleUuid(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        List<ArticleIndexData> result = facade.findAllPublishedForIndex();

        assertThat(result.get(0).authorUuid()).isNull();
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("31").contains("7");
    }

    @Test
    @DisplayName("作者 uuid 查得到 → findContentById 不記 warn")
    void findContentById_authorUuidResolved_noWarn() {
        when(articleService.findById(1L)).thenReturn(Optional.of(article(1L, null)));

        facade.findContentById(1L);

        assertThat(warnings()).isEmpty();
    }
}
