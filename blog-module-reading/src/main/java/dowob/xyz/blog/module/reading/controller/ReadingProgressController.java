package dowob.xyz.blog.module.reading.controller;

import dowob.xyz.blog.infrastructure.ratelimit.RateLimited;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.common.util.SecurityUtils;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.reading.model.dto.request.UpdateProgressRequest;
import dowob.xyz.blog.module.reading.model.dto.response.ProgressResponse;
import dowob.xyz.blog.module.reading.service.ReadingProgressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 閱讀進度 Controller。
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1/articles/{articleUuid}/progress")
@RequiredArgsConstructor
@Tag(name = "閱讀進度")
public class ReadingProgressController {

    private final ReadingProgressService progressService;
    private final ArticleFacade articleFacade;

    @PutMapping
    @RateLimited("reading-progress")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "更新閱讀進度",
            description = "登入。進度值需介於 0 到 1。文章不存在或不可讀時靜默回 200 且不寫入，避免形成草稿存在性探測。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗（progress 需介於 0.000 到 1.000）")
    public ApiResponse<Void> update(
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal AuthUser user,
            @Valid @RequestBody UpdateProgressRequest req) {
        // SEC-16「可讀才可互動」：不可讀與「文章不存在」同一條路徑——既有契約是 200 靜默略過（不寫 Redis／DB），
        // 因此回應與副作用都與不存在完全相同，不形成草稿存在性 oracle
        if (!articleFacade.isReadableBy(articleUuid, user.uuid(), SecurityUtils.isAdmin())) {
            return ApiResponse.success();
        }
        progressService.update(user.uuid(), articleUuid, req.getProgress(), req.getLastHeading());
        return ApiResponse.success();
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "查詢閱讀進度",
            description = "登入。優先讀 Redis；尚無進度時 data 為 null。")
    public ApiResponse<ProgressResponse> get(
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal AuthUser user) {
        return ApiResponse.success(progressService.get(user.uuid(), articleUuid).orElse(null));
    }
}
