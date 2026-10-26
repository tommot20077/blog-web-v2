package dowob.xyz.blog.module.article.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.event.ArticleContentChangedEvent;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreSource;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.ArticleRecommendMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.service.ArticleEventPublisher;
import dowob.xyz.blog.module.article.service.ArticleFileBinder;
import dowob.xyz.blog.module.article.service.ArticleIdentityResolver;
import dowob.xyz.blog.module.article.service.ArticleMarkdownRenderer;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.article.service.ArticleTocCodec;
import dowob.xyz.blog.module.article.service.RenderResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M3-a：{@link ArticleFacadeImpl} 的 UUID 版還原 / 內容查詢單元測試。
 *
 * <p>涵蓋 {@code findContentByUuid} 與 {@code applyRestoreContent(UUID, ArticleRestoreSource)}。
 * 渲染器與 TOC 編解碼器使用<b>真實</b>實例（無外部依賴的純計算元件），才能斷言「傳給內部還原流程的
 * contentHtml／toc 等於對同一份 Markdown 呼叫 renderer／codec 的結果」；其餘協作者以 mock 取代。</p>
 *
 * <p><strong>場景表</strong></p>
 * <pre>
 * # | 情境                                  | 預期
 * 1 | findContentByUuid 存在                 | 回 ArticleContentData，authorUuid 有值
 * 2 | findContentByUuid 查無／null           | empty
 * 3 | UUID 還原                              | contentHtml／toc 等於 renderer／codec 對同一份 Markdown 的結果
 * 4 | UUID 還原 vs Long 還原                 | 同樣內容 → 實體欄位逐欄位相同；save → sync tags → 事件 → 綁檔順序相同
 * 5 | 文章不存在                             | 與 Long 版相同：BusinessException A0201，無任何寫入／事件
 * 6 | 內容凍結狀態（3 種）                   | 與 Long 版相同：A0209，實體零改動
 * 7 | 還原                                   | 不改 status（SEC-02）；tags null 視為空清單
 * </pre>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ArticleFacadeImpl M3-a：UUID 版還原與內容查詢")
class ArticleFacadeRestoreUuidTest {

    private static final String MARKDOWN = "# 標題\n\n前言\n\n## 第一節\n\n內容\n\n### 第一節之一\n\n細節\n\n## 第二節\n\n結尾";

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

    private final ArticleMarkdownRenderer renderer = new ArticleMarkdownRenderer();
    private final ArticleTocCodec tocCodec = new ArticleTocCodec(new ObjectMapper());

    private ArticleFacadeImpl facade;

    private Long articleId;
    private UUID articleUuid;
    private UUID authorUuid;
    private Article existing;

    @BeforeEach
    void setUp() {
        facade = new ArticleFacadeImpl(
                articleMapper, userFacade, recommendMapper, articleService,
                articleRepository, tagFacade, articleEventPublisher, transactionTemplate,
                new ArticleFileBinder(fileFacade),
                new ArticleDataAssembler(new ArticleIdentityResolver(articleMapper, seriesFacadeProvider)),
                null, renderer, tocCodec);
        when(seriesFacadeProvider.getObject()).thenReturn(seriesFacade);
        when(seriesFacade.findUuidsByIds(any())).thenReturn(java.util.Map.of());
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        articleId = 100L;
        articleUuid = UUID.randomUUID();
        authorUuid = UUID.randomUUID();
        existing = draftArticle();
        when(articleRepository.findById(articleId)).thenReturn(Optional.of(existing));
        when(articleRepository.findByUuid(articleUuid)).thenReturn(Optional.of(existing));
        when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));
        when(articleMapper.findUserUuidsByIds(anyCollection()))
                .thenReturn(List.of(new ArticleMapper.UserUuidRow(5L, authorUuid)));
    }

    private Article draftArticle() {
        Article a = new Article();
        a.setId(articleId);
        a.setUuid(articleUuid);
        a.setAuthorId(5L);
        a.setTitle("舊標題");
        a.setSlug("old-slug");
        a.setContent("舊內容");
        a.setSummary("舊摘要");
        a.setCoverImageUrl("https://cdn/old.jpg");
        a.setContentHtml("<p>舊</p>");
        a.setToc("[]");
        a.setStatus(ArticleStatus.DRAFT);
        return a;
    }

    private ArticleRestoreSource source(List<UUID> tags) {
        return new ArticleRestoreSource("新標題", "new-slug", MARKDOWN, "新摘要", "https://cdn/new.jpg", tags);
    }

    // ─── findContentByUuid ───

    @Test
    @DisplayName("findContentByUuid：文章存在 → 回傳完整內容，authorUuid 有值")
    void findContentByUuid_existing_returnsContentDataWithAuthorUuid() {
        existing.setContent("# Hello");
        when(articleService.findByUuid(articleUuid)).thenReturn(Optional.of(existing));

        Optional<ArticleContentData> result = facade.findContentByUuid(articleUuid);

        assertThat(result).isPresent();
        ArticleContentData data = result.get();
        assertThat(data.id()).isEqualTo(articleId);
        assertThat(data.uuid()).isEqualTo(articleUuid);
        assertThat(data.authorId()).isEqualTo(5L);
        assertThat(data.authorUuid()).isEqualTo(authorUuid);
        assertThat(data.content()).isEqualTo("# Hello");
        assertThat(data.status()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("findContentByUuid：結果與 findContentById 對同一篇文章相等（不分狀態）")
    void findContentByUuid_sameArticle_equalsFindContentById() {
        existing.setStatus(ArticleStatus.REJECTED);
        when(articleService.findByUuid(articleUuid)).thenReturn(Optional.of(existing));
        when(articleService.findById(articleId)).thenReturn(Optional.of(existing));

        assertThat(facade.findContentByUuid(articleUuid)).isEqualTo(facade.findContentById(articleId));
    }

    @Test
    @DisplayName("findContentByUuid：查無 → empty；null → empty 且不查 DB")
    void findContentByUuid_notFoundOrNull_returnsEmpty() {
        UUID unknown = UUID.randomUUID();
        when(articleService.findByUuid(unknown)).thenReturn(Optional.empty());

        assertThat(facade.findContentByUuid(unknown)).isEmpty();
        assertThat(facade.findContentByUuid(null)).isEmpty();
        verify(articleService, never()).findByUuid(null);
    }

    // ─── applyRestoreContent(UUID, ...) ───

    @Test
    @DisplayName("UUID 還原：contentHtml／toc 等於 renderer／codec 對同一份 Markdown 的結果")
    void applyRestoreContentByUuid_rendersHtmlAndTocFromSameRender() {
        RenderResult expected = renderer.render(MARKDOWN);
        assertThat(expected.toc()).as("測試素材必須含 h2/h3，toc 才有比對意義").isNotEmpty();

        facade.applyRestoreContent(articleUuid, source(List.of()));

        assertThat(existing.getContentHtml()).isEqualTo(expected.html());
        assertThat(existing.getToc()).isEqualTo(tocCodec.serialize(expected.toc()));
        assertThat(existing.getToc()).isNotEqualTo("[]");
        assertThat(existing.getTitle()).isEqualTo("新標題");
        assertThat(existing.getSlug()).isEqualTo("new-slug");
        assertThat(existing.getContent()).isEqualTo(MARKDOWN);
        assertThat(existing.getSummary()).isEqualTo("新摘要");
        assertThat(existing.getCoverImageUrl()).isEqualTo("https://cdn/new.jpg");
    }

    @Test
    @DisplayName("UUID 版與 Long 版：同樣內容還原後實體欄位逐欄位相同，tags／事件／綁檔呼叫相同")
    void applyRestoreContent_uuidAndLongVersions_produceIdenticalResult() {
        List<UUID> tags = List.of(UUID.randomUUID(), UUID.randomUUID());
        RenderResult rendered = renderer.render(MARKDOWN);
        ArticleRestoreData longData = new ArticleRestoreData("新標題", "new-slug", MARKDOWN, "新摘要",
                "https://cdn/new.jpg", rendered.html(), tocCodec.serialize(rendered.toc()), tags);

        Article viaLong = draftArticle();
        when(articleRepository.findById(articleId)).thenReturn(Optional.of(viaLong));
        facade.applyRestoreContent(articleId, longData);

        Article viaUuid = draftArticle();
        when(articleRepository.findByUuid(articleUuid)).thenReturn(Optional.of(viaUuid));
        facade.applyRestoreContent(articleUuid, source(tags));

        assertThat(viaUuid).usingRecursiveComparison().isEqualTo(viaLong);
        verify(tagFacade, org.mockito.Mockito.times(2)).syncArticleTags(articleUuid, tags);
        /* 兩個實體內容相同（Article 的 equals 為值相等），因此兩次還原共觸發 2 次相同的事件 */
        verify(articleEventPublisher, org.mockito.Mockito.times(2))
                .publishContentChanged(viaLong, ArticleContentChangedEvent.Action.RESTORED);
        verify(fileFacade, org.mockito.Mockito.times(2)).bindFilesToArticle(eq(articleUuid), anyList());
    }

    @Test
    @DisplayName("UUID 還原：順序 save → syncArticleTags → publishContentChanged → 綁檔（與 Long 版相同）")
    void applyRestoreContentByUuid_invocationOrder_saveThenSyncTagsThenPublishThenBind() {
        facade.applyRestoreContent(articleUuid, source(List.of(UUID.randomUUID())));

        InOrder inOrder = inOrder(articleRepository, tagFacade, articleEventPublisher, fileFacade);
        inOrder.verify(articleRepository).save(existing);
        inOrder.verify(tagFacade).syncArticleTags(eq(articleUuid), anyList());
        inOrder.verify(articleEventPublisher)
                .publishContentChanged(existing, ArticleContentChangedEvent.Action.RESTORED);
        inOrder.verify(fileFacade).bindFilesToArticle(eq(articleUuid), anyList());
    }

    @Test
    @DisplayName("UUID 還原：tags 為 null → 以空清單同步")
    void applyRestoreContentByUuid_tagsNull_syncWithEmptyList() {
        facade.applyRestoreContent(articleUuid, source(null));

        verify(tagFacade).syncArticleTags(articleUuid, List.of());
    }

    @Test
    @DisplayName("UUID 還原：不改動 article.status（SEC-02）")
    void applyRestoreContentByUuid_doesNotChangeStatus() {
        existing.setStatus(ArticleStatus.REJECTED);
        existing.setRejectReason("內部評語");

        facade.applyRestoreContent(articleUuid, source(List.of()));

        assertThat(existing.getStatus()).isEqualTo(ArticleStatus.REJECTED);
        assertThat(existing.getRejectReason()).isEqualTo("內部評語");
        verify(articleEventPublisher, never()).publishUpdated(any());
    }

    @Test
    @DisplayName("UUID 還原：文章不存在 → A0201，與 Long 版相同；無寫入、無事件、無綁檔")
    void applyRestoreContentByUuid_articleNotFound_throwsLikeLongVersion() {
        UUID unknown = UUID.randomUUID();
        when(articleRepository.findByUuid(unknown)).thenReturn(Optional.empty());
        when(articleRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facade.applyRestoreContent(unknown, source(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(t -> ((BusinessException) t).getCode())
                .isEqualTo("A0201");
        assertThatThrownBy(() -> facade.applyRestoreContent(999L,
                new ArticleRestoreData("T", "s", "c", "sum", null, "<p>c</p>", "[]", List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(t -> ((BusinessException) t).getCode())
                .isEqualTo("A0201");

        verify(articleRepository, never()).save(any(Article.class));
        verifyNoInteractions(tagFacade, articleEventPublisher, fileFacade);
    }

    @ParameterizedTest(name = "狀態 {0}")
    @EnumSource(value = ArticleStatus.class, names = {"PENDING_REVIEW", "PUBLISHED", "ARCHIVED"})
    @DisplayName("UUID 還原：內容凍結狀態 → A0209，實體零改動、無事件")
    void applyRestoreContentByUuid_frozenStatus_throwsAndLeavesEntityUntouched(ArticleStatus frozen) {
        existing.setStatus(frozen);

        assertThatThrownBy(() -> facade.applyRestoreContent(articleUuid, source(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(t -> ((BusinessException) t).getCode())
                .isEqualTo("A0209");

        assertThat(existing.getTitle()).isEqualTo("舊標題");
        assertThat(existing.getContentHtml()).isEqualTo("<p>舊</p>");
        verify(articleRepository, never()).save(any(Article.class));
        verifyNoInteractions(tagFacade, articleEventPublisher, fileFacade);
    }
}
