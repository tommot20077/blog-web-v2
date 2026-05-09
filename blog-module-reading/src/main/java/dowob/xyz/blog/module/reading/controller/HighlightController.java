package dowob.xyz.blog.module.reading.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
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
@Tag(name = "Highlight")
public class HighlightController {

    private final HighlightService highlightService;

    @PostMapping("/articles/{articleUuid}/highlights")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "建立 highlight + 可選 note")
    public ApiResponse<HighlightResponse> create(
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateHighlightRequest req) {
        return ApiResponse.success(highlightService.create(articleUuid, userId, req));
    }

    @GetMapping("/articles/{articleUuid}/highlights")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "撈我在這篇文章的所有 highlight")
    public ApiResponse<List<HighlightResponse>> list(
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal Long userId) {
        return ApiResponse.success(highlightService.getByArticle(articleUuid, userId));
    }

    @PutMapping("/highlights/{uuid}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "更新 highlight (color/note)")
    public ApiResponse<HighlightResponse> update(
            @PathVariable UUID uuid,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UpdateHighlightRequest req) {
        return ApiResponse.success(highlightService.update(uuid, userId, req));
    }

    @DeleteMapping("/highlights/{uuid}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "硬刪除 highlight")
    public ApiResponse<Void> delete(
            @PathVariable UUID uuid,
            @AuthenticationPrincipal Long userId) {
        highlightService.delete(uuid, userId);
        return ApiResponse.success();
    }
}
