package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.module.version.mapper.VersionMapper;
import dowob.xyz.blog.module.version.model.LatestSnapshotRef;
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
 * <p>PERF-21：上次快照先以 {@link VersionMapper#findLatestRef} 只取主鍵與建立時間檢查間隔，通過後才以
 * {@link VersionMapper#findContentLength} 取長度，不再把整份 Markdown 撈回應用程式，也不對每個事件做全文掃描；判斷通過時把已查好的文章內容與設定以 {@link AutoSnapshotPlan} 交給
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
     * 判斷是否該寫 AUTO 快照；該寫時一併回傳判斷當下查到的文章內容與生效設定。
     *
     * <p>字元差距以 Java {@code String.length()}（UTF-16 code unit）計：目前內容在記憶體中直接取長度，
     * 上次快照的長度由資料庫以相同單位算出（見 {@code VersionMapper#findContentLength}），兩邊口徑一致。</p>
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
        Optional<LatestSnapshotRef> lastOpt = versionMapper.findLatestRef(articleUuid, TYPE_AUTO);
        if (lastOpt.isEmpty()) return Optional.of(plan);

        LatestSnapshotRef last = lastOpt.get();
        Duration sinceLast = Duration.between(last.createdAt(), LocalDateTime.now());
        if (sinceLast.getSeconds() < cfg.intervalSeconds()) return Optional.empty();

        /* 時間間隔通過才計算上次快照的內容長度：連續存檔時大多數事件在上一行就結束，不做全文掃描。
         * diffChars=0（純時間觸發）時完全不需要長度。 */
        if (cfg.diffChars() > 0) {
            Optional<Integer> lastLen = versionMapper.findContentLength(last.id());
            if (lastLen.isPresent()) {
                int currentLen = article.content() != null ? article.content().length() : 0;
                if (Math.abs(currentLen - lastLen.get()) < cfg.diffChars()) return Optional.empty();
            }
        }

        return Optional.of(plan);
    }
}
