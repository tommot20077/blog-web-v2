package dowob.xyz.blog.module.article.job;

import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob;
import dowob.xyz.blog.module.article.mapper.ArticleSeriesCleanupMapper;
import lombok.extern.slf4j.Slf4j;
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
 *   <li>與 consumer 一致：{@code series_position} 一併清空（系列已不存在）、{@code version} 遞增（讓過期的整筆 save
 *       撞樂觀鎖，不會把 ghost 歸屬寫回）、{@code updated_at} 不動（與改造前 FK 相同）；細節見
 *       {@link ArticleSeriesCleanupMapper}</li>
 *   <li>覆寫單次處理上限為 {@value #MAX_ORPHAN_SERIES_PER_RUN} 個孤兒系列，其餘門檻沿用框架預設</li>
 *   <li>清空前以 WARN 逐篇記錄受影響文章，供存在性查詢誤判時人工復原</li>
 * </ul>
 *
 * <p>鎖與游標 key 以 {@code module=article}、{@code table=articles} 識別；若日後 articles 還有別的欄位需要
 * 另一個校正排程，須使用不同的 {@code table()} 識別，避免共用同一把鎖而互相略過。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
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

    /** 單次執行最多處理的孤兒系列數（見 {@link #maxOrphansPerRun()}） */
    public static final int MAX_ORPHAN_SERIES_PER_RUN = 20;

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

    /**
     * 單次執行最多處理的孤兒系列數：覆寫為 {@value #MAX_ORPHAN_SERIES_PER_RUN}（框架預設 1000 太寬鬆）。
     *
     * <p>系列是低基數實體（部落格量級為數十），正常情況下一次只會有個位數的系列被刪除而事件遺失。
     * 若存在性查詢誤回空集合（例如 series 端異常），被參照的系列會全被判成孤兒；沒有這道上限，就會把這些系列底下
     * 所有文章的歸屬與位置一次清光且無法復原。超過上限即整次中止（ABORTED、零變更）。</p>
     *
     * @return 孤兒系列數上限（含）
     */
    @Override
    protected int maxOrphansPerRun() {
        return MAX_ORPHAN_SERIES_PER_RUN;
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
     * <p>清空前先以 WARN 逐篇記錄 articleUuid、原 series_uuid、原 series_position，供誤判時人工復原。
     * 清空本身是單一 {@code UPDATE ... IN (...)} 敘述，本身即原子，不需要 {@code TransactionTemplate}；
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
        List<UUID> seriesUuids = new ArrayList<>(missing);
        for (ArticleSeriesCleanupMapper.StaleMemberRow row : cleanupMapper.findMembersBySeriesUuids(seriesUuids)) {
            log.warn("系列孤兒校正：即將清空文章的系列歸屬（誤判時可依此還原） articleUuid={} seriesUuid={} seriesPosition={}",
                    row.articleUuid(), row.seriesUuid(), row.seriesPosition());
        }
        return cleanupMapper.clearSeriesByUuids(seriesUuids);
    }
}
