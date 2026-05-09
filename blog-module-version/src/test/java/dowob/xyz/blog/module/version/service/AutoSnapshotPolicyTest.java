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

    private final Long articleId = 100L;
    private final Long authorId = 1L;

    private ArticleContentData buildContentData(Long id, Long authorId, String content) {
        return new ArticleContentData(
            id, UUID.randomUUID(), authorId,
            "T", "s", content, "sum", null, "PUBLISHED"
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
        when(articleFacade.findContentById(articleId)).thenReturn(Optional.of(buildContentData(articleId, authorId, "aaa")));
        when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(false, 50, 60, 50));

        assertThat(policy.shouldSnapshot(articleId)).isFalse();
    }

    @Test
    void shouldSnapshot_firstTime_returnsTrue() {
        when(articleFacade.findContentById(articleId)).thenReturn(Optional.of(buildContentData(articleId, authorId, "aaa")));
        when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.findLatestByArticleAndType(articleId, "AUTO"))
            .thenReturn(Optional.empty());

        assertThat(policy.shouldSnapshot(articleId)).isTrue();
    }

    @Test
    void shouldSnapshot_intervalNotElapsed_returnsFalse() {
        when(articleFacade.findContentById(articleId)).thenReturn(Optional.of(buildContentData(articleId, authorId, "a".repeat(200))));
        when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.findLatestByArticleAndType(articleId, "AUTO"))
            .thenReturn(Optional.of(lastAuto("a".repeat(100), LocalDateTime.now().minusSeconds(30))));

        assertThat(policy.shouldSnapshot(articleId)).isFalse();
    }

    @Test
    void shouldSnapshot_diffNotEnough_returnsFalse() {
        when(articleFacade.findContentById(articleId)).thenReturn(Optional.of(buildContentData(articleId, authorId, "a".repeat(110))));
        when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.findLatestByArticleAndType(articleId, "AUTO"))
            .thenReturn(Optional.of(lastAuto("a".repeat(100), LocalDateTime.now().minusSeconds(120))));

        assertThat(policy.shouldSnapshot(articleId)).isFalse();
    }

    @Test
    void shouldSnapshot_diffCharsZero_skipsDiffCheck_returnsTrueWhenIntervalPassed() {
        when(articleFacade.findContentById(articleId)).thenReturn(Optional.of(buildContentData(articleId, authorId, "a".repeat(100))));
        when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 0));
        when(versionRepo.findLatestByArticleAndType(articleId, "AUTO"))
            .thenReturn(Optional.of(lastAuto("a".repeat(100), LocalDateTime.now().minusSeconds(120))));

        assertThat(policy.shouldSnapshot(articleId)).isTrue();
    }

    @Test
    void shouldSnapshot_intervalAndDiffPass_returnsTrue() {
        when(articleFacade.findContentById(articleId)).thenReturn(Optional.of(buildContentData(articleId, authorId, "a".repeat(200))));
        when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.findLatestByArticleAndType(articleId, "AUTO"))
            .thenReturn(Optional.of(lastAuto("a".repeat(100), LocalDateTime.now().minusSeconds(120))));

        assertThat(policy.shouldSnapshot(articleId)).isTrue();
    }
}
