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
     * 上傳檔案（不綁定文章）
     *
     * @param file         上傳的 MultipartFile
     * @param usageType    檔案用途類型
     * @param uploaderId   上傳者 UUID
     * @param uploaderRole 上傳者角色字串（USER / AUTHOR / ADMIN）
     * @return 上傳成功的檔案回應資訊
     */
    default FileUploadResponse uploadFile(MultipartFile file, UsageType usageType, UUID uploaderId, String uploaderRole) {
        return uploadFile(file, usageType, uploaderId, uploaderRole, null);
    }

    /**
     * 上傳檔案，並可選擇性地一併綁定至文章（B4）
     *
     * <p>
     * 上傳當下文章可能尚未存在（新文章未儲存），因此 {@code articleUuid} 為可選：
     * 有值即在建立此檔案的 metadata 時直接設定 {@code articleUuid}。
     * </p>
     *
     * <p>
     * <b>刻意不透過 {@link #bindToArticle} 實作此綁定</b>：{@code bindToArticle} 是「完整替換」語意
     * （會解除該文章目前已綁定、但不在新清單內的其他檔案）。若對單一新檔案呼叫
     * {@code bindToArticle(articleUuid, List.of(fileId))}，會把該文章既有的其他綁定檔案全部誤解除。
     * 因此此處對新建立的 {@code FileMetadata} 直接設定 {@code articleUuid} 欄位，不影響同文章其他檔案。
     * </p>
     *
     * @param file         上傳的 MultipartFile
     * @param usageType    檔案用途類型
     * @param uploaderId   上傳者 UUID
     * @param uploaderRole 上傳者角色字串（USER / AUTHOR / ADMIN）
     * @param articleUuid  要一併綁定的文章 UUID；可為 null（代表暫不綁定）
     * @return 上傳成功的檔案回應資訊
     */
    FileUploadResponse uploadFile(MultipartFile file, UsageType usageType, UUID uploaderId, String uploaderRole, UUID articleUuid);

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
     * <p>
     * <b>擁有權不變量（安全複審 CRITICAL 修復）</b>：{@code fileUuids} 內的檔案僅在「該檔案的上傳者
     * == {@code articleUuid} 對應文章的作者」時才會被綁定；不符者安靜略過（記錄可疑嘗試但不拋錯，
     * 因為呼叫端如 blog-module-article 內文掃描取得的 UUID 屬使用者輸入）。文章不存在或無法解析
     * 其作者 UUID 時，fail-safe 為不綁定任何檔案。
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

    /**
     * 判斷請求者是否有權讀取指定檔案內容（授權矩陣同 {@link #canRead(UUID, UUID, boolean)}）
     *
     * <p>
     * 接受呼叫端<b>已取得</b>的 {@link FileMetadata}，省掉一次 DB 查詢。內文圖片是熱路徑
     * （一頁十張圖 = 十個 {@code /content} 請求），呼叫端若為了產生簽名網址等用途已經
     * 取過 metadata，就該重用它。
     * </p>
     *
     * @param metadata    已取得的檔案元資料（不可為 null）
     * @param requesterId 請求者 UUID；匿名請求傳 null
     * @param isAdmin     請求者是否具 ADMIN 權限
     * @return 允許讀取則 true，否則 false
     */
    boolean canRead(FileMetadata metadata, UUID requesterId, boolean isAdmin);

    /**
     * 產生指定檔案的 MinIO 短效簽名網址（B4：供 {@code GET /api/v1/files/{id}/content} 302 導向使用）
     *
     * <p>
     * 效期固定 5 分鐘：足夠瀏覽器完成一次載入，即使網址外流也很快失效。
     * 呼叫端須先以 {@link #canRead} 完成授權判斷後才呼叫本方法——本方法本身不做任何權限檢查。
     * </p>
     *
     * @param fileId 檔案 UUID
     * @return MinIO 簽名網址（含效期、簽章等查詢參數）
     * @throws dowob.xyz.blog.common.exception.BusinessException FILE_NOT_FOUND 若檔案不存在
     */
    String generatePresignedUrl(UUID fileId);

    /**
     * 產生指定檔案的 MinIO 短效簽名網址（接受已取得的 metadata，省掉一次 DB 查詢）
     *
     * <p>語意與效期同 {@link #generatePresignedUrl(UUID)}；同樣不做任何權限檢查，
     * 呼叫端須先以 {@link #canRead(FileMetadata, UUID, boolean)} 完成授權判斷。</p>
     *
     * @param metadata 已取得的檔案元資料（不可為 null）
     * @return MinIO 簽名網址
     */
    String generatePresignedUrl(FileMetadata metadata);
}
