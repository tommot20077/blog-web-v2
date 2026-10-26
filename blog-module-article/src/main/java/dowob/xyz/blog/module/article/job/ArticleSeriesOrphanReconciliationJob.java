package dowob.xyz.blog.module.article.job;

import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob;
import dowob.xyz.blog.module.article.mapper.ArticleSeriesCleanupMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 文章系列歸屬孤兒校正排程：每日掃描 {@code articles.series_uuid}，把指向「已不存在系列」的文章歸屬清空。
 *
 * <p>系列刪除時主要靠 {@code ArticleSeriesDeletedConsumer} 清空成員文章的歸屬；事件遺失（例如發送失敗、
 * DLQ 未補送），或「加入系列」與「刪除系列」競態（V32 起沒有 FK 擋下對不存在系列的指派）都會留下
 * 指向已刪除系列的文章，本排程是第二道保險。掃描、分批、中止門檻、分散式鎖與指標由
 * {@link OrphanReconciliationJob} 框架負責，這裡只提供表的識別與四個鉤子：</p>
 * <ul>
 *   <li>存在性委派 {@link SeriesFacade#filterExistingUuids}（系列沒有狀態，存在即回傳）</li>
 *   <li><b>孤兒的處理是「把 {@code series_uuid}、{@code series_position} 設為 NULL」，絕不刪除文章</b>
 *       （系列只是文章的可選關聯，文章本身仍是有效資料；框架的 {@code removeOrphans} 明載兩種語意皆合法）</li>
 *   <li>與改造前 FK 的 {@code ON DELETE SET NULL} 相同，不遞增 {@code version}、不更新 {@code updated_at}</li>
 *   <li>不覆寫中止門檻，沿用框架預設</li>
 * </ul>
 *
 * <p>鎖與游標 key 以 {@code module=article}、{@code table=articles} 識別；若日後 articles 還有別的欄位需要
 * 另一個校正排程，須使用不同的 {@code table()} 識別，避免共用同一把鎖而互相略過。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
public class ArticleSeriesOrphanReconciliationJob extends OrphanReconciliationJob {

    /**
     * 排程時間：每日 03:53（伺服器時區），秒 分 時 日 月 週。
     *
     * <p>選在深夜低流量時段；刻意避開整點與半點（其他排程與外部工作多集中於此），
     * 並與 comment（03:17）、reading（03:23／29／35／41）、version（03:47）的校正排程各錯開數分鐘——
     * 排程執行緒池為單執行緒（PERF-16），同時觸發只會排隊互相拖延。</p>
     */
    public static final String CRON = "0 53 3 * * *";

    private final ArticleSeriesCleanupMapper cleanupMapper;
    private final SeriesFacade seriesFacade;

    /**
     * 建立排程。
     *
     * <p>指標走全域 registry（{@code Metrics.globalRegistry}）：正式環境由 Spring Boot actuator 綁定，
     * 沒有 actuator 的測試應用程式也不需要補 {@code MeterRegistry} bean。</p>
     *
     * @param redis         Redis 模板（分散式鎖與游標）
     * @param cleanupMapper article 系列歸屬清空 mapper
     * @param seriesFacade  系列 facade（存在性查詢）
     */
    public ArticleSeriesOrphanReconciliationJob(StringRedisTemplate redis,
                                                ArticleSeriesCleanupMapper cleanupMapper,
                                                SeriesFacade seriesFacade) {
        super(redis);
        this.cleanupMapper = cleanupMapper;
        this.seriesFacade = seriesFacade;
    }

    /** 每日排程入口：委派框架的 {@link #run()}（結果與日誌由框架記錄，run 不拋例外）。 */
    @Scheduled(cron = CRON)
    void scheduledRun() {
        run();
    }

    @Override
    protected String module() {
        return "article";
    }

    @Override
    protected String table() {
        return "articles";
    }

    /**
     * 以 keyset 分頁取得 {@code articles.series_uuid} 中不重複的 UUID（排序由資料庫決定）。
     *
     * @param afterExclusive 游標（不含）；null 表示從頭
     * @param limit          本頁筆數上限
     * @return 升冪的系列 UUID
     */
    @Override
    protected List<UUID> fetchReferencedUuids(UUID afterExclusive, int limit) {
        return cleanupMapper.findDistinctSeriesUuidsAfter(afterExclusive, limit).stream()
                .map(UUID::fromString)
                .toList();
    }

    /**
     * 委派 {@link SeriesFacade#filterExistingUuids}：系列沒有狀態，只要列還在就算存在。
     *
     * @param uuids 待確認的系列 UUID
     * @return 仍存在的系列 UUID
     */
    @Override
    protected Set<UUID> filterExisting(Collection<UUID> uuids) {
        return seriesFacade.filterExistingUuids(uuids);
    }

    /**
     * 把指向已不存在系列的文章歸屬設為 NULL（<b>不刪除文章</b>）。
     *
     * <p>單一 {@code UPDATE ... IN (...)} 敘述本身即原子，不需要 {@code TransactionTemplate}；
     * 框架保證單次傳入筆數不超過頁大小，不會超出參數上限。</p>
     *
     * @param missing 已確認不存在的系列 UUID
     * @return 被清空歸屬的文章筆數
     */
    @Override
    protected int removeOrphans(Collection<UUID> missing) {
        if (missing.isEmpty()) {
            return 0;
        }
        return cleanupMapper.clearSeriesByUuids(new ArrayList<>(missing));
    }
}
