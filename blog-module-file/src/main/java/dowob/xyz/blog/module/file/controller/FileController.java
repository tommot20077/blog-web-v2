package dowob.xyz.blog.module.file.controller;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.FileErrorCode;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.exception.HttpStatusBusinessException;
import dowob.xyz.blog.common.util.SecurityUtils;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.model.dto.FileUploadRequest;
import dowob.xyz.blog.module.file.model.dto.FileUploadResponse;
import dowob.xyz.blog.module.file.model.dto.QuotaResponse;
import dowob.xyz.blog.module.file.service.FileService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
     * {@code /content} 302 導向回應的快取秒數。
     *
     * <p>刻意短於 {@code FileServiceImpl} 產生 presigned URL 的 5 分鐘效期，
     * 確保瀏覽器不會拿到已經過期的 Location。</p>
     */
    private static final int CONTENT_REDIRECT_CACHE_SECONDS = 240;

    /**
     * 上傳檔案（需要 FILE_UPLOAD 權限），可選擇性一併綁定至文章
     *
     * @param file           上傳的檔案
     * @param usageType      用途類型
     * @param articleUuid    要一併綁定的文章 UUID（可選；新文章尚未儲存時可留空，待文章儲存時再由
     *                       {@code blog-module-article} 掃描內文回填綁定）
     * @param userId         當前登入用戶的資料庫主鍵
     * @param authentication 當前認證資訊（用於解析角色）
     * @return 上傳成功的檔案資訊（{@code url} 為相對路徑 {@code /api/v1/files/{id}/content}）
     */
    @PostMapping("/api/v1/files/upload")
    @PreAuthorize("hasAuthority('FILE_UPLOAD')")
    @RequestBody(
            required = true,
            content = @Content(
                    mediaType = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE,
                    schema = @Schema(implementation = FileUploadRequest.class)
            )
    )
    public ApiResponse<FileUploadResponse> uploadFile(
            @Parameter(hidden = true) @RequestParam("file") MultipartFile file,
            @Parameter(hidden = true) @RequestParam("usageType") UsageType usageType,
            @Parameter(hidden = true) @RequestParam(value = "articleUuid", required = false) UUID articleUuid,
            @AuthenticationPrincipal Long userId,
            Authentication authentication) {
        UUID uploaderId = resolveUserUuid(userId);
        Role roleEnum = SecurityUtils.resolveRole(authentication);
        String role = roleEnum != null ? roleEnum.name() : "USER";
        return ApiResponse.success(fileService.uploadFile(file, usageType, uploaderId, role, articleUuid));
    }

    /**
     * 取得檔案元資料
     *
     * <p>
     * <b>MEDIUM 2 修復（安全複審）</b>：本端點原先完全不經授權判斷，任何人皆可查詢任一檔案的
     * metadata（含草稿圖片的存在與綁定關係）。現套用與 {@link #getFileContent} 相同的
     * {@link FileService#canRead} 授權矩陣：AVATAR、已綁定 PUBLISHED 文章的圖片對匿名開放；
     * 草稿圖片與未綁定檔案僅上傳者與 ADMIN 可讀，無權限時回傳 HTTP 403。
     * 檔案不存在則維持既有慣例（HTTP 400 + {@code FILE_NOT_FOUND}）。
     * </p>
     *
     * @param id             檔案 UUID
     * @param userId         當前登入用戶的資料庫主鍵（匿名為 null）
     * @param authentication 當前認證資訊（用於判斷是否為 ADMIN）
     * @return 檔案元資料
     */
    @GetMapping("/api/v1/files/{id}")
    public ApiResponse<FileMetadata> getFileMetadata(
            @PathVariable UUID id,
            @AuthenticationPrincipal Long userId,
            Authentication authentication) {
        UUID requesterId = resolveOptionalUserUuid(userId);
        boolean isAdmin = SecurityUtils.isAdmin(authentication);
        /* 先取一次 metadata 再授權，避免 canRead(id,..) 與 getFileMetadata(id) 各查一次 DB */
        FileMetadata metadata = fileService.getFileMetadata(id);
        if (!fileService.canRead(metadata, requesterId, isAdmin)) {
            throw new HttpStatusBusinessException(FileErrorCode.FILE_ACCESS_DENIED, HttpStatus.FORBIDDEN);
        }
        return ApiResponse.success(metadata);
    }

    /**
     * 依授權矩陣判斷後，302 導向 MinIO 短效簽名網址取得檔案內容
     *
     * <p>
     * 端點本身對所有請求皆「可到達」（見 {@code SecurityConfig} 的 permitAll 註解），
     * 但實際授權判斷在 {@link FileService#canRead}，依 spec §4 授權矩陣：
     * AVATAR、已綁定 PUBLISHED 文章的圖片對匿名開放；草稿圖片與未綁定檔案僅上傳者與 ADMIN 可讀。
     * </p>
     *
     * <p>
     * <b>絕不可回傳圖片位元組</b>：本端點僅回 302 + {@code Location}，由瀏覽器直接向 MinIO 取得實際內容，
     * 讓圖片流量不經過應用伺服器（本設計的核心目的）。
     * </p>
     *
     * @param id             檔案 UUID
     * @param userId         當前登入用戶的資料庫主鍵（匿名為 null）
     * @param authentication 當前認證資訊（用於判斷是否為 ADMIN）
     * @param response       HTTP 回應（用於寫入 302 狀態碼與 Location 標頭）
     */
    @GetMapping("/api/v1/files/{id}/content")
    public void getFileContent(
            @PathVariable UUID id,
            @AuthenticationPrincipal Long userId,
            Authentication authentication,
            HttpServletResponse response) {
        UUID requesterId = resolveOptionalUserUuid(userId);
        boolean isAdmin = SecurityUtils.isAdmin(authentication);

        /*
         * 只查一次 metadata，授權判斷與簽名網址產生都重用它。
         * 原本 canRead(id,..) 與 generatePresignedUrl(id) 各自 findById 一次——
         * 一頁十張圖就是二十次 DB 查詢。
         */
        FileMetadata metadata;
        try {
            metadata = fileService.getFileMetadata(id);
        } catch (BusinessException e) {
            /*
             * 其他端點的 FILE_NOT_FOUND 維持既有 HTTP 400 慣例，但本端點需要區分
             * 404（不存在）與 403（存在但無權限）。只轉換 FILE_NOT_FOUND，
             * 其餘 BusinessException 原樣往外拋——否則會把無關的錯誤一律偽裝成 404。
             */
            if (FileErrorCode.FILE_NOT_FOUND.getCode().equals(e.getCode())) {
                throw new HttpStatusBusinessException(FileErrorCode.FILE_NOT_FOUND, HttpStatus.NOT_FOUND);
            }
            throw e;
        }

        if (!fileService.canRead(metadata, requesterId, isAdmin)) {
            throw new HttpStatusBusinessException(FileErrorCode.FILE_ACCESS_DENIED, HttpStatus.FORBIDDEN);
        }

        String presignedUrl = fileService.generatePresignedUrl(metadata);
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader(HttpHeaders.LOCATION, presignedUrl);
        /*
         * 302 本身可被瀏覽器短暫快取，省下同一頁重複載入時的往返；但必須是 private——
         * 授權結果因人而異，且 Location 帶的是短效簽名網址，絕不可被共用快取層交叉服務。
         * max-age 取 240 秒，短於 presigned URL 的 5 分鐘效期，避免快取到的網址已失效。
         */
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=" + CONTENT_REDIRECT_CACHE_SECONDS);
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
        boolean isAdmin = SecurityUtils.isAdmin(authentication);
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

    /**
     * 根據 userId 查詢使用者 UUID，匿名請求（userId 為 null）回傳 null
     *
     * <p>供 {@link #getFileContent} 等允許匿名存取的端點使用，與 {@link #resolveUserUuid} 的差異在於
     * 匿名時不拋例外，因為授權判斷（{@link FileService#canRead}）本身接受 {@code requesterId = null}。</p>
     *
     * @param userId 用戶內部 ID；可為 null（匿名）
     * @return 用戶 UUID；匿名時為 null
     * @throws BusinessException 若 userId 非 null 但查無對應使用者
     */
    private UUID resolveOptionalUserUuid(Long userId) {
        if (userId == null) {
            return null;
        }
        return userFacade.getUserUuidById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }

}
