package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 每日綁定校正（B1）與未綁定檔清理（D6）的業務邏輯；排程、分散式鎖與指標在 {@code FileBindingReconcileJob}。
 *
 * <h3>為什麼有這個流程</h3>
 * <p>「檔案 → 文章」綁定靠內容變更事件非同步回填（{@link ArticleFileBindingService}），事件遺失會讓內文引用的圖
 * 停在未綁定（讀者看到破圖）；而上傳後從未存進任何文章的檔案（編輯器上傳後放棄）則永遠占用儲存與配額。
 * 本服務先補綁定、再清理，兩者必須在同一個流程、依此順序，清理才不會把「只是綁定尚未回填」的圖當成無主檔刪掉。</p>
 *
 * <h3>補綁定（B1）：刻意不分文章狀態</h3>
 * <p>決策原文只掃描 PUBLISHED 文章；這裡擴大為<b>所有狀態</b>（{@link ArticleLookupFacade#findUuidsUpdatedSinceAnyStatus}），
 * 因為 D6 的清理不分狀態保護草稿——草稿內文引用的圖若沒被補綁定，仍會因為「未綁定」而進入清理候選。
 * （即使如此，清理仍有第二道安全網，見下。）</p>
 *
 * <h3>清理（D6）：四個條件同時成立才刪</h3>
 * <ol>
 *   <li>建立早於保留天數（預設 7 天）</li>
 *   <li>仍未綁定（{@code article_uuid IS NULL}）</li>
 *   <li>不是頭像（頭像生命週期跟帳號走）</li>
 *   <li><b>不被上傳者任何一篇文章（不分狀態）的內文或封面引用</b>
 *       （{@link ArticleLookupFacade#findFileUuidsReferencedByAuthorArticles}，與文章刪除連動清理共用的安全網；
 *       封面圖不會被內文綁定回填，只能靠這道網保護）</li>
 * </ol>
 * <p>前三項是 SQL 條件，服務層另做一次 Java 端檢查作為縱深防護。</p>
 *
 * <h3>fail-safe</h3>
 * <ul>
 *   <li>任何 facade 查詢失敗（列舉近期文章、取得引用集合）都往外拋，由 job 整次中止；
 *       引用集合在<b>刪除開始前</b>全部查完，因此查詢失敗時零刪除。</li>
 *   <li>刪除走 {@link FileCleanupSupport#deleteAll}（→ {@link FileService#deleteFile} 的嚴格語意）：先刪 MinIO，
 *       失敗則該筆 metadata 保留、留到下次；其餘檔案仍會嘗試，最後把第一個例外往外拋。</li>
 *   <li>單次處理有上限（文章數、掃描數、刪除數）與時間預算，避免長時間占住排程執行緒與鎖。</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Service
public class FileBindingReconcileService {

    /** 每頁筆數（facade 與 repository 的 keyset 分頁共用） */
    public static final int PAGE_SIZE = 200;

    /** keyset 起點：nil UUID 在 PostgreSQL uuid 排序中最小 */
    private static final UUID START_CURSOR = new UUID(0L, 0L);

    /**
     * 補綁定結果。
     *
     * @param scanned   處理的文章數
     * @param rebound   實際執行了綁定回填的文章數（文章在處理時已不存在者不計）
     * @param failed    回填時拋例外的文章數
     * @param truncated 是否因文章數上限或時間預算而未處理完
     */
    public record RebindResult(int scanned, int rebound, int failed, boolean truncated) {
    }

    /**
     * 清理結果。
     *
     * @param scanned        掃描（已做出去留決定）的候選檔案數
     * @param deleted        實際刪除數
     * @param keptReferenced 因被作者的文章引用而保留的數量
     * @param nextCursor     下次續跑的游標（最後一個已決定去留的檔案 id）；null 表示整輪掃描完成
     * @param truncated      是否因掃描／刪除上限或時間預算而提早結束
     */
    public record CleanupResult(int scanned, int deleted, int keptReferenced, UUID nextCursor, boolean truncated) {
    }

    private final ArticleLookupFacade articleLookupFacade;
    private final ArticleFileBindingService articleFileBindingService;
    private final FileMetadataRepository fileMetadataRepository;
    private final FileService fileService;
    private final int pageSize;

    /**
     * 建立服務（Spring 使用）。
     *
     * @param articleLookupFacade       文章最小依賴查詢 facade（近期更新文章、作者文章引用的檔案）
     * @param articleFileBindingService 依文章內文回填綁定的既有邏輯
     * @param fileMetadataRepository    檔案 metadata 資料存取
     * @param fileService               檔案服務（單檔刪除：先 MinIO 後 DB）
     */
    @Autowired
    public FileBindingReconcileService(ArticleLookupFacade articleLookupFacade,
                                       ArticleFileBindingService articleFileBindingService,
                                       FileMetadataRepository fileMetadataRepository,
                                       FileService fileService) {
        this(articleLookupFacade, articleFileBindingService, fileMetadataRepository, fileService, PAGE_SIZE);
    }

    /**
     * 建立服務（可指定頁大小，測試用）。
     *
     * @param articleLookupFacade       文章最小依賴查詢 facade
     * @param articleFileBindingService 依文章內文回填綁定的既有邏輯
     * @param fileMetadataRepository    檔案 metadata 資料存取
     * @param fileService               檔案服務
     * @param pageSize                  每頁筆數（正數）
     */
    FileBindingReconcileService(ArticleLookupFacade articleLookupFacade,
                                ArticleFileBindingService articleFileBindingService,
                                FileMetadataRepository fileMetadataRepository,
                                FileService fileService, int pageSize) {
        this.articleLookupFacade = articleLookupFacade;
        this.articleFileBindingService = articleFileBindingService;
        this.fileMetadataRepository = fileMetadataRepository;
        this.fileService = fileService;
        this.pageSize = pageSize;
    }

    /**
     * B1：對「自 {@code since} 起有更新」的文章（不分狀態）逐篇重新解析內文並回填綁定。
     *
     * <p>列舉文章（facade）失敗會往外拋；單篇回填失敗只計入 {@code failed} 並繼續（該篇圖片仍受清理的引用安全網保護）。</p>
     *
     * @param since       更新時間下界（含）
     * @param maxArticles 單次處理文章數上限
     * @param timeUp      時間預算是否已用完（每篇之前檢查）
     * @return 處理結果
     */
    public RebindResult rebindRecentlyUpdatedArticles(LocalDateTime since, int maxArticles, BooleanSupplier timeUp) {
        int scanned = 0;
        int rebound = 0;
        int failed = 0;
        boolean truncated = false;
        UUID after = null;
        outer:
        while (true) {
            if (timeUp.getAsBoolean() || scanned >= maxArticles) {
                truncated = true;
                break;
            }
            List<UUID> page = articleLookupFacade.findUuidsUpdatedSinceAnyStatus(since, after, pageSize);
            if (page.isEmpty()) {
                break;
            }
            for (UUID articleUuid : page) {
                if (timeUp.getAsBoolean() || scanned >= maxArticles) {
                    truncated = true;
                    break outer;
                }
                scanned++;
                try {
                    if (articleFileBindingService.rebindFromArticleContent(articleUuid)) {
                        rebound++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("綁定校正：文章回填失敗，略過（隔天會再試）articleUuid={}: {}", articleUuid, e.toString());
                }
            }
            if (page.size() < pageSize) {
                break;
            }
            after = page.get(page.size() - 1);
        }
        log.info("綁定校正（補綁定）完成 since={} scanned={} rebound={} failed={} truncated={}",
                since, scanned, rebound, failed, truncated);
        return new RebindResult(scanned, rebound, failed, truncated);
    }

    /**
     * D6：清理建立早於 {@code createdBefore}、仍未綁定、非頭像、且不被上傳者任何文章引用的檔案。
     *
     * <p>以 keyset 游標（檔案 id）掃描候選；先掃描並確認所有引用集合、收齊要刪的檔案，最後才開始刪除——
     * 因此 facade 查詢失敗時零刪除。被引用而保留的檔案每次都會重新掃到，故以 {@code maxScan} 與游標分次處理整張表，
     * 避免它們占滿掃描視窗讓後面的檔案餓死。</p>
     *
     * @param createdBefore 建立時間上界（不含）；通常為「現在 − 保留天數」
     * @param afterCursor   上次續跑的游標（不含）；null 表示從頭
     * @param maxDeletes    單次刪除上限
     * @param maxScan       單次掃描上限（以頁為單位檢查，可能略為超出不到一頁）
     * @param timeUp        時間預算是否已用完（每頁之前檢查）
     * @return 清理結果；{@code nextCursor} 為 null 代表整輪掃描完成
     * @throws RuntimeException facade 查詢失敗（零刪除），或某檔案刪除失敗（其餘已盡量處理）
     */
    public CleanupResult cleanupUnboundFiles(LocalDateTime createdBefore, UUID afterCursor,
                                             int maxDeletes, int maxScan, BooleanSupplier timeUp) {
        Map<UUID, Set<UUID>> referencedByUploader = new HashMap<>();
        List<FileMetadata> toDelete = new ArrayList<>();
        int scanned = 0;
        int keptReferenced = 0;
        boolean finished = false;
        boolean truncated = false;
        UUID lastDecided = afterCursor;
        UUID after = afterCursor == null ? START_CURSOR : afterCursor;

        /* 階段一：只讀。掃描候選、查引用集合、收齊要刪的檔案；任何例外都在刪除之前拋出 */
        outer:
        while (true) {
            if (timeUp.getAsBoolean() || scanned >= maxScan) {
                truncated = true;
                break;
            }
            List<FileMetadata> page = fileMetadataRepository.findUnboundNonAvatarCreatedBefore(createdBefore, after, pageSize);
            if (page.isEmpty()) {
                finished = true;
                break;
            }
            for (FileMetadata file : page) {
                if (!isDeletionCandidate(file, createdBefore)) {
                    scanned++;
                    lastDecided = file.getId();
                    continue;
                }
                Set<UUID> referenced = referencedByUploader.computeIfAbsent(file.getUploaderId(),
                        articleLookupFacade::findFileUuidsReferencedByAuthorArticles);
                if (referenced.contains(file.getId())) {
                    keptReferenced++;
                } else {
                    if (toDelete.size() >= maxDeletes) {
                        truncated = true;
                        break outer;
                    }
                    toDelete.add(file);
                }
                scanned++;
                lastDecided = file.getId();
            }
            if (page.size() < pageSize) {
                finished = true;
                break;
            }
            after = page.get(page.size() - 1).getId();
        }

        /* 階段二：嚴格語意的刪除（先 MinIO 後 DB；失敗的留到下次，其餘照做，最後外拋第一個例外） */
        int deleted = toDelete.isEmpty() ? 0
                : FileCleanupSupport.deleteAll(fileService, toDelete, "（未綁定檔清理）createdBefore=" + createdBefore);
        UUID nextCursor = finished ? null : lastDecided;
        log.info("綁定校正（未綁定檔清理）完成 createdBefore={} scanned={} deleted={} keptReferenced={} truncated={} finished={}",
                createdBefore, scanned, deleted, keptReferenced, truncated, finished);
        return new CleanupResult(scanned, deleted, keptReferenced, nextCursor, truncated);
    }

    /**
     * 縱深防護：SQL 已過濾「未綁定、非頭像、夠舊」，這裡再確認一次；任何一項不成立都不刪。
     *
     * @param file          候選檔案
     * @param createdBefore 建立時間上界（不含）
     * @return 四個條件中的前三個都成立
     */
    private static boolean isDeletionCandidate(FileMetadata file, LocalDateTime createdBefore) {
        return file.getUsageType() != UsageType.AVATAR
                && file.getArticleUuid() == null
                && file.getCreatedAt() != null
                && file.getCreatedAt().isBefore(createdBefore);
    }
}
