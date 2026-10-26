package dowob.xyz.blog.module.version.job;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob;
import dowob.xyz.blog.module.version.mapper.VersionArticleCleanupMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 版本快照孤兒校正排程：每日掃描 {@code article_versions.article_uuid}，刪除指向「已不存在文章」的快照。
 *
 * <p>文章刪除時主要靠 {@code VersionArticleDeletedConsumer} 連動刪除快照；事件遺失（例如 DLQ 未補送），
 * 或快照寫入與文章刪除競態（快照在文章刪除後才寫入，V28 起無 FK 擋下）都會留下孤兒快照，本排程是第二道保險。
 * 掃描、分批、中止門檻、分散式鎖與指標由 {@link OrphanReconciliationJob} 框架負責，這裡只提供表的識別與四個鉤子：</p>
 * <ul>
 *   <li>存在性委派 {@link ArticleFacade#filterExistingUuids}（<b>不分狀態</b>；草稿、待審等文章的快照不是孤兒）</li>
 *   <li>孤兒處理是「刪除該列」（快照的存在以文章為前提）；快照沒有反正規化計數，不需修正其他表</li>
 *   <li>不覆寫中止門檻，沿用框架預設</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
public class ArticleVersionOrphanReconciliationJob extends OrphanReconciliationJob {

    /**
     * 排程時間：每日 03:47（伺服器時區），秒 分 時 日 月 週。
     *
     * <p>選在深夜低流量時段；刻意避開整點與半點（其他排程與外部工作多集中於此），
     * 並與 comment（03:17）、reading（03:23／29／35／41）的校正排程各錯開數分鐘——
     * 排程執行緒池為單執行緒（PERF-16），同時觸發只會排隊互相拖延。</p>
     */
    public static final String CRON = "0 47 3 * * *";

    private final VersionArticleCleanupMapper cleanupMapper;
    private final ArticleFacade articleFacade;

    /**
     * 建立排程。
     *
     * <p>指標走全域 registry（{@code Metrics.globalRegistry}）：正式環境由 Spring Boot actuator 綁定，
     * 沒有 actuator 的測試應用程式也不需要補 {@code MeterRegistry} bean。</p>
     *
     * @param redis         Redis 模板（分散式鎖與游標）
     * @param cleanupMapper version 文章刪除連動 mapper
     * @param articleFacade 文章 facade（存在性查詢）
     */
    public ArticleVersionOrphanReconciliationJob(StringRedisTemplate redis,
                                                 VersionArticleCleanupMapper cleanupMapper,
                                                 ArticleFacade articleFacade) {
        super(redis);
        this.cleanupMapper = cleanupMapper;
        this.articleFacade = articleFacade;
    }

    /** 每日排程入口：委派框架的 {@link #run()}（結果與日誌由框架記錄，run 不拋例外）。 */
    @Scheduled(cron = CRON)
    void scheduledRun() {
        run();
    }

    @Override
    protected String module() {
        return "version";
    }

    @Override
    protected String table() {
        return "article_versions";
    }

    /**
     * 以 keyset 分頁取得 {@code article_versions.article_uuid} 中不重複的 UUID（排序由資料庫決定）。
     *
     * @param afterExclusive 游標（不含）；null 表示從頭
     * @param limit          本頁筆數上限
     * @return 升冪的文章 UUID
     */
    @Override
    protected List<UUID> fetchReferencedUuids(UUID afterExclusive, int limit) {
        return cleanupMapper.findDistinctArticleUuidsAfter(afterExclusive, limit).stream()
                .map(UUID::fromString)
                .toList();
    }

    /**
     * 委派 {@link ArticleFacade#filterExistingUuids}：不分狀態，只要文章列還在就算存在。
     *
     * @param uuids 待確認的文章 UUID
     * @return 仍存在的文章 UUID
     */
    @Override
    protected Set<UUID> filterExisting(Collection<UUID> uuids) {
        return articleFacade.filterExistingUuids(uuids);
    }

    /**
     * 刪除指向已不存在文章的版本快照。
     *
     * <p>單一 {@code DELETE ... IN (...)} 敘述本身即原子，不需要 {@code TransactionTemplate}；
     * 框架保證單次傳入筆數不超過頁大小，不會超出參數上限。</p>
     *
     * @param missing 已確認不存在的文章 UUID
     * @return 刪除的快照筆數
     */
    @Override
    protected int removeOrphans(Collection<UUID> missing) {
        if (missing.isEmpty()) {
            return 0;
        }
        return cleanupMapper.deleteByArticleUuids(new ArrayList<>(missing));
    }
}
