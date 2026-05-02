package dowob.xyz.blog.module.version.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.module.version.model.ArticleVersion;
import dowob.xyz.blog.module.version.model.dto.request.CreateManualSnapshotRequest;
import dowob.xyz.blog.module.version.model.dto.response.VersionDetailResponse;
import dowob.xyz.blog.module.version.model.dto.response.VersionSummaryResponse;
import dowob.xyz.blog.module.version.service.VersioningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Version Controller — 6 端點。
 *
 * <ul>
 *   <li>GET  /api/v1/articles/{articleUuid}/versions — 版本列表（分頁，type filter 可選）</li>
 *   <li>GET  /api/v1/articles/{articleUuid}/versions/{versionUuid} — 版本詳情（含 content）</li>
 *   <li>POST /api/v1/articles/{articleUuid}/versions/manual — 建立手動快照</li>
 *   <li>POST /api/v1/articles/{articleUuid}/versions/{versionUuid}/restore — 還原快照</li>
 *   <li>POST /api/v1/articles/{articleUuid}/versions/{versionUuid}/promote — AUTO 升級為 MANUAL</li>
 *   <li>DELETE /api/v1/articles/{articleUuid}/versions/{versionUuid} — 刪除快照</li>
 * </ul>
 *
 * <p>所有端點皆需已認證（isAuthenticated），owner 與 admin 檢查在 service 層執行。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1/articles/{articleUuid}/versions")
@RequiredArgsConstructor
@Tag(name = "Version")
public class VersionController {

    private final VersioningService versioningService;

    /**
     * GET /api/v1/articles/{articleUuid}/versions
     * 列出 article 的版本 summary（分頁，可選 type filter）。
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "版本列表（分頁）")
    public ApiResponse<PageResult<VersionSummaryResponse>> list(
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        boolean isAdmin = isAdmin();
        return ApiResponse.success(
            versioningService.listByArticle(articleUuid, type, page, size, currentUserId, isAdmin));
    }

    /**
     * GET /api/v1/articles/{articleUuid}/versions/{versionUuid}
     * 取版本詳情（含完整 content）。
     */
    @GetMapping("/{versionUuid}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "版本詳情")
    public ApiResponse<VersionDetailResponse> getDetail(
            @PathVariable UUID articleUuid,
            @PathVariable UUID versionUuid,
            @AuthenticationPrincipal Long currentUserId) {
        boolean isAdmin = isAdmin();
        return ApiResponse.success(
            versioningService.getDetail(versionUuid, currentUserId, isAdmin));
    }

    /**
     * POST /api/v1/articles/{articleUuid}/versions/manual
     * 建立手動快照（type=MANUAL）。
     */
    @PostMapping("/manual")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "建立手動快照")
    public ApiResponse<ArticleVersion> createManual(
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal Long currentUserId,
            @Valid @RequestBody(required = false) CreateManualSnapshotRequest req) {
        boolean isAdmin = isAdmin();
        Long articleId = versioningService.findArticleIdByUuidOrThrow(articleUuid, currentUserId, isAdmin);
        String note = req != null ? req.getNote() : null;
        ArticleVersion v = versioningService.recordManualSnapshot(articleId, note);
        return ApiResponse.success(v);
    }

    /**
     * POST /api/v1/articles/{articleUuid}/versions/{versionUuid}/restore
     * 將指定版本快照還原為文章當前狀態。
     */
    @PostMapping("/{versionUuid}/restore")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "還原快照")
    public ApiResponse<Void> restore(
            @PathVariable UUID articleUuid,
            @PathVariable UUID versionUuid,
            @AuthenticationPrincipal Long currentUserId) {
        boolean isAdmin = isAdmin();
        versioningService.restore(versionUuid, currentUserId, isAdmin);
        return ApiResponse.success();
    }

    /**
     * POST /api/v1/articles/{articleUuid}/versions/{versionUuid}/promote
     * 將 AUTO 快照升級為 MANUAL（使用者救援機制）。
     */
    @PostMapping("/{versionUuid}/promote")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "AUTO 升級為 MANUAL")
    public ApiResponse<ArticleVersion> promote(
            @PathVariable UUID articleUuid,
            @PathVariable UUID versionUuid,
            @AuthenticationPrincipal Long currentUserId) {
        boolean isAdmin = isAdmin();
        ArticleVersion v = versioningService.promote(versionUuid, currentUserId, isAdmin);
        return ApiResponse.success(v);
    }

    /**
     * DELETE /api/v1/articles/{articleUuid}/versions/{versionUuid}
     * 刪除快照（僅允許 MANUAL / AUTO；PUBLISHED 不可刪）。
     */
    @DeleteMapping("/{versionUuid}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "刪除快照")
    public ApiResponse<Void> delete(
            @PathVariable UUID articleUuid,
            @PathVariable UUID versionUuid,
            @AuthenticationPrincipal Long currentUserId) {
        boolean isAdmin = isAdmin();
        versioningService.delete(versionUuid, currentUserId, isAdmin);
        return ApiResponse.success();
    }

    /**
     * 判斷當前認證使用者是否為 ADMIN。
     */
    private boolean isAdmin() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
