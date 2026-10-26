package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreSource;
import dowob.xyz.blog.module.version.exception.VersionErrorCode;
import dowob.xyz.blog.module.version.mapper.VersionMapper;
import dowob.xyz.blog.module.version.model.ArticleVersion;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import dowob.xyz.blog.module.version.model.dto.response.VersionSummaryResponse;
import dowob.xyz.blog.module.version.repository.ArticleVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link VersioningService} 單元測試（M3-b：全面改以 UUID 識別文章與使用者）。
 *
 * <p>還原改走 {@code ArticleFacade.applyRestoreContent(UUID, ArticleRestoreSource)}，
 * 由 article 模組自行渲染 HTML／TOC，因此本測試不再有渲染器與 TOC codec 的 mock；
 * 「HTML 與 TOC 出自同一次 render」改由 article 端的 {@code ArticleFacadeRestoreUuidTest} 守住。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VersioningService 單元測試")
class VersioningServiceTest {

    @Mock private ArticleFacade articleFacade;
    @Mock private ArticleVersionRepository versionRepo;
    @Mock private VersionMapper versionMapper;
    @Mock private PreferenceResolver preferenceResolver;
    @Mock private TagFacade tagFacade;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks private VersioningService service;

    private final UUID articleUuid = UUID.randomUUID();
    private final UUID authorUuid = UUID.randomUUID();
    private final UUID otherUserUuid = UUID.randomUUID();
    private final Long versionId = 200L;
    private final UUID versionUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Default stub: TagFacade.findTagIdsByArticleUuid 回空列表（對齊 interface contract）
        // - snapshotFromContent 內部會呼叫此 method（多個 test path 走過）
        // - 個別 test 需要特定 tags 時可以 override（例如 recordManualSnapshot_copiesCurrentArticleTags）
        lenient().when(tagFacade.findTagIdsByArticleUuid(any(UUID.class)))
            .thenReturn(List.of());

        /* 讓 mock 的 TransactionTemplate 直接執行 callback，使 restore 的 stash 段照常運行 */
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(org.mockito.Mockito.mock(TransactionStatus.class));
        });
    }

    /** 建立帶有固定 uuid 的 ArticleContentData stub（snapshot 流程用；Long 主鍵只是 article 的過渡欄位）。 */
    private ArticleContentData contentData(UUID aArticleUuid, UUID aAuthorUuid, String title, String content) {
        return new ArticleContentData(
            100L, aArticleUuid,
            title, "test-slug", content, "summary", null, "DRAFT", aAuthorUuid
        );
    }

    private ArticleVersion existingVersion(String type) {
        ArticleVersion v = new ArticleVersion();
        v.setId(versionId);
        v.setUuid(versionUuid);
        v.setArticleUuid(articleUuid);
        v.setAuthorUuid(authorUuid);
        v.setType(type);
        v.setTitle("Test");
        v.setContent("Hello");
        v.setStatus("DRAFT");
        return v;
    }

    /** 建立只帶 uuid 與作者 uuid 的 ArticleData（ownership 檢查用）。 */
    private ArticleData articleData(UUID aArticleUuid, UUID aAuthorUuid) {
        return new ArticleData(100L, aArticleUuid, "DRAFT", null, null, aAuthorUuid, null);
    }

    // ─── recordAutoSnapshot ─────────────

    @Test
    @DisplayName("recordAutoSnapshot：存檔時帶文章與作者 UUID，並以文章 UUID 執行滾動保留")
    void recordAutoSnapshot_savesAndAppliesRetention() {
        when(articleFacade.findContentByUuid(articleUuid))
            .thenReturn(Optional.of(contentData(articleUuid, authorUuid, "Test", "Hello world")));
        when(preferenceResolver.resolveForUser(authorUuid))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordAutoSnapshot(articleUuid);

        ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo).save(captor.capture());
        ArticleVersion saved = captor.getValue();
        assertThat(saved.getUuid()).isNotNull();
        assertThat(saved.getArticleUuid()).isEqualTo(articleUuid);
        assertThat(saved.getAuthorUuid()).isEqualTo(authorUuid);
        assertThat(saved.getType()).isEqualTo("AUTO");
        assertThat(saved.getTitle()).isEqualTo("Test");
        assertThat(saved.getContent()).isEqualTo("Hello world");

        verify(versionMapper).retainAuto(articleUuid, 50);
    }

    @Test
    @DisplayName("recordAutoSnapshot：文章不存在 → 靜默返回，不寫快照也不做保留")
    void recordAutoSnapshot_articleNotFound_doesNothing() {
        when(articleFacade.findContentByUuid(articleUuid)).thenReturn(Optional.empty());

        service.recordAutoSnapshot(articleUuid);

        verify(versionRepo, never()).save(any());
        verify(versionMapper, never()).retainAuto(any(), anyInt());
    }

    /**
     * V28 起 article_versions 沒有指向 articles 的 FK，原本以「FK 違反＝快照期間文章被刪」判斷的吞例外邏輯
     * 成為死碼並已移除：寫入時的資料完整性例外一律是真正的錯誤，不得被吞掉
     * （「快照期間文章被刪」留下的孤兒快照由刪除連動 consumer 與每日校正清除）。
     */
    @Test
    @DisplayName("recordAutoSnapshot：存檔拋 DataIntegrityViolationException → 原樣往外拋，不做保留")
    void recordAutoSnapshot_saveThrowsIntegrityViolation_propagates() {
        when(articleFacade.findContentByUuid(articleUuid))
            .thenReturn(Optional.of(contentData(articleUuid, authorUuid, "Test", "Hello world")));
        when(preferenceResolver.resolveForUser(authorUuid))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.save(any())).thenThrow(new DataIntegrityViolationException("boom"));

        assertThatThrownBy(() -> service.recordAutoSnapshot(articleUuid))
            .isInstanceOf(DataIntegrityViolationException.class);

        verify(versionMapper, never()).retainAuto(any(), anyInt());
    }

    @Test
    @DisplayName("recordAutoSnapshot：作者 UUID 缺失（資料異常）→ 明確拋 IllegalStateException，不寫快照")
    void recordAutoSnapshot_authorUuidMissing_throwsIllegalState() {
        when(articleFacade.findContentByUuid(articleUuid))
            .thenReturn(Optional.of(contentData(articleUuid, null, "Test", "Hello world")));

        assertThatThrownBy(() -> service.recordAutoSnapshot(articleUuid))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(articleUuid.toString());

        verify(versionRepo, never()).save(any());
    }

    // ─── recordManualSnapshot ─────────────

    @Test
    @DisplayName("recordManualSnapshot：寫入 MANUAL 與備註，不做保留")
    void recordManualSnapshot_savesWithNote_noRetention() {
        when(articleFacade.findContentByUuid(articleUuid))
            .thenReturn(Optional.of(contentData(articleUuid, authorUuid, "T", "C")));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordManualSnapshot(articleUuid, "milestone");

        ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("MANUAL");
        assertThat(captor.getValue().getNote()).isEqualTo("milestone");
        assertThat(captor.getValue().getArticleUuid()).isEqualTo(articleUuid);
        assertThat(captor.getValue().getAuthorUuid()).isEqualTo(authorUuid);
        verify(versionMapper, never()).retainAuto(any(), anyInt());
    }

    /**
     * Regression：snapshotFromContent 必須抄入 article 當前的 tags，
     * 否則 restore 時 syncArticleTags 收到 List.of() 會把 article 所有 tag 清空。
     * 對應 PR #31 Copilot review #6。
     */
    @Test
    @DisplayName("recordManualSnapshot：抄入文章當前的 tags（避免還原時清空標籤）")
    void recordManualSnapshot_copiesCurrentArticleTags() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        when(articleFacade.findContentByUuid(articleUuid))
            .thenReturn(Optional.of(contentData(articleUuid, authorUuid, "T", "C")));
        when(tagFacade.findTagIdsByArticleUuid(articleUuid)).thenReturn(List.of(t1, t2));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordManualSnapshot(articleUuid, "with-tags");

        ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo).save(captor.capture());
        assertThat(captor.getValue().getTags()).containsExactly(t1, t2);
    }

    @Test
    @DisplayName("recordManualSnapshot：文章不存在 → V0106")
    void recordManualSnapshot_articleNotFound_throwsV0106() {
        when(articleFacade.findContentByUuid(articleUuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordManualSnapshot(articleUuid, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.ARTICLE_NOT_FOUND.getMessage());
    }

    // ─── freezePublished ─────────────

    @Test
    @DisplayName("freezePublished：寫入 PUBLISHED v1 並以文章 UUID 清掉 AUTO")
    void freezePublished_writesPublishedAndClearsAutos() {
        when(articleFacade.findContentByUuid(articleUuid))
            .thenReturn(Optional.of(contentData(articleUuid, authorUuid, "T", "C")));
        when(versionMapper.countPublished(articleUuid)).thenReturn(0);
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.freezePublished(articleUuid);

        ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("PUBLISHED");
        assertThat(captor.getValue().getNote()).isEqualTo("Published v1");
        assertThat(captor.getValue().getArticleUuid()).isEqualTo(articleUuid);

        verify(versionMapper).deleteAutoByArticle(articleUuid);
    }

    @Test
    @DisplayName("freezePublished：第二次發布的備註為 Published v2")
    void freezePublished_secondPublishIncrementsVN() {
        when(articleFacade.findContentByUuid(articleUuid))
            .thenReturn(Optional.of(contentData(articleUuid, authorUuid, "T", "C")));
        when(versionMapper.countPublished(articleUuid)).thenReturn(1);
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.freezePublished(articleUuid);

        ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo).save(captor.capture());
        assertThat(captor.getValue().getNote()).isEqualTo("Published v2");
    }

    @Test
    @DisplayName("freezePublished：文章不存在 → 靜默返回")
    void freezePublished_articleNotFound_doesNothing() {
        when(articleFacade.findContentByUuid(articleUuid)).thenReturn(Optional.empty());

        service.freezePublished(articleUuid);

        verify(versionRepo, never()).save(any());
        verify(versionMapper, never()).deleteAutoByArticle(any());
    }

    // ─── promote ─────────────

    @Test
    @DisplayName("promote：作者本人把 AUTO 升級為 MANUAL")
    void promote_autoToManual_updatesType() {
        ArticleVersion v = existingVersion("AUTO");
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.promote(versionUuid, authorUuid, false);

        ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("promote：非 AUTO → V0104")
    void promote_nonAuto_throwsV0104() {
        ArticleVersion v = existingVersion("MANUAL");
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.promote(versionUuid, authorUuid, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.CANNOT_PROMOTE_NON_AUTO.getMessage());
    }

    @Test
    @DisplayName("promote：非作者且非 admin → V0102")
    void promote_byNonOwner_throwsV0102() {
        ArticleVersion v = existingVersion("AUTO");
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.promote(versionUuid, otherUserUuid, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.VERSION_ACCESS_DENIED.getMessage());
        verify(versionRepo, never()).save(any());
    }

    @Test
    @DisplayName("promote：admin 繞過作者檢查")
    void promote_byAdmin_bypassesOwnerCheck() {
        ArticleVersion v = existingVersion("AUTO");
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.promote(versionUuid, otherUserUuid, true);

        verify(versionRepo).save(v);
    }

    @Test
    @DisplayName("promote：快照的作者 UUID 為 null（資料異常）時，非 admin 一律拒絕（fail-closed）")
    void promote_versionWithoutAuthorUuid_deniesNonAdmin() {
        ArticleVersion v = existingVersion("AUTO");
        v.setAuthorUuid(null);
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.promote(versionUuid, authorUuid, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.VERSION_ACCESS_DENIED.getMessage());
    }

    // ─── delete ─────────────

    @Test
    @DisplayName("delete：作者本人刪除 MANUAL")
    void delete_manualVersion_deletes() {
        ArticleVersion v = existingVersion("MANUAL");
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));

        service.delete(versionUuid, authorUuid, false);

        verify(versionRepo).delete(v);
    }

    @Test
    @DisplayName("delete：PUBLISHED 不可刪 → V0103")
    void delete_publishedVersion_throwsV0103() {
        ArticleVersion v = existingVersion("PUBLISHED");
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.delete(versionUuid, authorUuid, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.CANNOT_DELETE_PUBLISHED.getMessage());
    }

    @Test
    @DisplayName("delete：非作者且非 admin → V0102，不刪除")
    void delete_byNonOwner_throwsV0102() {
        ArticleVersion v = existingVersion("MANUAL");
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.delete(versionUuid, otherUserUuid, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.VERSION_ACCESS_DENIED.getMessage());
        verify(versionRepo, never()).delete(any());
    }

    // ─── getDetail ─────────────

    @Test
    @DisplayName("getDetail：非作者且非 admin → V0102")
    void getDetail_byNonOwner_throwsV0102() {
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(existingVersion("MANUAL")));

        assertThatThrownBy(() -> service.getDetail(versionUuid, otherUserUuid, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.VERSION_ACCESS_DENIED.getMessage());
    }

    @Test
    @DisplayName("getDetail：作者本人取得詳情，對外只出 UUID（不帶 articleUuid／authorUuid 欄位）")
    void getDetail_byOwner_returnsDetail() {
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(existingVersion("MANUAL")));

        var detail = service.getDetail(versionUuid, authorUuid, false);

        assertThat(detail.getUuid()).isEqualTo(versionUuid);
        assertThat(detail.getContent()).isEqualTo("Hello");
    }

    // ─── restore ─────────────

    @Test
    @DisplayName("restore：先 stash（AUTO＋保留）再把 Markdown 原文交給 ArticleFacade UUID 版還原")
    void restore_stashesAndDelegatesAtomicRestore() {
        UUID tagUuid = UUID.randomUUID();
        ArticleContentData current = contentData(articleUuid, authorUuid, "Old", "old content");

        ArticleVersion target = existingVersion("AUTO");
        target.setTitle("New");
        target.setContent("new content");
        target.setStatus("PUBLISHED");
        target.setTags(List.of(tagUuid));

        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(target));
        when(articleFacade.findContentByUuid(articleUuid)).thenReturn(Optional.of(current));
        lenient().when(preferenceResolver.resolveForUser(authorUuid))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.restore(versionUuid, authorUuid, false);

        /* 1. stash 寫入（type=AUTO，title=Old）*/
        ArgumentCaptor<ArticleVersion> stashCap = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo, times(1)).save(stashCap.capture());
        assertThat(stashCap.getValue().getType()).isEqualTo("AUTO");
        assertThat(stashCap.getValue().getTitle()).isEqualTo("Old");
        assertThat(stashCap.getValue().getArticleUuid()).isEqualTo(articleUuid);

        /* 2. retention 執行 */
        verify(versionMapper).retainAuto(eq(articleUuid), anyInt());

        /* 3. atomic restore 委派給 ArticleFacade（UUID 版，只交 Markdown 原文，不帶 HTML／TOC） */
        ArgumentCaptor<ArticleRestoreSource> restoreCap = ArgumentCaptor.forClass(ArticleRestoreSource.class);
        verify(articleFacade).applyRestoreContent(eq(articleUuid), restoreCap.capture());
        ArticleRestoreSource rs = restoreCap.getValue();
        assertThat(rs.title()).isEqualTo("New");
        assertThat(rs.slug()).isEqualTo(target.getSlug());
        assertThat(rs.content()).isEqualTo("new content");
        assertThat(rs.tags()).containsExactly(tagUuid);
    }

    @Test
    @DisplayName("restore：stash 交易先完成，再呼叫 applyRestoreContent；後者不在 stash 交易作用域內")
    void restore_applyRestoreContent_runsAfterStashTransactionCompleted() {
        ArticleVersion target = existingVersion("MANUAL");
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(target));
        when(articleFacade.findContentByUuid(articleUuid))
            .thenReturn(Optional.of(contentData(articleUuid, authorUuid, "Old", "old")));
        lenient().when(preferenceResolver.resolveForUser(authorUuid))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AtomicBoolean insideStashTransaction = new AtomicBoolean(false);
        AtomicBoolean insideTxAtRestore = new AtomicBoolean(true);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            insideStashTransaction.set(true);
            try {
                return callback.doInTransaction(org.mockito.Mockito.mock(TransactionStatus.class));
            } finally {
                insideStashTransaction.set(false);
            }
        }).when(transactionTemplate).execute(any());
        doAnswer(invocation -> {
            insideTxAtRestore.set(insideStashTransaction.get());
            return null;
        }).when(articleFacade).applyRestoreContent(eq(articleUuid), any(ArticleRestoreSource.class));

        service.restore(versionUuid, authorUuid, false);

        verify(articleFacade).applyRestoreContent(eq(articleUuid), any(ArticleRestoreSource.class));
        assertThat(insideTxAtRestore.get())
            .as("applyRestoreContent 須在 stash 交易 commit 之後執行，事件才會在還原自己的 commit 後發送")
            .isFalse();
        InOrder order = inOrder(versionRepo, versionMapper, articleFacade);
        order.verify(versionRepo).save(any());
        order.verify(versionMapper).retainAuto(eq(articleUuid), anyInt());
        order.verify(articleFacade).applyRestoreContent(eq(articleUuid), any(ArticleRestoreSource.class));
    }

    @Test
    @DisplayName("restore：非作者且非 admin → V0102，不 stash、不還原")
    void restore_byNonOwner_throwsV0102() {
        ArticleVersion target = existingVersion("AUTO");
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.restore(versionUuid, otherUserUuid, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.VERSION_ACCESS_DENIED.getMessage());

        verify(articleFacade, never()).findContentByUuid(any());
        verify(articleFacade, never()).applyRestoreContent(any(UUID.class), any(ArticleRestoreSource.class));
    }

    @Test
    @DisplayName("restore：文章已不存在 → V0106，不寫 stash、不還原")
    void restore_articleMissing_throwsV0106() {
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(existingVersion("MANUAL")));
        when(articleFacade.findContentByUuid(articleUuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restore(versionUuid, authorUuid, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.ARTICLE_NOT_FOUND.getMessage());

        verify(versionRepo, never()).save(any());
        verify(articleFacade, never()).applyRestoreContent(any(UUID.class), any(ArticleRestoreSource.class));
    }

    @Test
    @DisplayName("restore：快照沒有 tags（null）→ 以空清單交給 facade，不帶 null")
    void restore_nullTags_passesEmptyList() {
        ArticleVersion target = existingVersion("MANUAL");
        target.setTags(null);
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(target));
        when(articleFacade.findContentByUuid(articleUuid))
            .thenReturn(Optional.of(contentData(articleUuid, authorUuid, "Old", "old")));
        lenient().when(preferenceResolver.resolveForUser(authorUuid))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.restore(versionUuid, authorUuid, false);

        ArgumentCaptor<ArticleRestoreSource> cap = ArgumentCaptor.forClass(ArticleRestoreSource.class);
        verify(articleFacade).applyRestoreContent(eq(articleUuid), cap.capture());
        assertThat(cap.getValue().tags()).isEmpty();
    }

    /**
     * SEC-02（HIGH）契約層防線：還原資料不得攜帶文章狀態。
     *
     * <p>行為層的斷言（還原後文章狀態不變）在 {@code ArticleFacadeImplTest} 內；
     * 這裡守的是<b>結構</b>——只要有人把 status 加回 {@link ArticleRestoreData}／{@link ArticleRestoreSource}，
     * 還原就重新具備改狀態的能力，本測試立刻紅，不必等到行為測試被一起改掉。</p>
     */
    @Test
    @DisplayName("SEC-02：ArticleRestoreData／ArticleRestoreSource 都不含 status 欄位")
    void restoreDtos_haveNoStatusComponent() {
        assertThat(ArticleRestoreData.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .as("還原只還原內容：帶 status 會讓還原成為繞過 VALID_TRANSITIONS 的狀態轉換路徑（SEC-02）")
            .doesNotContain("status");
        assertThat(ArticleRestoreSource.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .doesNotContain("status");
    }

    // ─── listByArticle ─────────────

    @Test
    @DisplayName("listByArticle：作者本人，以文章 UUID 查列表與總數")
    void listByArticle_byOwner_queriesMapperByArticleUuid() {
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(articleData(articleUuid, authorUuid)));
        List<VersionSummaryResponse> rows = List.of(new VersionSummaryResponse());
        when(versionMapper.listSummaries(articleUuid, "AUTO", 20, 20)).thenReturn(rows);
        when(versionMapper.countSummaries(articleUuid, "AUTO")).thenReturn(21L);

        PageResult<VersionSummaryResponse> result =
            service.listByArticle(articleUuid, "AUTO", 2, 20, authorUuid, false);

        assertThat(result.getTotal()).isEqualTo(21L);
        assertThat(result.getRecords()).isEqualTo(rows);
    }

    @Test
    @DisplayName("listByArticle：非作者且非 admin → V0102，不查列表")
    void listByArticle_byNonOwner_throwsV0102() {
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(articleData(articleUuid, authorUuid)));

        assertThatThrownBy(() -> service.listByArticle(articleUuid, null, 1, 20, otherUserUuid, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.VERSION_ACCESS_DENIED.getMessage());
        verifyNoInteractions(versionMapper);
    }

    @Test
    @DisplayName("listByArticle：文章不存在 → V0106")
    void listByArticle_articleNotFound_throwsV0106() {
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listByArticle(articleUuid, null, 1, 20, authorUuid, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.ARTICLE_NOT_FOUND.getMessage());
    }

    // ─── assertArticleOwnerOrAdmin ─────────────

    @Test
    @DisplayName("assertArticleOwnerOrAdmin：作者本人通過")
    void assertArticleOwnerOrAdmin_owner_passes() {
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(articleData(articleUuid, authorUuid)));

        service.assertArticleOwnerOrAdmin(articleUuid, authorUuid, false);
    }

    @Test
    @DisplayName("assertArticleOwnerOrAdmin：admin 即使不是作者也通過")
    void assertArticleOwnerOrAdmin_admin_passes() {
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(articleData(articleUuid, authorUuid)));

        service.assertArticleOwnerOrAdmin(articleUuid, otherUserUuid, true);
    }

    @Test
    @DisplayName("assertArticleOwnerOrAdmin：非作者且非 admin → V0102")
    void assertArticleOwnerOrAdmin_nonOwner_throwsV0102() {
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(articleData(articleUuid, authorUuid)));

        assertThatThrownBy(() -> service.assertArticleOwnerOrAdmin(articleUuid, otherUserUuid, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.VERSION_ACCESS_DENIED.getMessage());
    }

    @Test
    @DisplayName("assertArticleOwnerOrAdmin：文章不存在 → V0106")
    void assertArticleOwnerOrAdmin_articleNotFound_throwsV0106() {
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assertArticleOwnerOrAdmin(articleUuid, authorUuid, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.ARTICLE_NOT_FOUND.getMessage());
    }

    // ─── assertVersionBelongsToArticle ─────────────

    @Test
    @DisplayName("assertVersionBelongsToArticle：版本屬於該文章 → 通過")
    void assertVersionBelongsToArticle_matching_passes() {
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(existingVersion("MANUAL")));
        when(articleFacade.existsByUuid(articleUuid)).thenReturn(true);

        service.assertVersionBelongsToArticle(articleUuid, versionUuid);
    }

    @Test
    @DisplayName("assertVersionBelongsToArticle：版本不存在 → V0101")
    void assertVersionBelongsToArticle_versionMissing_throwsV0101() {
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assertVersionBelongsToArticle(articleUuid, versionUuid))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.VERSION_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("assertVersionBelongsToArticle：路徑上的文章不存在 → V0106（優先於不對應）")
    void assertVersionBelongsToArticle_articleMissing_throwsV0106() {
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(existingVersion("MANUAL")));
        UUID otherArticle = UUID.randomUUID();
        when(articleFacade.existsByUuid(otherArticle)).thenReturn(false);

        assertThatThrownBy(() -> service.assertVersionBelongsToArticle(otherArticle, versionUuid))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.ARTICLE_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("assertVersionBelongsToArticle：文章存在但版本屬於另一篇 → V0108")
    void assertVersionBelongsToArticle_mismatch_throwsV0108() {
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(existingVersion("MANUAL")));
        UUID otherArticle = UUID.randomUUID();
        when(articleFacade.existsByUuid(otherArticle)).thenReturn(true);

        assertThatThrownBy(() -> service.assertVersionBelongsToArticle(otherArticle, versionUuid))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.VERSION_ARTICLE_MISMATCH.getMessage());
    }
}
