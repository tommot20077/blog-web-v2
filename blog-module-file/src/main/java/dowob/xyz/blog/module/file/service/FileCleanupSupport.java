package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.common.api.errorcode.FileErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.file.model.FileMetadata;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 「連動清理」路徑共用的批次刪除邏輯：帳號刪除（{@link FileUserCleanupService}）與文章刪除
 * （{@link FileArticleCleanupService}）判斷「哪些檔案該刪」的規則不同，但「怎麼刪」完全相同，集中在這裡避免兩份。
 *
 * <p>實際刪除委派 {@link FileService#deleteFile}（以檔案上傳者本人身分）：先在交易外刪 MinIO 物件（原檔與縮圖），
 * 成功後才以短交易刪 metadata 列；MinIO 失敗則拋錯、metadata 保留，不會留下「物件還在、紀錄沒了」的孤兒，重試即可。
 * 單一檔案失敗不影響其餘檔案；全部嘗試完後把第一個例外往外拋（其餘掛為 suppressed），由 MQ 容器或排程框架重試，
 * 重試只會處理剩下的。已不存在的檔案（{@code FILE_NOT_FOUND}，重送或併發刪除）視為已完成。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
final class FileCleanupSupport {

    private FileCleanupSupport() {
    }

    /**
     * 逐一刪除檔案，彙整失敗。
     *
     * @param fileService 檔案服務（單檔刪除：MinIO ＋ metadata）
     * @param files       要刪除的檔案（每個以自己的 {@code uploaderId} 當請求者）
     * @param logContext  日誌識別（例如 {@code "articleUuid=..."}）
     * @return 實際刪除的檔案數
     * @throws RuntimeException 至少一個檔案刪除失敗時（已盡量處理其餘檔案）
     */
    static int deleteAll(FileService fileService, Collection<FileMetadata> files, String logContext) {
        int deleted = 0;
        List<RuntimeException> failures = new ArrayList<>();
        for (FileMetadata file : files) {
            try {
                fileService.deleteFile(file.getId(), file.getUploaderId(), false);
                deleted++;
            } catch (BusinessException e) {
                if (!FileErrorCode.FILE_NOT_FOUND.getCode().equals(e.getCode())) {
                    failures.add(e);
                }
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
        log.info("連動清理檔案 {} candidates={} deleted={} failed={}", logContext, files.size(), deleted, failures.size());
        if (!failures.isEmpty()) {
            RuntimeException first = failures.get(0);
            failures.stream().skip(1).forEach(first::addSuppressed);
            throw first;
        }
        return deleted;
    }
}
