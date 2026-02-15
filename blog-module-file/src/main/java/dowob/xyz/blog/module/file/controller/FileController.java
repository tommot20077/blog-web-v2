package dowob.xyz.blog.module.file.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.model.dto.FileUploadResponse;
import dowob.xyz.blog.module.file.model.dto.QuotaResponse;
import dowob.xyz.blog.module.file.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
     * 上傳檔案（需要 FILE_UPLOAD 權限）
     *
     * @param file        上傳的檔案
     * @param usageType   用途類型
     * @param authentication 當前認證資訊
     * @return 上傳成功的檔案資訊
     */
    @PostMapping("/api/v1/files/upload")
    @PreAuthorize("hasAuthority('FILE_UPLOAD')")
    public ApiResponse<FileUploadResponse> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("usageType") UsageType usageType,
            Authentication authentication) {
        UUID uploaderId = UUID.fromString(authentication.getName());
        String role = resolveRole(authentication);
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
     * @param authentication 當前認證資訊
     * @return 空回應
     */
    @DeleteMapping("/api/v1/files/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> deleteFile(
            @PathVariable UUID id,
            Authentication authentication) {
        UUID requesterId = UUID.fromString(authentication.getName());
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        fileService.deleteFile(id, requesterId, isAdmin);
        return ApiResponse.success();
    }

    /**
     * 取得目前使用者的所有上傳檔案
     *
     * @param authentication 當前認證資訊
     * @param pageable       分頁參數
     * @return 檔案列表
     */
    @GetMapping("/api/v1/users/me/files")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<FileMetadata>> getUserFiles(
            Authentication authentication,
            Pageable pageable) {
        UUID uploaderId = UUID.fromString(authentication.getName());
        return ApiResponse.success(fileService.getUserFiles(uploaderId, pageable));
    }

    /**
     * 取得目前使用者的儲存配額資訊
     *
     * @param authentication 當前認證資訊
     * @return 配額資訊
     */
    @GetMapping("/api/v1/users/me/quota")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<QuotaResponse> getQuota(Authentication authentication) {
        UUID uploaderId = UUID.fromString(authentication.getName());
        String role = resolveRole(authentication);
        return ApiResponse.success(fileService.getQuota(uploaderId, role));
    }

    /**
     * 從 Authentication 中解析使用者角色字串
     *
     * @param authentication 當前認證資訊
     * @return 角色字串（USER / AUTHOR / ADMIN）
     */
    private String resolveRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .findFirst()
                .orElse("USER");
    }
}
