package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
}
