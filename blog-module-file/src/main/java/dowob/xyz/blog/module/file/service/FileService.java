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

    /**
     * 將指定檔案綁定至文章（設定該文章的完整檔案清單）
     *
     * <p>
     * 語意為「完整替換」而非「附加」：{@code fileUuids} 內的檔案會綁定至 {@code articleUuid}；
     * 原本已綁定 {@code articleUuid}、但不在本次清單內的檔案會被解除綁定（{@code articleUuid} 設回 null）。
     * 這是為了避免文章編輯時移除某張圖片後，該圖片仍殘留「屬於已發布文章」的公開讀取權限。
     * </p>
     *
     * @param articleUuid 文章 UUID
     * @param fileUuids   應綁定至此文章的檔案 UUID 完整清單；可為空清單（代表解除此文章的所有綁定）
     */
    void bindToArticle(UUID articleUuid, List<UUID> fileUuids);

    /**
     * 判斷請求者是否有權讀取指定檔案內容（依授權矩陣，任一成立即放行）
     *
     * <p>
     * 授權矩陣（見 {@code docs/superpowers/specs/2026-07-26-file-access-control-design.md} §4）：
     * </p>
     * <ol>
     *   <li>{@code usageType = AVATAR} → 允許（含匿名）</li>
     *   <li>已綁定文章且該文章狀態為 PUBLISHED → 允許（含匿名）</li>
     *   <li>{@code requesterId} 等於上傳者 → 允許</li>
     *   <li>{@code isAdmin} 為 true → 允許</li>
     *   <li>以上皆非（含未綁定任何文章）→ 拒絕（fail-safe）</li>
     * </ol>
     *
     * @param fileId      檔案 UUID
     * @param requesterId 請求者 UUID；匿名請求傳 null
     * @param isAdmin     請求者是否具 ADMIN 權限
     * @return 允許讀取則 true，否則 false
     * @throws dowob.xyz.blog.common.exception.BusinessException FILE_NOT_FOUND 若檔案不存在
     */
    boolean canRead(UUID fileId, UUID requesterId, boolean isAdmin);
}
