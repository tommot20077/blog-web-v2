package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.module.article.service.ArticleIdentityResolver;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ArticleLookupFacadeImpl 單元測試
 *
 * <p>
 * 驗證「最小依賴」文章查詢 Facade 的行為：直接查 {@link ArticleRepository}，
 * 將 {@link Article} entity 轉為跨模組 {@link ArticleData}。
 * </p>
 *
 * <p>
 * 這個 Bean 存在的唯一理由是打斷 article ⇄ file 模組的 Spring 循環依賴
 * （見 {@code ArticleLookupFacade} javadoc），因此測試特別驗證：
 * {@code articleUuid == null} 時不得碰 repository，直接回傳 empty。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleLookupFacadeImpl 單元測試")
class ArticleLookupFacadeImplTest {

    /** Mock：文章資料存取 */
    @Mock
    private ArticleRepository articleRepository;

    /** Mock：users reference data 查詢（assembler 用，只讀 mapper，不引入 service / facade 依賴） */
    @Mock
    private ArticleMapper articleMapper;

    /** Mock：延遲取得 SeriesFacade 的 provider（打斷 bean 循環） */
    @Mock
    private ObjectProvider<SeriesFacade> seriesFacadeProvider;

    /** Mock：series uuid 轉換 */
    @Mock
    private SeriesFacade seriesFacade;

    /** 受測物件 */
    private ArticleLookupFacadeImpl articleLookupFacadeImpl;

    /**
     * 組裝受測物件：以真實的 {@link ArticleDataAssembler} 包裝被 mock 的 mapper 與 provider，
     * 讓原有的欄位對應測試仍驗到真正的轉換邏輯。
     */
    @BeforeEach
    void setUp() {
        lenient().when(seriesFacadeProvider.getObject()).thenReturn(seriesFacade);
        lenient().when(seriesFacade.findUuidsByIds(anyCollection())).thenReturn(Map.of());
        articleLookupFacadeImpl = new ArticleLookupFacadeImpl(
                articleRepository, new ArticleDataAssembler(new ArticleIdentityResolver(articleMapper, seriesFacadeProvider)));
    }

    /**
     * 驗證：文章存在時應回傳完整對應的 ArticleData（含 authorId 與 status，
     * 這兩個欄位是 file 模組授權判斷的依據）。
     */
    @Test
    @DisplayName("findByUuid → 文章存在 → 應回傳含 authorId / status 的 ArticleData")
    void findByUuid_articleExists_shouldReturnArticleData() {
        UUID articleUuid = UUID.randomUUID();
        Article article = new Article();
        article.setId(42L);
        article.setUuid(articleUuid);
        article.setAuthorId(7L);
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setSeriesId(3L);
        article.setSeriesPosition(2);
        when(articleRepository.findByUuid(articleUuid)).thenReturn(Optional.of(article));

        Optional<ArticleData> result = articleLookupFacadeImpl.findByUuid(articleUuid);

        assertThat(result).isPresent();
        ArticleData data = result.get();
        assertThat(data.id()).isEqualTo(42L);
        assertThat(data.uuid()).isEqualTo(articleUuid);
        assertThat(data.authorId()).isEqualTo(7L);
        assertThat(data.status()).isEqualTo("PUBLISHED");
        assertThat(data.seriesId()).isEqualTo(3L);
        assertThat(data.seriesPosition()).isEqualTo(2);
    }

    /**
     * 驗證：status 為 null 時不得 NPE，status 欄位以 null 回傳。
     */
    @Test
    @DisplayName("findByUuid → 文章 status 為 null → status 欄位應為 null 且不拋錯")
    void findByUuid_nullStatus_shouldReturnNullStatus() {
        UUID articleUuid = UUID.randomUUID();
        Article article = new Article();
        article.setId(1L);
        article.setUuid(articleUuid);
        article.setAuthorId(1L);
        article.setStatus(null);
        when(articleRepository.findByUuid(articleUuid)).thenReturn(Optional.of(article));

        Optional<ArticleData> result = articleLookupFacadeImpl.findByUuid(articleUuid);

        assertThat(result).isPresent();
        assertThat(result.get().status()).isNull();
    }

    /**
     * 驗證：文章不存在時回傳 empty（呼叫端 file 模組據此 fail-safe 視為不公開）。
     */
    @Test
    @DisplayName("findByUuid → 文章不存在 → 應回傳 Optional.empty()")
    void findByUuid_articleNotFound_shouldReturnEmpty() {
        UUID articleUuid = UUID.randomUUID();
        when(articleRepository.findByUuid(articleUuid)).thenReturn(Optional.empty());

        Optional<ArticleData> result = articleLookupFacadeImpl.findByUuid(articleUuid);

        assertThat(result).isEmpty();
    }

    /**
     * 驗證：articleUuid 為 null 時直接回傳 empty，不查 repository。
     */
    @Test
    @DisplayName("findByUuid → articleUuid 為 null → 應回傳 empty 且不查 repository")
    void findByUuid_nullUuid_shouldReturnEmptyWithoutRepositoryCall() {
        Optional<ArticleData> result = articleLookupFacadeImpl.findByUuid(null);

        assertThat(result).isEmpty();
        verifyNoInteractions(articleRepository);
    }
    /**
     * 驗證（設計 §3.1）：回傳的 ArticleData 帶 authorUuid 與 seriesUuid。
     */
    @Test
    @DisplayName("findByUuid → 文章有作者與系列 → authorUuid 與 seriesUuid 非 null 且正確")
    void findByUuid_withAuthorAndSeries_shouldFillUuidFields() {
        UUID articleUuid = UUID.randomUUID();
        UUID authorUuid = UUID.randomUUID();
        UUID seriesUuid = UUID.randomUUID();
        Article article = new Article();
        article.setId(42L);
        article.setUuid(articleUuid);
        article.setAuthorId(7L);
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setSeriesId(3L);
        article.setSeriesPosition(2);
        when(articleRepository.findByUuid(articleUuid)).thenReturn(Optional.of(article));
        when(articleMapper.findUserUuidsByIds(anyCollection()))
                .thenReturn(List.of(new ArticleMapper.UserUuidRow(7L, authorUuid)));
        when(seriesFacade.findUuidsByIds(anyCollection())).thenReturn(Map.of(3L, seriesUuid));

        ArticleData data = articleLookupFacadeImpl.findByUuid(articleUuid).orElseThrow();

        assertThat(data.authorUuid()).isEqualTo(authorUuid);
        assertThat(data.seriesUuid()).isEqualTo(seriesUuid);
    }

    /**
     * 驗證：文章無系列時 seriesUuid 為 null，且不需要取得 SeriesFacade（避免不必要的 bean 取用）。
     */
    @Test
    @DisplayName("findByUuid → 文章無系列 → seriesUuid 為 null 且不取得 SeriesFacade")
    void findByUuid_noSeries_shouldLeaveSeriesUuidNull() {
        UUID articleUuid = UUID.randomUUID();
        Article article = new Article();
        article.setId(42L);
        article.setUuid(articleUuid);
        article.setAuthorId(7L);
        article.setStatus(ArticleStatus.DRAFT);
        when(articleRepository.findByUuid(articleUuid)).thenReturn(Optional.of(article));

        ArticleData data = articleLookupFacadeImpl.findByUuid(articleUuid).orElseThrow();

        assertThat(data.seriesUuid()).isNull();
        org.mockito.Mockito.verify(seriesFacadeProvider, org.mockito.Mockito.never()).getObject();
    }
}
