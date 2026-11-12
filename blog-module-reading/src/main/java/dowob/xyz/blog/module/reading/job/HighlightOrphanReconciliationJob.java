package dowob.xyz.blog.module.reading.job;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob;
import dowob.xyz.blog.module.reading.mapper.ReadingArticleCleanupMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 劃線孤兒校正排程：每日掃描 {@code user_highlights.article_uuid}，刪除指向「已不存在文章」的劃線。
 *
 * <p>文章刪除時主要靠 {@code ReadingArticleDeletedConsumer} 連動刪除劃線；事件遺失（例如 DLQ 未補送）
 * 會留下孤兒劃線，本排程是第二道保險。掃描、分批、中止門檻、分散式鎖與指標由
 * {@link OrphanReconciliationJob} 框架負責，這裡只提供表的識別與四個鉤子：</p>
 * <ul>
 *   <li>存在性委派 {@link ArticleFacade#filterExistingUuids}（<b>不分狀態</b>；草稿、待審等文章的劃線不是孤兒）</li>
 *   <li>孤兒處理是「刪除該列」（劃線的存在以文章為前提）；劃線沒有反正規化計數，不需修正其他表</li>
 *   <li>不覆寫中止門檻，沿用框架預設</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
public class HighlightOrphanReconciliationJob extends OrphanReconciliationJob {

    /**
     * 排程時間：每日 03:35（伺服器時區），秒 分 時 日 月 週。
     *
     * <p>選在深夜低流量時段；刻意避開整點與半點（其他排程與外部工作多集中於此），
     * 並與 comment 模組的校正排程（03:17）、同模組的按讚（03:23）與收藏（03:29）校正排程各錯開數分鐘——
     * 排程執行緒池為單執行緒（PERF-16），同時觸發只會排隊互相拖延。</p>
     */
    public static final String CRON = "0 35 3 * * *";

    private final ReadingArticleCleanupMapper cleanupMapper;
    private final ArticleFacade articleFacade;

    /**
     * 建立排程。
     *
     * <p>指標走全域 registry（{@code Metrics.globalRegistry}）：正式環境由 Spring Boot actuator 綁定，
     * 沒有 actuator 的測試應用程式也不需要補 {@code MeterRegistry} bean。</p>
     *
     * @param redis         Redis 模板（分散式鎖與游標）
     * @param cleanupMapper reading 文章刪除連動 mapper
     * @param articleFacade 文章 facade（存在性查詢）
     */
    public HighlightOrphanReconciliationJob(StringRedisTemplate redis,
                                              ReadingArticleCleanupMapper cleanupMapper,
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
        return "reading";
    }

    @Override
    protected String table() {
        return "user_highlights";
    }

    /**
     * 以 keyset 分頁取得 {@code user_highlights.article_uuid} 中不重複的 UUID（排序由資料庫決定）。
     *
     * @param afterExclusive 游標（不含）；null 表示從頭
     * @param limit          本頁筆數上限
     * @return 升冪的文章 UUID
     */
    @Override
    protected List<UUID> fetchReferencedUuids(UUID afterExclusive, int limit) {
        return cleanupMapper.findDistinctHighlightArticleUuidsAfter(afterExclusive, limit).stream()
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
     * 刪除指向已不存在文章的劃線。
     *
     * <p>單一 {@code DELETE ... IN (...)} 敘述本身即原子，不需要 {@code TransactionTemplate}；
     * 框架保證單次傳入筆數不超過頁大小，不會超出參數上限。</p>
     *
     * @param missing 已確認不存在的文章 UUID
     * @return 刪除的劃線筆數
     */
    @Override
    protected int removeOrphans(Collection<UUID> missing) {
        if (missing.isEmpty()) {
            return 0;
        }
        return cleanupMapper.deleteHighlightsByArticleUuids(new ArrayList<>(missing));
    }
}
