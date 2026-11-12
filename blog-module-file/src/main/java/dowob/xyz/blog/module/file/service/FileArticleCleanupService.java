package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 單篇文章被刪除（硬刪）後，清除綁定在該文章、且不再被任何文章引用的檔案（DATA-07：否則 MinIO 空間與配額永久洩漏）。
 *
 * <p>呼叫端有兩個：{@code FileArticleDeletedConsumer}（刪除事件，主要路徑）與
 * {@code FileArticleOrphanReconciliationJob}（每日校正，事件遺失時的第二道保險）；兩者共用同一套去留邏輯。
 * 與帳號刪除路徑（{@link FileUserCleanupService}）共用實際的批次刪除（{@link FileCleanupSupport}）。</p>
 *
 * <p><b>去留規則</b>（只用既有的跨模組資訊，不 JOIN article 表）：</p>
 * <ul>
 *   <li>候選 = {@code file_metadata.article_uuid} 等於該文章的檔案。這是內文圖片的綁定
 *       （{@link ArticleFileBindingService} 於內容變更事件後非同步回填）。</li>
 *   <li><b>頭像（{@link UsageType#AVATAR}）一律不動</b>：頭像是使用者個資，不綁文章，其生命週期跟帳號走
 *       （{@link FileUserCleanupService}）；即使資料異常使其 {@code article_uuid} 指向此文章也不刪。</li>
 *   <li>候選若仍被<b>上傳者其餘文章</b>（任何狀態——單篇刪除後那些文章都還在，草稿也算）的內文或封面引用
 *       （{@link ArticleLookupFacade#findFileUuidsReferencedByAuthorArticles}），保留；其餘刪除。
 *       集合是直接掃文章內容取得的，不依賴綁定欄位，所以綁定尚未回填的時間窗內也不會誤刪。</li>
 * </ul>
 *
 * <p><b>保留下來的檔案</b>其 {@code article_uuid} 仍指向已刪的文章（本方法維持現狀、不改綁定）。
 * 因此該檔案的公開可讀性（{@code isPubliclyReadable}：綁定的文章須存在且公開可見）會暫時變成「不可公開讀」，
 * 只剩上傳者與 ADMIN 能讀，直到引用它的文章下次儲存（觸發內容變更事件、重新綁定）才恢復。</p>
 *
 * <p><b>失敗語意</b>：查詢其餘文章引用失敗則整體往外拋、不刪任何檔案（無法確認時寧可重試，也不銷毀仍被引用的圖片）；
 * MinIO 刪除失敗亦拋錯（metadata 保留，重試即可）。拋出的例外由 MQ 容器重試，或由排程框架標記本次 failed。</p>
 *
 * <p><b>不處理</b>：從未綁定任何文章的上傳檔（例如編輯器上傳後沒存檔，{@code article_uuid} 為 NULL）。
 * 它們是否定期清理屬保留期限的產品決策，不在 DATA-07 範圍。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class FileArticleCleanupService {

    /** 檔案 metadata 資料存取 */
    private final FileMetadataRepository fileMetadataRepository;

    /** 檔案服務（既有的單檔刪除：先 MinIO 後 DB，失敗即拋錯） */
    private final FileService fileService;

    /** 文章查詢 Facade（取得上傳者其餘文章引用的檔案集合） */
    private final ArticleLookupFacade articleLookupFacade;

    /**
     * 刪除綁定在已刪文章、且不再被任何文章引用的檔案（metadata 列與 MinIO 物件）。
     *
     * @param articleUuid 已被刪除的文章公開 UUID；為 null 時略過
     * @return 實際刪除的檔案數
     * @throws RuntimeException 查詢引用失敗，或某檔案刪除失敗時（已盡量處理其餘檔案）
     */
    public int deleteFilesBoundToDeletedArticle(UUID articleUuid) {
        if (articleUuid == null) {
            return 0;
        }
        List<FileMetadata> candidates = fileMetadataRepository.findByArticleUuid(articleUuid).stream()
                .filter(file -> file.getUsageType() != UsageType.AVATAR)
                .toList();
        if (candidates.isEmpty()) {
            return 0;
        }
        /* 先確認所有上傳者的引用集合，任何一次查詢失敗都在刪除任何檔案之前拋出（fail-safe） */
        Map<UUID, List<FileMetadata>> byUploader = new LinkedHashMap<>();
        for (FileMetadata file : candidates) {
            byUploader.computeIfAbsent(file.getUploaderId(), k -> new ArrayList<>()).add(file);
        }
        List<FileMetadata> toDelete = new ArrayList<>();
        for (Map.Entry<UUID, List<FileMetadata>> entry : byUploader.entrySet()) {
            Set<UUID> stillReferenced = articleLookupFacade.findFileUuidsReferencedByAuthorArticles(entry.getKey());
            entry.getValue().stream()
                    .filter(file -> !stillReferenced.contains(file.getId()))
                    .forEach(toDelete::add);
        }
        return FileCleanupSupport.deleteAll(fileService, toDelete, "（文章刪除連動）articleUuid=" + articleUuid);
    }
}
