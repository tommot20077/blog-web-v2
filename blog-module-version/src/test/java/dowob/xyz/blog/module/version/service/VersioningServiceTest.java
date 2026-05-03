package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent.Action;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.service.ArticleEventPublisher;
import dowob.xyz.blog.module.article.service.ArticleMarkdownRenderer;
import dowob.xyz.blog.module.version.exception.VersionErrorCode;
import dowob.xyz.blog.module.version.mapper.VersionMapper;
import dowob.xyz.blog.module.version.model.ArticleVersion;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import dowob.xyz.blog.module.version.repository.ArticleVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @Mock private ArticleRepository articleRepo;
    @Mock private ArticleVersionRepository versionRepo;
    @Mock private VersionMapper versionMapper;
    @Mock private PreferenceResolver preferenceResolver;
    @Mock private ArticleMarkdownRenderer markdownRenderer;
    @Mock private ArticleEventPublisher articleEventPublisher;
    @Mock private TagFacade tagFacade;

    @InjectMocks private VersioningService service;

    private final Long articleId = 100L;
    private final Long authorId = 1L;
    private final Long versionId = 200L;
    private final UUID versionUuid = UUID.randomUUID();

    private Article article(String title, String content) {
        Article a = new Article();
        a.setId(articleId);
        a.setUuid(UUID.randomUUID());
        a.setAuthorId(authorId);
        a.setTitle(title);
        a.setSlug("test-slug");
        a.setContent(content);
        a.setStatus(dowob.xyz.blog.common.api.enums.ArticleStatus.DRAFT);
        return a;
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
        when(articleRepo.findById(articleId)).thenReturn(Optional.of(article("Test", "Hello world")));
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
        when(articleRepo.findById(articleId)).thenReturn(Optional.empty());

        service.recordAutoSnapshot(articleId);

        verify(versionRepo, never()).save(any());
        verify(versionMapper, never()).retainAuto(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ─── recordManualSnapshot ─────────────

    @Test
    void recordManualSnapshot_savesWithNote_noRetention() {
        when(articleRepo.findById(articleId)).thenReturn(Optional.of(article("T", "C")));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordManualSnapshot(articleId, "milestone");

        ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("MANUAL");
        assertThat(captor.getValue().getNote()).isEqualTo("milestone");
        verify(versionMapper, never()).retainAuto(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    /**
     * Regression：snapshotFromArticle 必須抄入 article 當前的 tags，
     * 否則 restore 時 syncArticleTags 收到 List.of() 會把 article 所有 tag 清空。
     * 對應 PR #31 Copilot review #6。
     */
    @Test
    void recordManualSnapshot_copiesCurrentArticleTags() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        Article a = article("T", "C");
        when(articleRepo.findById(articleId)).thenReturn(Optional.of(a));
        when(tagFacade.findTagIdsByArticleUuid(a.getUuid())).thenReturn(List.of(t1, t2));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordManualSnapshot(articleId, "with-tags");

        ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo).save(captor.capture());
        assertThat(captor.getValue().getTags()).containsExactly(t1, t2);
    }

    @Test
    void recordManualSnapshot_articleNotFound_throwsV0106() {
        when(articleRepo.findById(articleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordManualSnapshot(articleId, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.ARTICLE_NOT_FOUND.getMessage());
    }

    // ─── freezePublished ─────────────

    @Test
    void freezePublished_writesPublishedAndClearsAutos() {
        when(articleRepo.findById(articleId)).thenReturn(Optional.of(article("T", "C")));
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
        when(articleRepo.findById(articleId)).thenReturn(Optional.of(article("T", "C")));
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
    void restore_stashesAndOverwritesArticle() {
        UUID tagUuid = UUID.randomUUID();
        Article current = article("Old", "old content");

        ArticleVersion target = existingVersion("AUTO");
        target.setTitle("New");
        target.setContent("new content");
        target.setStatus("PUBLISHED");
        target.setTags(List.of(tagUuid));

        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(target));
        when(articleRepo.findById(articleId)).thenReturn(Optional.of(current));
        lenient().when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(articleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(markdownRenderer.render("new content")).thenReturn("<p>new content</p>");

        service.restore(versionUuid, authorId, false);

        /* 1. stash 寫入（type=AUTO，title=Old）*/
        ArgumentCaptor<ArticleVersion> stashCap = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo, times(1)).save(stashCap.capture());
        assertThat(stashCap.getValue().getType()).isEqualTo("AUTO");
        assertThat(stashCap.getValue().getTitle()).isEqualTo("Old");

        /* 2. retention 執行 */
        verify(versionMapper).retainAuto(eq(articleId), org.mockito.ArgumentMatchers.anyInt());

        /* 3. article 寫回 + render */
        ArgumentCaptor<Article> articleCap = ArgumentCaptor.forClass(Article.class);
        verify(articleRepo).save(articleCap.capture());
        assertThat(articleCap.getValue().getTitle()).isEqualTo("New");
        assertThat(articleCap.getValue().getContent()).isEqualTo("new content");
        assertThat(articleCap.getValue().getContentHtml()).isEqualTo("<p>new content</p>");

        /* 4. tags 重綁（透過 TagFacade.syncArticleTags） */
        verify(tagFacade).syncArticleTags(eq(current.getUuid()), eq(List.of(tagUuid)));

        /* 5. events 發出 */
        verify(articleEventPublisher).publishContentChanged(any(), eq(Action.RESTORED));
        verify(articleEventPublisher).publishUpdated(any());
    }

    @Test
    void restore_byNonOwner_throwsV0102() {
        ArticleVersion target = existingVersion("AUTO");
        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.restore(versionUuid, 999L, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(VersionErrorCode.VERSION_ACCESS_DENIED.getMessage());

        verify(articleRepo, never()).findById(any());
    }

    /**
     * Regression：restore 一個非 PUBLISHED 的快照時不可發 publishUpdated，
     * 否則 search listener 會把 DRAFT 文章重新 index 為 PUBLISHED（status 寫死）。
     * 對應 PR #31 Copilot review #1。
     */
    @Test
    void restore_draftSnapshot_doesNotPublishUpdated() {
        Article current = article("Old", "old");

        ArticleVersion draftSnapshot = existingVersion("MANUAL");
        draftSnapshot.setStatus("DRAFT");

        when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(draftSnapshot));
        when(articleRepo.findById(articleId)).thenReturn(Optional.of(current));
        lenient().when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(articleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(markdownRenderer.render(any())).thenReturn("<p/>");

        service.restore(versionUuid, authorId, false);

        verify(articleEventPublisher).publishContentChanged(any(), eq(Action.RESTORED));
        verify(articleEventPublisher, never()).publishUpdated(any());
    }
}
