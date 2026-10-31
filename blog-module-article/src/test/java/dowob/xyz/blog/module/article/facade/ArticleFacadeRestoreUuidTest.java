package dowob.xyz.blog.module.article.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.event.ArticleContentChangedEvent;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreSource;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.ArticleRecommendMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.service.ArticleEventPublisher;
import dowob.xyz.blog.module.article.service.ArticleFileBinder;
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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
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
 * 4 | UUID 還原順序                          | save → sync tags → 事件 → 綁檔
 * 5 | 文章不存在                             | BusinessException A0201，無任何寫入／事件
 * 6 | 內容凍結狀態（3 種）                   | A0209，實體零改動
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
                new ArticleDataAssembler(),
                null, renderer, tocCodec);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        articleId = 100L;
        articleUuid = UUID.randomUUID();
        authorUuid = UUID.randomUUID();
        existing = draftArticle();
        when(articleRepository.findByUuid(articleUuid)).thenReturn(Optional.of(existing));
        when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Article draftArticle() {
        Article a = new Article();
        a.setId(articleId);
        a.setUuid(articleUuid);
        a.setAuthorUuid(authorUuid);
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
        assertThat(data.uuid()).isEqualTo(articleUuid);
        assertThat(data.authorUuid()).isEqualTo(authorUuid);
        assertThat(data.content()).isEqualTo("# Hello");
        assertThat(data.status()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("findContentByUuid：不分狀態（REJECTED 也回傳），status 以字串帶出")
    void findContentByUuid_rejectedArticle_stillReturned() {
        existing.setStatus(ArticleStatus.REJECTED);
        when(articleService.findByUuid(articleUuid)).thenReturn(Optional.of(existing));

        Optional<ArticleContentData> result = facade.findContentByUuid(articleUuid);

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo("REJECTED");
        assertThat(result.get().authorUuid()).isEqualTo(authorUuid);
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
    @DisplayName("UUID 還原：順序 save → syncArticleTags → publishContentChanged → 綁檔")
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
    @DisplayName("UUID 還原：文章不存在 → A0201；無寫入、無事件、無綁檔")
    void applyRestoreContentByUuid_articleNotFound_throws() {
        UUID unknown = UUID.randomUUID();
        when(articleRepository.findByUuid(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facade.applyRestoreContent(unknown, source(List.of())))
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

    // ─── 事件／綁檔必須在交易 commit 之後（Transaction+MQ 時序）───

    /**
     * 讓 mock 的 {@code transactionTemplate.execute} 在 callback 回傳「之後」才把 committed 設為 true，
     * 模擬真實交易的 commit 時點；若事件或綁檔被移進 callback 內，那一刻 committed 仍為 false。
     */
    private AtomicBoolean simulateCommitAfterCallback() {
        AtomicBoolean committed = new AtomicBoolean(false);
        org.mockito.Mockito.doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            Object result = callback.doInTransaction(mock(TransactionStatus.class));
            committed.set(true);
            return result;
        }).when(transactionTemplate).execute(any());
        org.mockito.Mockito.doAnswer(inv -> {
            assertThat(committed).as("publishContentChanged 必須在交易 commit 之後呼叫").isTrue();
            return null;
        }).when(articleEventPublisher).publishContentChanged(any(Article.class), any());
        org.mockito.Mockito.doAnswer(inv -> {
            assertThat(committed).as("publishUpdated 必須在交易 commit 之後呼叫").isTrue();
            return null;
        }).when(articleEventPublisher).publishUpdated(any(Article.class));
        org.mockito.Mockito.doAnswer(inv -> {
            assertThat(committed).as("檔案綁定必須在交易 commit 之後呼叫").isTrue();
            return null;
        }).when(fileFacade).bindFilesToArticle(any(), any());
        return committed;
    }

    @Test
    @DisplayName("UUID 還原：事件與綁檔在交易 commit 之後才發（不在 callback 內）")
    void applyRestoreContentByUuid_eventsAndBindingHappenAfterCommit() {
        simulateCommitAfterCallback();

        facade.applyRestoreContent(articleUuid, source(List.of()));

        verify(articleEventPublisher)
                .publishContentChanged(existing, ArticleContentChangedEvent.Action.RESTORED);
        verify(fileFacade).bindFilesToArticle(eq(articleUuid), anyList());
    }

    // ─── 入口 null 檢查 ───

    @Test
    @DisplayName("UUID 還原：articleUuid 為 null → NPE（訊息指明參數），不渲染、不寫入")
    void applyRestoreContentByUuid_nullArticleUuid_throwsNpeWithMessage() {
        assertThatNullPointerException()
                .isThrownBy(() -> facade.applyRestoreContent((UUID) null, source(List.of())))
                .withMessageContaining("articleUuid").withMessageContaining("不可為 null");

        verify(articleRepository, never()).save(any(Article.class));
        verifyNoInteractions(tagFacade, articleEventPublisher, fileFacade);
    }

    @Test
    @DisplayName("UUID 還原：source 為 null → NPE（訊息指明參數），不寫入")
    void applyRestoreContentByUuid_nullSource_throwsNpeWithMessage() {
        assertThatNullPointerException()
                .isThrownBy(() -> facade.applyRestoreContent(articleUuid, null))
                .withMessageContaining("source").withMessageContaining("不可為 null");

        verify(articleRepository, never()).save(any(Article.class));
        verifyNoInteractions(tagFacade, articleEventPublisher, fileFacade);
    }

    @Test
    @DisplayName("UUID 還原：source.content() 為 null → NPE（訊息指明 content），不寫入")
    void applyRestoreContentByUuid_nullContent_throwsNpeWithMessage() {
        ArticleRestoreSource noContent =
                new ArticleRestoreSource("T", "s", null, "sum", null, List.of());

        assertThatNullPointerException()
                .isThrownBy(() -> facade.applyRestoreContent(articleUuid, noContent))
                .withMessageContaining("source.content").withMessageContaining("不可為 null");

        verify(articleRepository, never()).save(any(Article.class));
        verifyNoInteractions(tagFacade, articleEventPublisher, fileFacade);
    }
}
