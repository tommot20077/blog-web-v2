package dowob.xyz.blog.module.reading.controller;

import dowob.xyz.blog.infrastructure.ratelimit.RateLimited;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.common.util.SecurityUtils;
import dowob.xyz.blog.module.reading.model.dto.request.CreateHighlightRequest;
import dowob.xyz.blog.module.reading.model.dto.request.UpdateHighlightRequest;
import dowob.xyz.blog.module.reading.model.dto.response.HighlightResponse;
import dowob.xyz.blog.module.reading.service.HighlightService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 劃線 Controller。
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "劃線")
public class HighlightController {

    private final HighlightService highlightService;

    @PostMapping("/articles/{articleUuid}/highlights")
    @RateLimited("interaction-write")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "建立劃線",
            description = "登入。在可讀的文章上建立劃線，可附筆記。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗；A0201 文章不存在或對呼叫者不可讀")
    public ApiResponse<HighlightResponse> create(
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal AuthUser user,
            @Valid @RequestBody CreateHighlightRequest req) {
        return ApiResponse.success(highlightService.create(articleUuid, user.uuid(), SecurityUtils.isAdmin(), req));
    }

    @GetMapping("/articles/{articleUuid}/highlights")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "列出我在文章上的劃線",
            description = "登入。只回傳自己的劃線。")
    public ApiResponse<List<HighlightResponse>> list(
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal AuthUser user) {
        return ApiResponse.success(highlightService.getByArticle(articleUuid, user.uuid()));
    }

    @PutMapping("/highlights/{uuid}")
    @RateLimited("interaction-write")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "更新劃線",
            description = "登入；僅劃線擁有者。可更新顏色與筆記，未帶的欄位不變。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗；R0201 劃線不存在；R0202 不可變更他人的劃線；A0201 文章不存在或對呼叫者不可讀")
    public ApiResponse<HighlightResponse> update(
            @PathVariable UUID uuid,
            @AuthenticationPrincipal AuthUser user,
            @Valid @RequestBody UpdateHighlightRequest req) {
        return ApiResponse.success(highlightService.update(uuid, user.uuid(), SecurityUtils.isAdmin(), req));
    }

    @DeleteMapping("/highlights/{uuid}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "刪除劃線",
            description = "登入；僅劃線擁有者。硬刪除。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "R0201 劃線不存在；R0202 不可變更他人的劃線")
    public ApiResponse<Void> delete(
            @PathVariable UUID uuid,
            @AuthenticationPrincipal AuthUser user) {
        highlightService.delete(uuid, user.uuid());
        return ApiResponse.success();
    }
}
