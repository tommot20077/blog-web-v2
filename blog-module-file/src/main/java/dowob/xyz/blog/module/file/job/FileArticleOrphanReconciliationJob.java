package dowob.xyz.blog.module.file.job;

import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import dowob.xyz.blog.module.file.service.FileArticleCleanupService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 檔案孤兒校正排程（DATA-07 第二道保險）：每日掃描 {@code file_metadata.article_uuid}，
 * 對「已不存在的文章」套用與刪除事件相同的清理（{@link FileArticleCleanupService}）。
 *
 * <p>文章刪除時主要靠 {@code FileArticleDeletedConsumer} 連動清理；事件遺失（例如 DLQ 未補送）會讓檔案與 MinIO 物件
 * 永久洩漏，本排程補上。掃描、分批、中止門檻、分散式鎖與指標由 {@link OrphanReconciliationJob} 框架負責，
 * 這裡只提供表的識別與四個鉤子：</p>
 * <ul>
 *   <li>存在性委派 {@link ArticleLookupFacade#filterExistingUuids}（<b>不分狀態</b>；草稿、待審等文章的檔案不是孤兒；
 *       owner 查詢拋例外時整次 failed、零修改——fail-safe）</li>
 *   <li>孤兒處理是「依去留規則刪除檔案」而非無條件刪列：仍被作者其餘文章引用的檔案保留，頭像不動</li>
 *   <li>不覆寫中止門檻，沿用框架預設</li>
 * </ul>
 *
 * <p>不處理從未綁定任何文章的上傳檔（{@code article_uuid} 為 NULL，不在掃描範圍）：是否定期清理屬保留期限的產品決策。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
public class FileArticleOrphanReconciliationJob extends OrphanReconciliationJob {

    /**
     * 排程時間：每日 04:23（伺服器時區），秒 分 時 日 月 週。
     *
     * <p>與既有校正 job（03:17～03:59、tag 用量 04:11，皆為不同分鐘）錯開；清理含 MinIO 網路呼叫，
     * 不與其他校正搶同一時段。</p>
     */
    public static final String CRON = "0 23 4 * * *";

    private final FileMetadataRepository fileMetadataRepository;
    private final ArticleLookupFacade articleLookupFacade;
    private final FileArticleCleanupService cleanupService;

    /**
     * 建立排程。
     *
     * <p>指標走全域 registry（{@code Metrics.globalRegistry}），與其他校正 job 一致。</p>
     *
     * @param redis                  Redis 模板（分散式鎖與游標）
     * @param fileMetadataRepository 檔案 metadata 資料存取
     * @param articleLookupFacade    文章查詢 facade（存在性查詢）
     * @param cleanupService         文章刪除後的檔案清理服務
     */
    public FileArticleOrphanReconciliationJob(StringRedisTemplate redis,
                                              FileMetadataRepository fileMetadataRepository,
                                              ArticleLookupFacade articleLookupFacade,
                                              FileArticleCleanupService cleanupService) {
        super(redis);
        this.fileMetadataRepository = fileMetadataRepository;
        this.articleLookupFacade = articleLookupFacade;
        this.cleanupService = cleanupService;
    }

    /** 每日排程入口：委派框架的 {@link #run()}（結果與日誌由框架記錄，run 不拋例外）。 */
    @Scheduled(cron = CRON)
    void scheduledRun() {
        run();
    }

    @Override
    protected String module() {
        return "file";
    }

    @Override
    protected String table() {
        return "file_metadata";
    }

    /**
     * 以 keyset 分頁取得 {@code file_metadata.article_uuid} 中不重複的 UUID（排序由資料庫決定）。
     *
     * @param afterExclusive 游標（不含）；null 表示從頭
     * @param limit          本頁筆數上限
     * @return 升冪的文章 UUID
     */
    @Override
    protected List<UUID> fetchReferencedUuids(UUID afterExclusive, int limit) {
        return fileMetadataRepository.findDistinctArticleUuidsAfter(afterExclusive, limit);
    }

    /**
     * 委派 {@link ArticleLookupFacade#filterExistingUuids}：不分狀態，只要文章列還在就算存在。
     *
     * @param uuids 待確認的文章 UUID
     * @return 仍存在的文章 UUID
     */
    @Override
    protected Set<UUID> filterExisting(Collection<UUID> uuids) {
        return articleLookupFacade.filterExistingUuids(uuids);
    }

    /**
     * 對每個已不存在的文章清理其綁定檔案（去留規則與刪除事件路徑相同）。
     *
     * <p>清理失敗（例如 MinIO 掛了）時例外往外拋，由框架標記本次 failed、游標不推進；已清理的檔案不會再出現，
     * 隔天重跑只會處理剩下的。</p>
     *
     * @param missing 已確認不存在的文章 UUID
     * @return 實際刪除的檔案數
     */
    @Override
    protected int removeOrphans(Collection<UUID> missing) {
        int removed = 0;
        for (UUID articleUuid : missing) {
            removed += cleanupService.deleteFilesBoundToDeletedArticle(articleUuid);
        }
        return removed;
    }
}
