package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.errorcode.FileErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 使用者刪除帳號（匿名化，DATA-02／DATA-07）時，清除該使用者上傳、且<b>未被保留文章引用</b>的檔案。
 *
 * <p><b>「被保留文章引用」的判斷</b>只用既有的跨模組資訊，不 JOIN article 表：</p>
 * <ul>
 *   <li>檔案的 {@code article_uuid} 綁定（{@link ArticleFileBindingService} 於文章內容變更事件到達後非同步回填；內文圖片以此綁定，
 *       未綁定的檔案本來就不對外公開——見 {@code FileServiceImpl#canRead} 的授權矩陣，因此刪除它們不會讓任何公開頁面破圖）；</li>
 *   <li>{@link ArticleLookupFacade#findByUuid} 取得綁定文章的狀態，去留依 {@link ArticleStatus#isRemovedWithAuthorAccount(String)}：
 *       文章已不存在，或屬 DRAFT／PENDING_REVIEW／REJECTED（隨作者刪帳移除）→ 不算被引用，檔案刪除；
 *       PUBLISHED／ARCHIVED（保留）→ 檔案保留。</li>
 * </ul>
 * <p><b>綁定欄位不足以判斷</b>：{@link ArticleFileBindingService} 只掃內文、不掃 {@code coverImageUrl}，且重新綁定時會把不在
 * 內文清單的既有綁定改成 NULL，所以已發布文章的封面檔 {@code article_uuid} 常為 NULL；同一張圖同時用在草稿與已發布文章
 * 時也可能只綁到草稿那篇。因此另以 {@link ArticleLookupFacade#findFileUuidsReferencedByRetainedArticles} 取得保留文章
 * 內文與封面實際引用的檔案集合，凡在集合內一律保留（不論 article_uuid）。</p>
 * <p><b>綁定尚未回填的時間窗（W5A）</b>：綁定由 {@code FileArticleContentChangedConsumer} 非同步回填，文章剛儲存、
 * 事件還沒被消費前，內文新引用的檔案 {@code article_uuid} 仍是 NULL（或舊值）。此時判斷仍正確，因為「保留文章內文／封面
 * 實際引用的檔案集合」是直接掃文章內容取得的，不依賴綁定：被保留文章引用的檔案不論綁定是否已回填都會保留；
 * 只被草稿引用而尚未回填的檔案則本來就該隨草稿刪除；反方向（綁定仍指向已不引用它的舊文章）只會多保留、不會誤刪。</p>
 * <p>頭像（{@link UsageType#AVATAR}）是使用者個資，一律刪除。每個不同的文章只查一次狀態，不逐檔查詢。</p>
 *
 * <p>實際刪除委派既有的 {@link FileService#deleteFile}（以上傳者本人身分）：順序為先刪 MinIO 物件
 * （原檔與縮圖路徑，交易外），成功後才用短交易刪 metadata 列；MinIO 失敗則拋錯、metadata 保留，不會留下
 * 「物件還在、紀錄沒了」的孤兒，重試即可。若 MinIO 已刪但 DB 刪除失敗，重試時 MinIO 端為 no-op、DB 列隨後被刪掉。
 * 單一檔案失敗不影響其餘檔案，最後把例外往外拋，由容器重試（重試只會處理剩下的）；已不存在的檔案（重送）視為已完成。
 * 文章狀態查詢失敗則整體往外拋、<b>不刪除任何檔案</b>——無法確認時寧可重試，也不銷毀仍被引用的圖片。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileUserCleanupService {

    /** 檔案 metadata 資料存取 */
    private final FileMetadataRepository fileMetadataRepository;

    /** 檔案服務（既有的單檔刪除：metadata ＋ MinIO，含失敗回滾） */
    private final FileService fileService;

    /** 文章查詢 Facade（取得綁定文章的狀態） */
    private final ArticleLookupFacade articleLookupFacade;

    /**
     * 刪除某位上傳者名下未被保留文章引用的所有檔案。
     *
     * @param uploaderId 上傳者（已刪除帳號的使用者）公開 UUID
     * @return 實際刪除的檔案數
     * @throws RuntimeException 某檔案刪除失敗，或文章狀態查詢失敗時（已盡量處理其餘檔案）
     */
    public int deleteFilesNotReferencedByRetainedArticles(UUID uploaderId) {
        List<FileMetadata> files = fileMetadataRepository.findByUploaderIdOrderByCreatedAtDesc(uploaderId);
        if (files.isEmpty()) {
            return 0;
        }
        Set<UUID> retainedArticles = findRetainedArticleUuids(files);
        /* 保留文章內文／封面實際引用的檔案：不論 article_uuid 綁到哪（封面不會被 binder 綁定；同圖可能只綁到草稿） */
        Set<UUID> referencedFileIds = articleLookupFacade.findFileUuidsReferencedByRetainedArticles(uploaderId);

        int deleted = 0;
        List<RuntimeException> failures = new ArrayList<>();
        for (FileMetadata file : files) {
            if (isReferencedByRetainedArticle(file, retainedArticles, referencedFileIds)) {
                continue;
            }
            try {
                fileService.deleteFile(file.getId(), uploaderId, false);
                deleted++;
            } catch (BusinessException e) {
                if (!FileErrorCode.FILE_NOT_FOUND.getCode().equals(e.getCode())) {
                    failures.add(e);
                }
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
        log.info("使用者刪除連動：已刪除未被保留文章引用的檔案 uploaderId={} total={} deleted={} failed={}",
                uploaderId, files.size(), deleted, failures.size());
        if (!failures.isEmpty()) {
            RuntimeException first = failures.get(0);
            failures.stream().skip(1).forEach(first::addSuppressed);
            throw first;
        }
        return deleted;
    }

    /**
     * 找出這批檔案綁定的文章中仍被保留的那些（存在，且狀態不是隨作者刪帳移除的）。
     *
     * @param files 檔案 metadata
     * @return 仍被保留的文章 UUID
     */
    private Set<UUID> findRetainedArticleUuids(List<FileMetadata> files) {
        Set<UUID> boundArticles = new HashSet<>();
        for (FileMetadata file : files) {
            if (file.getUsageType() != UsageType.AVATAR && file.getArticleUuid() != null) {
                boundArticles.add(file.getArticleUuid());
            }
        }
        Set<UUID> retained = new HashSet<>();
        for (UUID articleUuid : boundArticles) {
            articleLookupFacade.findByUuid(articleUuid)
                    .filter(article -> !ArticleStatus.isRemovedWithAuthorAccount(article.status()))
                    .ifPresent(article -> retained.add(articleUuid));
        }
        return retained;
    }

    /**
     * 判斷檔案是否仍被保留文章引用。
     *
     * @param file             檔案 metadata
     * @param retainedArticles 仍被保留的文章 UUID
     * @param referencedFileIds 保留文章內文／封面引用的檔案 UUID（article facade 提供）
     * @return 非頭像，且（綁定的文章被保留，或被任一保留文章的內文／封面引用）時為 true
     */
    private boolean isReferencedByRetainedArticle(FileMetadata file, Set<UUID> retainedArticles,
                                                  Set<UUID> referencedFileIds) {
        if (file.getUsageType() == UsageType.AVATAR) {
            return false;
        }
        return (file.getArticleUuid() != null && retainedArticles.contains(file.getArticleUuid()))
                || referencedFileIds.contains(file.getId());
    }
}
