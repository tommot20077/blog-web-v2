package dowob.xyz.blog.module.file.controller;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.FileErrorCode;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.exception.HttpStatusBusinessException;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.common.util.SecurityUtils;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.util.StreamUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
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
@Slf4j
@RestController
@RequiredArgsConstructor
public class FileController {

    /**
     * 檔案服務
     */
    private final FileService fileService;

    /** 公開可讀內容的快取策略：一天（頭像、公開文章圖片；內容以檔案 UUID 為鍵，不可變） */
    private static final String PUBLIC_CACHE_CONTROL = "public, max-age=86400";

    /** 需登入／擁有者才可讀內容的快取策略：不得被共用快取或瀏覽器儲存 */
    private static final String PRIVATE_CACHE_CONTROL = "private, no-store";

    /**
     * 上傳檔案（需要 FILE_UPLOAD 權限），可選擇性一併綁定至文章
     *
     * @param file           上傳的檔案
     * @param usageType      用途類型
     * @param articleUuid    要一併綁定的文章 UUID（可選；新文章尚未儲存時可留空，待文章儲存後由 file 模組消費
     *                       {@code ArticleContentChangedEvent} 掃描內文回填綁定）
     * @param user         當前登入者（AuthUser）
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
            @AuthenticationPrincipal AuthUser user,
            Authentication authentication) {
        UUID uploaderId = user.uuid();
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
     * @param user         當前登入者（AuthUser；匿名為 null）
     * @param authentication 當前認證資訊（用於判斷是否為 ADMIN）
     * @return 檔案元資料
     */
    @GetMapping("/api/v1/files/{id}")
    public ApiResponse<FileMetadata> getFileMetadata(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthUser user,
            Authentication authentication) {
        UUID requesterId = user == null ? null : user.uuid();
        boolean isAdmin = SecurityUtils.isAdmin(authentication);
        /* 先取一次 metadata 再授權，避免 canRead(id,..) 與 getFileMetadata(id) 各查一次 DB */
        FileMetadata metadata = fileService.getFileMetadata(id);
        if (!fileService.canRead(metadata, requesterId, isAdmin)) {
            throw new HttpStatusBusinessException(FileErrorCode.FILE_ACCESS_DENIED, HttpStatus.FORBIDDEN);
        }
        return ApiResponse.success(metadata);
    }

    /**
     * 依授權矩陣判斷後，由後端直接串流檔案內容回瀏覽器
     *
     * <p>
     * 端點本身對所有請求皆「可到達」（見 {@code SecurityConfig} 的 permitAll 註解），
     * 但實際授權判斷在 {@link FileService#canRead}，依 spec §4 授權矩陣：
     * AVATAR、已綁定 PUBLISHED 文章的圖片對匿名開放；草稿圖片與未綁定檔案僅上傳者與 ADMIN 可讀。
     * 檔案不存在 404、無權限 403。
     * </p>
     *
     * <p>
     * <b>串流而非導向（W5A，Yuan 2026-11-05 決定）</b>：物件儲存（MinIO／SeaweedFS）的位址僅後端內網可達，
     * 瀏覽器連不到 presigned URL，且前端 CSP {@code img-src 'self' blob:} 只允許同源，故改由後端以
     * {@code getObject} 邊讀邊寫（不整檔進記憶體）。回應標頭：
     * {@code Content-Type} 取自 metadata、{@code Content-Length}、{@code X-Content-Type-Options: nosniff}、
     * {@code Content-Disposition: inline}、{@code Content-Security-Policy: default-src 'none'; sandbox}
     * （縱深防禦：即使日後 allowlist 放行 SVG 等可執行內容，直接導覽時也不執行腳本）。
     * </p>
     *
     * <p>
     * <b>快取</b>：公開可讀（AVATAR、綁定公開文章）→ {@code public, max-age=86400}；
     * 其餘（需登入／擁有者）→ {@code private, no-store}，避免共用快取存到。
     * {@code ETag} 取自檔案 UUID（物件寫入後內容不可變），{@code If-None-Match} 命中回 304
     * （<b>授權判斷先於 304</b>，無權限者不會因帶了 ETag 而繞過）。
     * </p>
     *
     * @param id             檔案 UUID
     * @param user           當前登入者（AuthUser；匿名為 null）
     * @param authentication 當前認證資訊（用於判斷是否為 ADMIN）
     * @param ifNoneMatch    請求的 {@code If-None-Match} 標頭；可為 null
     * @param response       HTTP 回應（寫入標頭與內容）
     * @throws IOException 寫出內容失敗（例如用戶端中斷連線）
     */
    @GetMapping("/api/v1/files/{id}/content")
    public void getFileContent(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthUser user,
            Authentication authentication,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            HttpServletResponse response) throws IOException {
        UUID requesterId = user == null ? null : user.uuid();
        boolean isAdmin = SecurityUtils.isAdmin(authentication);

        /* 只查一次 metadata，授權判斷與串流都重用它（一頁十張圖就是十個請求）。 */
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

        /*
         * 匿名且非 ADMIN 卻通過 canRead，必然是公開可讀（只有矩陣前兩條不看身分），省一次文章查詢；
         * 其餘情況（登入者可能因擁有者／ADMIN 身分才通過）需實際判斷是否公開。
         */
        boolean publicCacheable = (requesterId == null && !isAdmin) || fileService.isPubliclyReadable(metadata);
        String etag = "\"" + metadata.getId() + "\"";
        String cacheControl = publicCacheable ? PUBLIC_CACHE_CONTROL : PRIVATE_CACHE_CONTROL;

        /* 304 不需要讀物件，維持在 openContent 之前；此時回應本身就是成功語意，帶快取標頭正確。 */
        if (matchesEtag(ifNoneMatch, etag)) {
            response.setHeader(HttpHeaders.ETAG, etag);
            response.setHeader(HttpHeaders.CACHE_CONTROL, cacheControl);
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        /*
         * 先開串流、成功後才設任何標頭：開啟失敗（MinIO 故障）時 response 尚未 commit 也尚未帶 ETag／public 快取標頭，
         * 全域例外處理器回的 500 不會被 CDN／瀏覽器當成「可快取的公開圖片」存一天。
         */
        try (InputStream in = fileService.openContent(metadata)) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setHeader(HttpHeaders.ETAG, etag);
            response.setHeader(HttpHeaders.CACHE_CONTROL, cacheControl);
            response.setContentType(metadata.getContentType());
            if (metadata.getSize() != null) {
                response.setContentLengthLong(metadata.getSize());
            }
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline");
            response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
            StreamUtils.copy(in, response.getOutputStream());
        } catch (IOException e) {
            /*
             * 串流階段的 IOException 絕大多數是瀏覽器中途取消（ClientAbortException／Broken pipe）：
             * response 已 commit，往外拋只會讓通用 Exception 處理器記 ERROR 並試圖再寫 JSON，徒增噪音。
             */
            log.debug("檔案內容串流中斷（多為用戶端取消）fileId={}: {}", id, e.getMessage());
        }
    }

    /**
     * 判斷 {@code If-None-Match} 是否命中目前 ETag（支援 {@code *}、逗號分隔多值與弱驗證前綴 {@code W/}）
     *
     * @param ifNoneMatch 請求標頭值；可為 null
     * @param etag        目前 ETag（含雙引號）
     * @return 命中則 true
     */
    private static boolean matchesEtag(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String tag = candidate.trim();
            if (tag.equals("*")) {
                return true;
            }
            if (tag.startsWith("W/")) {
                tag = tag.substring(2);
            }
            if (tag.equals(etag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 刪除檔案（需認證，擁有者或管理員可操作）
     *
     * @param id             檔案 UUID
     * @param user         當前登入者（AuthUser）
     * @param authentication 當前認證資訊（用於判斷是否為管理員）
     * @return 空回應
     */
    @DeleteMapping("/api/v1/files/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> deleteFile(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthUser user,
            Authentication authentication) {
        UUID requesterId = user.uuid();
        boolean isAdmin = SecurityUtils.isAdmin(authentication);
        fileService.deleteFile(id, requesterId, isAdmin);
        return ApiResponse.success();
    }

    /**
     * 分頁取得目前使用者的上傳檔案（最新在前）
     *
     * <p>回應形狀仍為檔案陣列（不含總數）；{@code page} 為 0 起算、{@code size} 預設 20、上限 100（超過回 400）。
     * 排序固定，忽略 {@code sort} 參數。</p>
     *
     * @param user   當前登入者（AuthUser）
     * @param pageable 分頁參數
     * @return 檔案列表
     */
    @GetMapping("/api/v1/users/me/files")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<FileMetadata>> getUserFiles(
            @AuthenticationPrincipal AuthUser user,
            Pageable pageable) {
        UUID uploaderId = user.uuid();
        return ApiResponse.success(fileService.getUserFiles(uploaderId, pageable));
    }

    /**
     * 取得目前使用者的儲存配額資訊
     *
     * @param user         當前登入者（AuthUser）
     * @param authentication 當前認證資訊（用於解析角色）
     * @return 配額資訊
     */
    @GetMapping("/api/v1/users/me/quota")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<QuotaResponse> getQuota(
            @AuthenticationPrincipal AuthUser user,
            Authentication authentication) {
        UUID uploaderId = user.uuid();
        Role roleEnum = SecurityUtils.resolveRole(authentication);
        String role = roleEnum != null ? roleEnum.name() : "USER";
        return ApiResponse.success(fileService.getQuota(uploaderId, role));
    }

}
