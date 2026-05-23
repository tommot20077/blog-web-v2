package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreData;
import dowob.xyz.blog.module.article.service.ArticleMarkdownRenderer;
import dowob.xyz.blog.module.version.exception.VersionErrorCode;
import dowob.xyz.blog.module.version.mapper.VersionMapper;
import dowob.xyz.blog.module.version.model.ArticleVersion;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import dowob.xyz.blog.module.version.repository.ArticleVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VersioningServiceTest {

    @Mock private ArticleFacade articleFacade;
    @Mock private ArticleVersionRepository versionRepo;
    @Mock private VersionMapper versionMapper;
    @Mock private PreferenceResolver preferenceResolver;
    @Mock private ArticleMarkdownRenderer markdownRenderer;
    @Mock private TagFacade tagFacade;

    @InjectMocks private VersioningService service;

    private final Long articleId = 100L;
    private final Long authorId = 1L;
    private final Long versionId = 200L;
    private final UUID versionUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Default stub: TagFacade.findTagIdsByArticleUuid 回空列表（對齊 interface contract）
        // - snapshotFromContent 內部會呼叫此 method（多個 test path 走過）
        // - 個別 test 需要特定 tags 時可以 override（例如 recordManualSnapshot_copiesCurrentArticleTags）
        lenient().when(tagFacade.findTagIdsByArticleUuid(any(UUID.class)))
            .thenReturn(java.util.List.of());
    }

    /** 建立帶有固定 uuid 的 ArticleContentData stub（snapshot 流程用）。 */
    private ArticleContentData contentData(Long id, Long aAuthorId, String title, String content) {
        return new ArticleContentData(
            id, UUID.randomUUID(), aAuthorId,
            title, "test-slug", content, "summary", null, "DRAFT"
        );
    }

    private ArticleVersion existingVersion(String type) {
        ArticleVersion v = new ArticleVersion();
        v.setId(versionId);
        v.setUuid(versionUuid);
        v.setArticleId(articleId);
        v.setAuthorId(authorId);
        v.setType(type);
        v.setTitle("Test");
        v.setContent("Hello");
        v.setStatus("DRAFT");
        return v;
    }

    @Test
    void recordAutoSnapshot_savesAndAppliesRetention() {
        when(articleFacade.findContentById(articleId))
            .thenReturn(Optional.of(contentData(articleId, authorId, "Test", "Hello world")));
        when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordAutoSnapshot(articleId);

        ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo).save(captor.capture());
        ArticleVersion saved = captor.getValue();
        assertThat(saved.getUuid()).isNotNull();
        assertThat(saved.getArticleId()).isEqualTo(articleId);
        assertThat(saved.getAuthorId()).isEqualTo(authorId);
        assertThat(saved.getType()).isEqualTo("AUTO");
        assertThat(saved.getTitle()).isEqualTo("Test");
        assertThat(saved.getContent()).isEqualTo("Hello world");

        verify(versionMapper).retainAuto(articleId, 50);
    }

    @Test
    void recordAutoSnapshot_articleNotFound_doesNothing() {
        when(articleFacade.findContentById(articleId)).thenReturn(Optional.empty());

        service.recordAutoSnapshot(articleId);

        verify(versionRepo, never()).save(any());
        verify(versionMapper, never()).retainAuto(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void recordAutoSnapshot_articleDeletedBeforeSave_swallowsForeignKeyViolation() {
        when(articleFacade.findContentById(articleId))
            .thenReturn(Optional.of(contentData(articleId, authorId, "Test", "Hello world")));
        when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.save(any()))
            .thenThrow(new DataIntegrityViolationException(
                "insert or update on table \"article_versions\" violates foreign key constraint " +
                    "\"article_versions_article_id_fkey\""));

        service.recordAutoSnapshot(articleId);

        verify(versionRepo).save(any());
        verify(versionMapper, never()).retainAuto(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ─── recordManualSnapshot ─────────────

    @Test
    void recordManualSnapshot_savesWithNote_noRetention() {
        when(articleFacade.findContentById(articleId))
            .thenReturn(Optional.of(contentData(articleId, authorId, "T", "C")));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordManualSnapshot(articleId, "milestone");

        ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("MANUAL");
        assertThat(captor.getValue().getNote()).isEqualTo("milestone");
        verify(versionMapper, never()).retainAuto(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    /**
     * Regression：snapshotFromContent 必須抄入 article 當前的 tags，
     * 否則 restore 時 syncArticleTags 收到 List.of() 會把 article 所有 tag 清空。
     * 對應 PR #31 Copilot review #6。
     */
    @Test
    void recordManualSnapshot_copiesCurrentArticleTags() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        ArticleContentData cd = contentData(articleId, authorId, "T", "C");
        when(articleFacade.findContentById(articleId)).thenReturn(Optional.of(cd));
        when(tagFacade.findTagIdsByArticleUuid(cd.uuid())).thenReturn(List.of(t1, t2));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordManualSnapshot(articleId, "with-tags");

        ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo).save(captor.capture());
        assertThat(captor.getValue().getTags()).containsExactly(t1, t2);
    }

    @Test
    void recordManualSnapshot_articleNotFound_throwsV0106() {
        when(articleFacade.findContentById(articleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordManualSnapshot(articleId, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.ARTICLE_NOT_FOUND.getMessage());
    }

    // ─── freezePublished ─────────────

    @Test
    void freezePublished_writesPublishedAndClearsAutos() {
        when(articleFacade.findContentById(articleId))
            .thenReturn(Optional.of(contentData(articleId, authorId, "T", "C")));
        when(versionMapper.countPublished(articleId)).thenReturn(0);
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.freezePublished(articleId);

        ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("PUBLISHED");
        assertThat(captor.getValue().getNote()).isEqualTo("Published v1");

        verify(versionMapper).deleteAutoByArticle(articleId);
    }

    @Test
    void freezePublished_secondPublishIncrementsVN() {
        when(articleFacade.findContentById(articleId))
            .thenReturn(Optional.of(contentData(articleId, authorId, "T", "C")));
        when(versionMapper.countPublished(articleId)).thenReturn(1);
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.freezePublished(articleId);

        ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo).save(captor.capture());
        assertThat(captor.getValue().getNote()).isEqualTo("Published v2");
    }

    // ─── promote ─────────────

    @Test
    void promote_autoToManual_updatesType() {
        ArticleVersion v = existingVersion("AUTO");
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.promote(versionUuid, authorId, false);

        ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("MANUAL");
    }

    @Test
    void promote_nonAuto_throwsV0104() {
        ArticleVersion v = existingVersion("MANUAL");
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.promote(versionUuid, authorId, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.CANNOT_PROMOTE_NON_AUTO.getMessage());
    }

    @Test
    void promote_byNonOwner_throwsV0102() {
        ArticleVersion v = existingVersion("AUTO");
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.promote(versionUuid, 999L, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.VERSION_ACCESS_DENIED.getMessage());
    }

    // ─── delete ─────────────

    @Test
    void delete_manualVersion_deletes() {
        ArticleVersion v = existingVersion("MANUAL");
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));

        service.delete(versionUuid, authorId, false);

        verify(versionRepo).delete(v);
    }

    @Test
    void delete_publishedVersion_throwsV0103() {
        ArticleVersion v = existingVersion("PUBLISHED");
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.delete(versionUuid, authorId, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.CANNOT_DELETE_PUBLISHED.getMessage());
    }

    // ─── restore ─────────────

    @Test
    void restore_stashesAndDelegatesAtomicRestore() {
        UUID tagUuid = UUID.randomUUID();
        ArticleContentData current = contentData(articleId, authorId, "Old", "old content");

        ArticleVersion target = existingVersion("AUTO");
        target.setTitle("New");
        target.setContent("new content");
        target.setStatus("PUBLISHED");
        target.setTags(List.of(tagUuid));

        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(target));
        when(articleFacade.findContentById(articleId)).thenReturn(Optional.of(current));
        lenient().when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(markdownRenderer.render("new content")).thenReturn("<p>new content</p>");

        service.restore(versionUuid, authorId, false);

        /* 1. stash 寫入（type=AUTO，title=Old）*/
        ArgumentCaptor<ArticleVersion> stashCap = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo, times(1)).save(stashCap.capture());
        assertThat(stashCap.getValue().getType()).isEqualTo("AUTO");
        assertThat(stashCap.getValue().getTitle()).isEqualTo("Old");

        /* 2. retention 執行 */
        verify(versionMapper).retainAuto(eq(articleId), org.mockito.ArgumentMatchers.anyInt());

        /* 3. atomic restore 委派給 ArticleFacade */
        ArgumentCaptor<ArticleRestoreData> restoreCap =
            ArgumentCaptor.forClass(ArticleRestoreData.class);
        verify(articleFacade).applyRestoreContent(eq(articleId), restoreCap.capture());
        ArticleRestoreData rd = restoreCap.getValue();
        assertThat(rd.title()).isEqualTo("New");
        assertThat(rd.content()).isEqualTo("new content");
        assertThat(rd.contentHtml()).isEqualTo("<p>new content</p>");
        assertThat(rd.tags()).containsExactly(tagUuid);
    }

    @Test
    void restore_byNonOwner_throwsV0102() {
        ArticleVersion target = existingVersion("AUTO");
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.restore(versionUuid, 999L, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.VERSION_ACCESS_DENIED.getMessage());

        verify(articleFacade, never()).findContentById(any());
    }

    /**
     * Regression：restore 一個非 PUBLISHED 的快照時不可由 VersioningService 發 publishUpdated；
     * event 的條件判斷已移入 ArticleFacade.applyRestoreContent 內部。
     * VersioningService 層只需確認 applyRestoreContent 被呼叫，
     * event 條件由 ArticleFacadeImplTest 驗證。
     */
    @Test
    void restore_draftSnapshot_delegatesToFacadeApplyRestore() {
        ArticleContentData current = contentData(articleId, authorId, "Old", "old");

        ArticleVersion draftSnapshot = existingVersion("MANUAL");
        draftSnapshot.setStatus("DRAFT");

        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(draftSnapshot));
        when(articleFacade.findContentById(articleId)).thenReturn(Optional.of(current));
        lenient().when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(markdownRenderer.render(any())).thenReturn("<p/>");

        service.restore(versionUuid, authorId, false);

        ArgumentCaptor<ArticleRestoreData> rdCaptor = ArgumentCaptor.forClass(ArticleRestoreData.class);
        verify(articleFacade).applyRestoreContent(eq(articleId), rdCaptor.capture());
        assertThat(rdCaptor.getValue().status()).isEqualTo("DRAFT");
    }
}
