package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.module.version.mapper.VersionMapper;
import dowob.xyz.blog.module.version.model.LatestSnapshotMeta;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 自動快照觸發判斷（時間 + 字元差距雙門檻）。
 *
 * <p>PERF-21：上次快照只以 {@link VersionMapper#findLatestMeta} 取「建立時間＋內容長度」，不再把整份 Markdown
 * 撈回應用程式只為了比長度；判斷通過時把已查好的文章內容與設定以 {@link AutoSnapshotPlan} 交給
 * {@link VersioningService#recordAutoSnapshot(AutoSnapshotPlan)}，同一次事件不重查。</p>
 *
 * @author Yuan
 * @version 1.1
 */
@Component
@RequiredArgsConstructor
public class AutoSnapshotPolicy {

    public static final String TYPE_AUTO = "AUTO";

    private final ArticleFacade articleFacade;
    private final VersionMapper versionMapper;
    private final PreferenceResolver preferenceResolver;

    /**
     * 判斷這次內容變更是否該寫一筆 AUTO 快照（開關、時間間隔、字元差距三道門檻）。
     *
     * @param articleUuid 文章公開 UUID
     * @return 該寫快照為 true；文章不存在、功能關閉或未達門檻為 false
     */
    public boolean shouldSnapshot(UUID articleUuid) {
        return plan(articleUuid).isPresent();
    }

    /**
     * 判斷是否該寫 AUTO 快照；該寫時一併回傳判斷當下查到的文章內容與生效設定。
     *
     * <p>字元差距以 Java {@code String.length()}（UTF-16 code unit）計：目前內容在記憶體中直接取長度，
     * 上次快照的長度由資料庫以相同單位算出（見 {@code VersionMapper#findLatestMeta}），兩邊口徑一致。</p>
     *
     * @param articleUuid 文章公開 UUID
     * @return 該寫快照時為 {@link AutoSnapshotPlan}；文章不存在、功能關閉或未達門檻為 empty
     */
    public Optional<AutoSnapshotPlan> plan(UUID articleUuid) {
        ArticleContentData article = articleFacade.findContentByUuid(articleUuid).orElse(null);
        if (article == null) return Optional.empty();

        AutoSnapshotConfig cfg = preferenceResolver.resolveForUser(article.authorUuid());
        if (!cfg.enabled()) return Optional.empty();

        AutoSnapshotPlan plan = new AutoSnapshotPlan(article, cfg);
        Optional<LatestSnapshotMeta> lastOpt = versionMapper.findLatestMeta(articleUuid, TYPE_AUTO);
        if (lastOpt.isEmpty()) return Optional.of(plan);

        LatestSnapshotMeta last = lastOpt.get();
        Duration sinceLast = Duration.between(last.createdAt(), LocalDateTime.now());
        if (sinceLast.getSeconds() < cfg.intervalSeconds()) return Optional.empty();

        if (cfg.diffChars() > 0) {
            int currentLen = article.content() != null ? article.content().length() : 0;
            int diff = Math.abs(currentLen - last.contentLength());
            if (diff < cfg.diffChars()) return Optional.empty();
        }

        return Optional.of(plan);
    }
}
