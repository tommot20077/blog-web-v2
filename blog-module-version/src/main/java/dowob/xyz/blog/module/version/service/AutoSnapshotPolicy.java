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
import java.util.UUID;

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

    /**
     * 判斷這次內容變更是否該寫一筆 AUTO 快照（開關、時間間隔、字元差距三道門檻）。
     *
     * @param articleUuid 文章公開 UUID
     * @return 該寫快照為 true；文章不存在、功能關閉或未達門檻為 false
     */
    public boolean shouldSnapshot(UUID articleUuid) {
        ArticleContentData article = articleFacade.findContentByUuid(articleUuid).orElse(null);
        if (article == null) return false;

        AutoSnapshotConfig cfg = preferenceResolver.resolveForUser(article.authorUuid());
        if (!cfg.enabled()) return false;

        Optional<ArticleVersion> lastOpt = versionRepo.findLatestByArticleUuidAndType(articleUuid, TYPE_AUTO);
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
