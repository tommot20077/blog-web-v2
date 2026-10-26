package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.module.version.model.ArticleVersion;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import dowob.xyz.blog.module.version.repository.ArticleVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoSnapshotPolicyTest {

    @Mock private ArticleFacade articleFacade;
    @Mock private ArticleVersionRepository versionRepo;
    @Mock private PreferenceResolver preferenceResolver;
    @InjectMocks private AutoSnapshotPolicy policy;

    private final UUID articleUuid = UUID.randomUUID();
    private final UUID authorUuid = UUID.randomUUID();

    private ArticleContentData buildContentData(UUID articleUuid, UUID authorUuid, String content) {
        return new ArticleContentData(
            100L, articleUuid, 1L,
            "T", "s", content, "sum", null, "PUBLISHED", authorUuid
        );
    }

    private ArticleVersion lastAuto(String content, LocalDateTime createdAt) {
        ArticleVersion v = new ArticleVersion();
        v.setContent(content);
        v.setCreatedAt(createdAt);
        return v;
    }

    @Test
    void shouldSnapshot_disabled_returnsFalse() {
        when(articleFacade.findContentByUuid(articleUuid)).thenReturn(Optional.of(buildContentData(articleUuid, authorUuid, "aaa")));
        when(preferenceResolver.resolveForUser(authorUuid))
            .thenReturn(new AutoSnapshotConfig(false, 50, 60, 50));

        assertThat(policy.shouldSnapshot(articleUuid)).isFalse();
    }

    @Test
    void shouldSnapshot_firstTime_returnsTrue() {
        when(articleFacade.findContentByUuid(articleUuid)).thenReturn(Optional.of(buildContentData(articleUuid, authorUuid, "aaa")));
        when(preferenceResolver.resolveForUser(authorUuid))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.findLatestByArticleUuidAndType(articleUuid, "AUTO"))
            .thenReturn(Optional.empty());

        assertThat(policy.shouldSnapshot(articleUuid)).isTrue();
    }

    @Test
    void shouldSnapshot_intervalNotElapsed_returnsFalse() {
        when(articleFacade.findContentByUuid(articleUuid)).thenReturn(Optional.of(buildContentData(articleUuid, authorUuid, "a".repeat(200))));
        when(preferenceResolver.resolveForUser(authorUuid))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.findLatestByArticleUuidAndType(articleUuid, "AUTO"))
            .thenReturn(Optional.of(lastAuto("a".repeat(100), LocalDateTime.now().minusSeconds(30))));

        assertThat(policy.shouldSnapshot(articleUuid)).isFalse();
    }

    @Test
    void shouldSnapshot_diffNotEnough_returnsFalse() {
        when(articleFacade.findContentByUuid(articleUuid)).thenReturn(Optional.of(buildContentData(articleUuid, authorUuid, "a".repeat(110))));
        when(preferenceResolver.resolveForUser(authorUuid))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.findLatestByArticleUuidAndType(articleUuid, "AUTO"))
            .thenReturn(Optional.of(lastAuto("a".repeat(100), LocalDateTime.now().minusSeconds(120))));

        assertThat(policy.shouldSnapshot(articleUuid)).isFalse();
    }

    @Test
    void shouldSnapshot_diffCharsZero_skipsDiffCheck_returnsTrueWhenIntervalPassed() {
        when(articleFacade.findContentByUuid(articleUuid)).thenReturn(Optional.of(buildContentData(articleUuid, authorUuid, "a".repeat(100))));
        when(preferenceResolver.resolveForUser(authorUuid))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 0));
        when(versionRepo.findLatestByArticleUuidAndType(articleUuid, "AUTO"))
            .thenReturn(Optional.of(lastAuto("a".repeat(100), LocalDateTime.now().minusSeconds(120))));

        assertThat(policy.shouldSnapshot(articleUuid)).isTrue();
    }

    @Test
    void shouldSnapshot_intervalAndDiffPass_returnsTrue() {
        when(articleFacade.findContentByUuid(articleUuid)).thenReturn(Optional.of(buildContentData(articleUuid, authorUuid, "a".repeat(200))));
        when(preferenceResolver.resolveForUser(authorUuid))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.findLatestByArticleUuidAndType(articleUuid, "AUTO"))
            .thenReturn(Optional.of(lastAuto("a".repeat(100), LocalDateTime.now().minusSeconds(120))));

        assertThat(policy.shouldSnapshot(articleUuid)).isTrue();
    }

    @Test
    void shouldSnapshot_articleNotFound_returnsFalse() {
        when(articleFacade.findContentByUuid(articleUuid)).thenReturn(Optional.empty());

        assertThat(policy.shouldSnapshot(articleUuid)).isFalse();
    }
}
