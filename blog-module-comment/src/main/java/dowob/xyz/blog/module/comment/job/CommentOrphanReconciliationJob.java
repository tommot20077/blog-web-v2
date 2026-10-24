package dowob.xyz.blog.module.comment.job;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob;
import dowob.xyz.blog.module.comment.mapper.CommentArticleCleanupMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 留言孤兒校正排程：每日掃描 {@code comments.article_uuid}，刪除指向「已不存在文章」的留言。
 *
 * <p>文章刪除時主要靠 {@code CommentArticleDeletedConsumer} 連動刪除留言；事件遺失（例如 DLQ 未補送）
 * 會留下孤兒留言，本排程是第二道保險。掃描、分批、中止門檻、分散式鎖與指標由
 * {@link OrphanReconciliationJob} 框架負責，這裡只提供表的識別與四個鉤子：</p>
 * <ul>
 *   <li>存在性委派 {@link ArticleFacade#filterExistingUuids}（<b>不分狀態</b>；草稿、待審等文章的留言不是孤兒）</li>
 *   <li>孤兒處理是「刪除該列」（留言的存在以文章為前提）；留言讚由 FK CASCADE 清除</li>
 *   <li>不覆寫中止門檻，沿用框架預設</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
public class CommentOrphanReconciliationJob extends OrphanReconciliationJob {

    /**
     * 排程時間：每日 03:17（伺服器時區），秒 分 時 日 月 週。
     *
     * <p>選在深夜低流量時段；刻意避開整點與半點（其他排程與外部工作多集中於此），
     * 並與既有的 fixedDelay 排程（閱讀進度 flush、瀏覽數 flush、trending 刷新）、
     * 各模組將新增的其他校正排程錯開分鐘數。</p>
     */
    public static final String CRON = "0 17 3 * * *";

    private final CommentArticleCleanupMapper cleanupMapper;
    private final ArticleFacade articleFacade;

    /**
     * 建立排程。
     *
     * <p>指標走全域 registry（{@code Metrics.globalRegistry}）：正式環境由 Spring Boot actuator 綁定，
     * comment 模組的測試應用程式沒有 actuator 也不需要補 {@code MeterRegistry} bean。</p>
     *
     * @param redis          Redis 模板（分散式鎖與游標）
     * @param cleanupMapper  留言清除 mapper
     * @param articleFacade  文章 facade（存在性查詢）
     */
    public CommentOrphanReconciliationJob(StringRedisTemplate redis,
                                          CommentArticleCleanupMapper cleanupMapper,
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
        return "comment";
    }

    @Override
    protected String table() {
        return "comments";
    }

    /**
     * 以 keyset 分頁取得 {@code comments.article_uuid} 中不重複的 UUID（排序由資料庫決定）。
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
     * 刪除指向已不存在文章的留言。
     *
     * <p>單一 {@code DELETE ... IN (...)} 敘述本身即原子，不需要 {@code TransactionTemplate}；
     * 框架保證單次傳入筆數不超過頁大小，不會超出參數上限。</p>
     *
     * @param missing 已確認不存在的文章 UUID
     * @return 刪除的留言筆數（不含由 CASCADE 清除的留言讚）
     */
    @Override
    protected int removeOrphans(Collection<UUID> missing) {
        if (missing.isEmpty()) {
            return 0;
        }
        return cleanupMapper.deleteByArticleUuids(new ArrayList<>(missing));
    }
}
