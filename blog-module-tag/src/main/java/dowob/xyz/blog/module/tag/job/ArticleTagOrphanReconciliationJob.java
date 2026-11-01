package dowob.xyz.blog.module.tag.job;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob;
import dowob.xyz.blog.module.tag.repository.ArticleTagRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 文章標籤關聯孤兒校正排程：每日掃描 {@code article_tags.article_id}，刪除指向「已不存在文章」的關聯列。
 *
 * <p>文章刪除時主要靠 {@code TagArticleDeletedConsumer} 連動刪除關聯；事件遺失（例如 DLQ 未補送）
 * 會留下孤兒關聯，本排程是第二道保險。掃描、分批、中止門檻、分散式鎖與指標由
 * {@link OrphanReconciliationJob} 框架負責，這裡只提供表的識別與四個鉤子：</p>
 * <ul>
 *   <li>存在性委派 {@link ArticleFacade#filterExistingUuids}（<b>不分狀態</b>；草稿、待審等文章的標籤關聯不是孤兒）</li>
 *   <li>孤兒處理是「刪除該列」（關聯的存在以文章為前提）；<b>只刪 {@code article_tags}，不動 {@code tags}</b>，
 *       也不調整 {@code tags.usage_count}——孤兒指向的文章已不存在、本來就不在「已發布文章數」內；
 *       使用計數由 {@code TagUsageReconciliationJob}（每日 04:11，排在本排程之後）全量重算</li>
 *   <li>不覆寫中止門檻，沿用框架預設</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
public class ArticleTagOrphanReconciliationJob extends OrphanReconciliationJob {

    /**
     * 排程時間：每日 03:59（伺服器時區），秒 分 時 日 月 週。
     *
     * <p>選在深夜低流量時段；刻意避開整點與半點，並與其他模組的校正排程
     * （03:17／23／29／35／41／47／53）各錯開數分鐘——排程執行緒池為單執行緒（PERF-16），
     * 同時觸發只會排隊互相拖延。</p>
     */
    public static final String CRON = "0 59 3 * * *";

    private final ArticleTagRepository articleTagRepository;
    private final ArticleFacade articleFacade;

    /**
     * 建立排程。
     *
     * <p>指標走全域 registry（{@code Metrics.globalRegistry}）：正式環境由 Spring Boot actuator 綁定，
     * 沒有 actuator 的測試應用程式也不需要補 {@code MeterRegistry} bean。</p>
     *
     * @param redis                Redis 模板（分散式鎖與游標）
     * @param articleTagRepository 文章-標籤關聯 Repository
     * @param articleFacade        文章 facade（存在性查詢）
     */
    public ArticleTagOrphanReconciliationJob(StringRedisTemplate redis,
                                             ArticleTagRepository articleTagRepository,
                                             ArticleFacade articleFacade) {
        super(redis);
        this.articleTagRepository = articleTagRepository;
        this.articleFacade = articleFacade;
    }

    /** 每日排程入口：委派框架的 {@link #run()}（結果與日誌由框架記錄，run 不拋例外）。 */
    @Scheduled(cron = CRON)
    void scheduledRun() {
        run();
    }

    @Override
    protected String module() {
        return "tag";
    }

    @Override
    protected String table() {
        return "article_tags";
    }

    /**
     * 以 keyset 分頁取得 {@code article_tags.article_id} 中不重複的 UUID（排序由資料庫決定）。
     *
     * @param afterExclusive 游標（不含）；null 表示從頭
     * @param limit          本頁筆數上限
     * @return 升冪的文章 UUID
     */
    @Override
    protected List<UUID> fetchReferencedUuids(UUID afterExclusive, int limit) {
        return articleTagRepository.findReferencedArticleIdsAfter(afterExclusive, limit);
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
     * 刪除指向已不存在文章的標籤關聯。
     *
     * <p>單一 {@code DELETE ... IN (...)} 敘述本身即原子，不需要 {@code TransactionTemplate}；
     * 框架保證單次傳入筆數不超過頁大小，不會超出參數上限。</p>
     *
     * @param missing 已確認不存在的文章 UUID
     * @return 刪除的關聯筆數
     */
    @Override
    protected int removeOrphans(Collection<UUID> missing) {
        if (missing.isEmpty()) {
            return 0;
        }
        return articleTagRepository.deleteByArticleIds(new ArrayList<>(missing));
    }
}
