package dowob.xyz.blog.module.series.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.util.SecurityUtils;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.model.dto.request.AddArticleToSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.request.CreateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.request.UpdateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.response.SeriesDetailResponse;
import dowob.xyz.blog.module.series.model.dto.response.SeriesSummaryResponse;
import dowob.xyz.blog.module.series.service.SeriesService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Series Controller — 7 端點。
 *
 * <ul>
 *   <li>GET /series — 公開列表</li>
 *   <li>GET /series/{slug} — 公開詳情（含我的進度）</li>
 *   <li>POST /series — 建立 Series（需 ARTICLE_CREATE 權限）</li>
 *   <li>PUT /series/{uuid} — 更新 Series（需 ARTICLE_CREATE + service 層 ownership）</li>
 *   <li>DELETE /series/{uuid} — 刪除 Series（需 ARTICLE_CREATE + service 層 ownership）</li>
 *   <li>PUT /series/{uuid}/articles/{articleUuid} — 加文章到 Series / 改 position（需 ARTICLE_CREATE + ownership）</li>
 *   <li>DELETE /series/{uuid}/articles/{articleUuid} — 從 Series 移除文章（需 ARTICLE_CREATE + ownership）</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1/series")
@RequiredArgsConstructor
@Tag(name = "Series")
public class SeriesController {

    private final SeriesService seriesService;

    @GetMapping
    @Operation(summary = "Series 列表（公開）")
    public ApiResponse<PageResult<SeriesSummaryResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(seriesService.listPublic(page, size));
    }

    /**
     * 取得 Series 詳情。
     *
     * <p>
     * <b>匿名可存取</b>——本端點依 {@code security.md} 原則 7 的「選填認證公開端點」豁免，
     * 故不標註 {@code @PreAuthorize}。三項豁免條件均成立：
     * </p>
     * <ol>
     *   <li>{@code SecurityConfig} 明確 {@code permitAll} {@code GET /api/v1/series/**}（對得上 Public Endpoints 表）；</li>
     *   <li>{@code currentUserId} 允許為 null，僅用於選填的閱讀進度（myProgress）個人化；</li>
     *   <li>本 JavaDoc 即為所需的豁免標註。</li>
     * </ol>
     * <p>
     * 文章可見性不倚賴此處的認證狀態：{@code getSeriesDetail} 對任何呼叫者一律只回 PUBLISHED 文章。
     * </p>
     *
     * @param slug          Series URL slug
     * @param currentUserId 當前使用者 ID（未登入為 null，僅供 myProgress 個人化）
     * @return Series 詳情（含文章列表與我的進度）
     */
    @GetMapping("/{slug}")
    @Operation(summary = "Series 詳情（匿名可存取，登入時含我的進度）")
    public ApiResponse<SeriesDetailResponse> get(
            @PathVariable String slug,
            @AuthenticationPrincipal Long currentUserId) {
        return ApiResponse.success(seriesService.getSeriesDetail(slug, currentUserId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ARTICLE_CREATE')")
    @Operation(summary = "建立 Series")
    public ApiResponse<Series> create(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateSeriesRequest req) {
        return ApiResponse.success(seriesService.createSeries(userId, req));
    }

    @PutMapping("/{uuid}")
    @PreAuthorize("hasAuthority('ARTICLE_CREATE')")
    @Operation(summary = "更新 Series")
    public ApiResponse<Series> update(
            @PathVariable UUID uuid,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UpdateSeriesRequest req) {
        boolean isAdmin = SecurityUtils.isAdmin();
        return ApiResponse.success(seriesService.updateSeries(uuid, userId, isAdmin, req));
    }

    @DeleteMapping("/{uuid}")
    @PreAuthorize("hasAuthority('ARTICLE_CREATE')")
    @Operation(summary = "刪除 Series")
    public ApiResponse<Void> delete(
            @PathVariable UUID uuid,
            @AuthenticationPrincipal Long userId) {
        boolean isAdmin = SecurityUtils.isAdmin();
        seriesService.deleteSeries(uuid, userId, isAdmin);
        return ApiResponse.success();
    }

    @PutMapping("/{uuid}/articles/{articleUuid}")
    @PreAuthorize("hasAuthority('ARTICLE_CREATE')")
    @Operation(summary = "加文章到 Series / 改 position")
    public ApiResponse<Void> addArticle(
            @PathVariable UUID uuid,
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody AddArticleToSeriesRequest req) {
        boolean isAdmin = SecurityUtils.isAdmin();
        seriesService.addArticleToSeries(uuid, articleUuid, userId, isAdmin, req.getPosition());
        return ApiResponse.success();
    }

    @DeleteMapping("/{uuid}/articles/{articleUuid}")
    @PreAuthorize("hasAuthority('ARTICLE_CREATE')")
    @Operation(summary = "從 Series 移除文章")
    public ApiResponse<Void> removeArticle(
            @PathVariable UUID uuid,
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal Long userId) {
        boolean isAdmin = SecurityUtils.isAdmin();
        seriesService.removeArticleFromSeries(uuid, articleUuid, userId, isAdmin);
        return ApiResponse.success();
    }

}
