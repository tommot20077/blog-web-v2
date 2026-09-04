package dowob.xyz.blog.module.version.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.util.SecurityUtils;
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
 * <p>owner 與 admin 檢查一律在 service 層執行（security.md 原則 4：能力歸
 * {@code @PreAuthorize}，歸屬歸 service 層）。</p>
 *
 * <p>兩個 GET（list / getDetail）僅讀取，要求 {@code isAuthenticated()}。
 * 四個寫入端點（manual / restore / promote / delete）皆會改寫或刪除文章的版本資料，
 * 因此比照 {@code PUT /api/v1/articles/{uuid}} 要求 {@code ARTICLE_EDIT} 細粒度權限——
 * restore 首先於 F-M1 收斂，manual / promote / delete 於 M-1（PR #68 review）跟進，
 * 統一「會寫入就要 ARTICLE_EDIT」的標準，避免被降級為 USER 的前作者仍能寫入或
 * 刪除自己文章的版本資料。</p>
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
        boolean isAdmin = SecurityUtils.isAdmin();
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
        boolean isAdmin = SecurityUtils.isAdmin();
        versioningService.assertVersionBelongsToArticle(articleUuid, versionUuid);
        return ApiResponse.success(
            versioningService.getDetail(versionUuid, currentUserId, isAdmin));
    }

    /**
     * POST /api/v1/articles/{articleUuid}/versions/manual
     * 建立手動快照（type=MANUAL）。
     *
     * <p>M-1（MEDIUM，PR #68 review）：與 restore（F-M1）同一威脅模型——此端點會寫入
     * 完整內容複本，比照收斂為 {@code ARTICLE_EDIT}，避免被降級為 USER 的前作者仍能寫入。</p>
     */
    @PostMapping("/manual")
    @PreAuthorize("hasAuthority('ARTICLE_EDIT')")
    @Operation(summary = "建立手動快照")
    public ApiResponse<VersionDetailResponse> createManual(
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal Long currentUserId,
            @Valid @RequestBody(required = false) CreateManualSnapshotRequest req) {
        boolean isAdmin = SecurityUtils.isAdmin();
        Long articleId = versioningService.findArticleIdByUuidOrThrow(articleUuid, currentUserId, isAdmin);
        String note = req != null ? req.getNote() : null;
        ArticleVersion v = versioningService.recordManualSnapshot(articleId, note);
        return ApiResponse.success(versioningService.toDetailResponse(v));
    }

    /**
     * POST /api/v1/articles/{articleUuid}/versions/{versionUuid}/restore
     * 將指定版本快照還原為文章當前狀態。
     *
     * <p>F-M1：還原是對文章內容的寫入，權限要求比照 {@code PUT /api/v1/articles/{uuid}} 的
     * {@code ARTICLE_EDIT}——原本只要 {@code isAuthenticated()}，導致角色被降級為 USER 的前作者
     * 仍能改寫自己的文章，與更新文章的標準不一致。</p>
     *
     * <p>owner 檢查仍在 {@code VersioningService.restore}（ADMIN 可繞過），
     * 內容凍結檢查在 article 模組的 {@code ArticleFacade.applyRestoreContent}（F-H1）。</p>
     */
    @PostMapping("/{versionUuid}/restore")
    @PreAuthorize("hasAuthority('ARTICLE_EDIT')")
    @Operation(summary = "還原快照")
    public ApiResponse<Void> restore(
            @PathVariable UUID articleUuid,
            @PathVariable UUID versionUuid,
            @AuthenticationPrincipal Long currentUserId) {
        boolean isAdmin = SecurityUtils.isAdmin();
        versioningService.assertVersionBelongsToArticle(articleUuid, versionUuid);
        versioningService.restore(versionUuid, currentUserId, isAdmin);
        return ApiResponse.success();
    }

    /**
     * POST /api/v1/articles/{articleUuid}/versions/{versionUuid}/promote
     * 將 AUTO 快照升級為 MANUAL（使用者救援機制）。
     *
     * <p>M-1（MEDIUM，PR #68 review）：與 restore（F-M1）同一威脅模型——此端點把可被
     * {@code retainAuto} 汰除的快照變成永久列，比照收斂為 {@code ARTICLE_EDIT}。</p>
     */
    @PostMapping("/{versionUuid}/promote")
    @PreAuthorize("hasAuthority('ARTICLE_EDIT')")
    @Operation(summary = "AUTO 升級為 MANUAL")
    public ApiResponse<VersionDetailResponse> promote(
            @PathVariable UUID articleUuid,
            @PathVariable UUID versionUuid,
            @AuthenticationPrincipal Long currentUserId) {
        boolean isAdmin = SecurityUtils.isAdmin();
        versioningService.assertVersionBelongsToArticle(articleUuid, versionUuid);
        ArticleVersion v = versioningService.promote(versionUuid, currentUserId, isAdmin);
        return ApiResponse.success(versioningService.toDetailResponse(v));
    }

    /**
     * DELETE /api/v1/articles/{articleUuid}/versions/{versionUuid}
     * 刪除快照（僅允許 MANUAL / AUTO；PUBLISHED 不可刪）。
     *
     * <p>M-1（MEDIUM，PR #68 review）：與 restore（F-M1）同一威脅模型——此操作破壞性且
     * 不可逆，比照收斂為 {@code ARTICLE_EDIT}，避免被降級為 USER 的前作者仍能清掉
     * 自己文章的版本證據。</p>
     */
    @DeleteMapping("/{versionUuid}")
    @PreAuthorize("hasAuthority('ARTICLE_EDIT')")
    @Operation(summary = "刪除快照")
    public ApiResponse<Void> delete(
            @PathVariable UUID articleUuid,
            @PathVariable UUID versionUuid,
            @AuthenticationPrincipal Long currentUserId) {
        boolean isAdmin = SecurityUtils.isAdmin();
        versioningService.assertVersionBelongsToArticle(articleUuid, versionUuid);
        versioningService.delete(versionUuid, currentUserId, isAdmin);
        return ApiResponse.success();
    }

}
