package dowob.xyz.blog.module.reading.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
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
@Tag(name = "Reading Progress")
public class ReadingProgressController {

    private final ReadingProgressService progressService;

    @PutMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "更新閱讀進度（HSET Redis）")
    public ApiResponse<Void> update(
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UpdateProgressRequest req) {
        progressService.update(userId, articleUuid, req.getProgress(), req.getLastHeading());
        return ApiResponse.success();
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "查詢進度（Redis 優先）")
    public ApiResponse<ProgressResponse> get(
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal Long userId) {
        return ApiResponse.success(progressService.get(userId, articleUuid).orElse(null));
    }
}
