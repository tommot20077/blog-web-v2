package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.module.version.model.ArticleVersion;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import dowob.xyz.blog.module.version.repository.ArticleVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 自動快照觸發判斷（時間 + 字元差距雙門檻）。
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class AutoSnapshotPolicy {

    public static final String TYPE_AUTO = "AUTO";

    private final ArticleFacade articleFacade;
    private final ArticleVersionRepository versionRepo;
    private final PreferenceResolver preferenceResolver;

    public boolean shouldSnapshot(Long articleId) {
        ArticleContentData article = articleFacade.findContentById(articleId).orElse(null);
        if (article == null) return false;

        AutoSnapshotConfig cfg = preferenceResolver.resolveForUser(article.authorId());
        if (!cfg.enabled()) return false;

        Optional<ArticleVersion> lastOpt = versionRepo.findLatestByArticleAndType(articleId, TYPE_AUTO);
        if (lastOpt.isEmpty()) return true;

        ArticleVersion last = lastOpt.get();
        Duration sinceLast = Duration.between(last.getCreatedAt(), LocalDateTime.now());
        if (sinceLast.getSeconds() < cfg.intervalSeconds()) return false;

        if (cfg.diffChars() > 0) {
            int currentLen = article.content() != null ? article.content().length() : 0;
            int lastLen = last.getContent() != null ? last.getContent().length() : 0;
            int diff = Math.abs(currentLen - lastLen);
            if (diff < cfg.diffChars()) return false;
        }

        return true;
    }
}
