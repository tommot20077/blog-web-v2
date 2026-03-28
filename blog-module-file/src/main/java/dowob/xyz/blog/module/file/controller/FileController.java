package dowob.xyz.blog.module.file.controller;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.util.SecurityUtils;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.model.dto.FileUploadResponse;
import dowob.xyz.blog.module.file.model.dto.QuotaResponse;
import dowob.xyz.blog.module.file.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * 檔案管理 REST Controller
 *
 * <p>
 * 提供檔案上傳、刪除、查詢及配額查詢等 HTTP 端點。
 * 上傳端點需要 FILE_UPLOAD 權限，刪除及查詢個人檔案需要認證。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequiredArgsConstructor
public class FileController {

    /**
     * 檔案服務
     */
    private final FileService fileService;

    /**
     * 用戶 Facade（用於將 userId 轉換為 UUID）
     */
    private final UserFacade userFacade;

    /**
     * 上傳檔案（需要 FILE_UPLOAD 權限）
     *
     * @param file           上傳的檔案
     * @param usageType      用途類型
     * @param userId         當前登入用戶的資料庫主鍵
     * @param authentication 當前認證資訊（用於解析角色）
     * @return 上傳成功的檔案資訊
     */
    @PostMapping("/api/v1/files/upload")
    @PreAuthorize("hasAuthority('FILE_UPLOAD')")
    public ApiResponse<FileUploadResponse> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("usageType") UsageType usageType,
            @AuthenticationPrincipal Long userId,
            Authentication authentication) {
        UUID uploaderId = resolveUserUuid(userId);
        Role roleEnum = SecurityUtils.resolveRole(authentication);
        String role = roleEnum != null ? roleEnum.name() : "USER";
        return ApiResponse.success(fileService.uploadFile(file, usageType, uploaderId, role));
    }

    /**
     * 取得檔案元資料（公開端點）
     *
     * @param id 檔案 UUID
     * @return 檔案元資料
     */
    @GetMapping("/api/v1/files/{id}")
    public ApiResponse<FileMetadata> getFileMetadata(@PathVariable UUID id) {
        return ApiResponse.success(fileService.getFileMetadata(id));
    }

    /**
     * 刪除檔案（需認證，擁有者或管理員可操作）
     *
     * @param id             檔案 UUID
     * @param userId         當前登入用戶的資料庫主鍵
     * @param authentication 當前認證資訊（用於判斷是否為管理員）
     * @return 空回應
     */
    @DeleteMapping("/api/v1/files/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> deleteFile(
            @PathVariable UUID id,
            @AuthenticationPrincipal Long userId,
            Authentication authentication) {
        UUID requesterId = resolveUserUuid(userId);
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        fileService.deleteFile(id, requesterId, isAdmin);
        return ApiResponse.success();
    }

    /**
     * 取得目前使用者的所有上傳檔案
     *
     * @param userId   當前登入用戶的資料庫主鍵
     * @param pageable 分頁參數
     * @return 檔案列表
     */
    @GetMapping("/api/v1/users/me/files")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<FileMetadata>> getUserFiles(
            @AuthenticationPrincipal Long userId,
            Pageable pageable) {
        UUID uploaderId = resolveUserUuid(userId);
        return ApiResponse.success(fileService.getUserFiles(uploaderId, pageable));
    }

    /**
     * 取得目前使用者的儲存配額資訊
     *
     * @param userId         當前登入用戶的資料庫主鍵
     * @param authentication 當前認證資訊（用於解析角色）
     * @return 配額資訊
     */
    @GetMapping("/api/v1/users/me/quota")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<QuotaResponse> getQuota(
            @AuthenticationPrincipal Long userId,
            Authentication authentication) {
        UUID uploaderId = resolveUserUuid(userId);
        Role roleEnum = SecurityUtils.resolveRole(authentication);
        String role = roleEnum != null ? roleEnum.name() : "USER";
        return ApiResponse.success(fileService.getQuota(uploaderId, role));
    }

    /**
     * 根據 userId 查詢使用者 UUID（供 FileService 使用）
     *
     * @param userId 用戶內部 ID
     * @return 用戶 UUID
     * @throws BusinessException 若使用者不存在
     */
    private UUID resolveUserUuid(Long userId) {
        if (userId == null) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
        }
        return userFacade.getUserUuidById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }

}
