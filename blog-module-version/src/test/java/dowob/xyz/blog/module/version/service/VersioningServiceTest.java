package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    // 後續 task 會加 markdownRenderer / eventPublisher / userPrefRepo / userPrefMapper / articleMapper / tagFacade

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
}
