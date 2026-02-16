package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.model.dto.FileUploadResponse;
import dowob.xyz.blog.module.file.model.dto.QuotaResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * 檔案服務介面
 *
 * <p>
 * 定義檔案上傳、刪除、配額查詢等業務操作。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface FileService {

    /**
     * 上傳檔案
     *
     * @param file         上傳的 MultipartFile
     * @param usageType    檔案用途類型
     * @param uploaderId   上傳者 UUID
     * @param uploaderRole 上傳者角色字串（USER / AUTHOR / ADMIN）
     * @return 上傳成功的檔案回應資訊
     */
    FileUploadResponse uploadFile(MultipartFile file, UsageType usageType, UUID uploaderId, String uploaderRole);

    /**
     * 刪除檔案
     *
     * @param fileId      要刪除的檔案 UUID
     * @param requesterId 請求刪除的使用者 UUID
     * @param isAdmin     是否為管理員（管理員可刪除任何檔案）
     */
    void deleteFile(UUID fileId, UUID requesterId, boolean isAdmin);

    /**
     * 查詢使用者的儲存配額資訊
     *
     * @param uploaderId   使用者 UUID
     * @param uploaderRole 使用者角色字串
     * @return 配額回應資訊
     */
    QuotaResponse getQuota(UUID uploaderId, String uploaderRole);

    /**
     * 依 ID 取得單一檔案元資料（公開）
     *
     * @param fileId 檔案 UUID
     * @return 檔案元資料
     */
    FileMetadata getFileMetadata(UUID fileId);

    /**
     * 取得使用者上傳的所有檔案
     *
     * @param uploaderId 使用者 UUID
     * @param pageable   分頁參數
     * @return 檔案元資料列表
     */
    List<FileMetadata> getUserFiles(UUID uploaderId, Pageable pageable);
}
