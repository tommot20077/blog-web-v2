package dowob.xyz.blog.module.version.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.module.version.model.dto.request.UpdatePreferenceRequest;
import dowob.xyz.blog.module.version.model.dto.response.EffectiveConfigResponse;
import dowob.xyz.blog.module.version.service.PreferenceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Preference Controller — 3 端點。
 *
 * <ul>
 *   <li>GET    /api/v1/me/preferences/version — 取得 effective version config</li>
 *   <li>PUT    /api/v1/me/preferences/version — 更新 user override（partial）</li>
 *   <li>DELETE /api/v1/me/preferences/version/{key} — 重置某 key 為系統預設</li>
 * </ul>
 *
 * <p>所有端點皆需已認證（isAuthenticated）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1/me/preferences/version")
@RequiredArgsConstructor
@Tag(name = "Version Preference")
public class PreferenceController {

    private final PreferenceResolver preferenceResolver;

    /**
     * GET /api/v1/me/preferences/version
     * 取得 user 的 effective version config（含 source 標示）。
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "取得 effective version config")
    public ApiResponse<EffectiveConfigResponse> get(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(preferenceResolver.getEffectiveResponse(userId));
    }

    /**
     * PUT /api/v1/me/preferences/version
     * 更新 user override（partial update，只更新非 null 欄位）。
     */
    @PutMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "更新 user override（partial）")
    public ApiResponse<EffectiveConfigResponse> update(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UpdatePreferenceRequest req) {
        return ApiResponse.success(preferenceResolver.updatePreferences(userId, req));
    }

    /**
     * DELETE /api/v1/me/preferences/version/{key}
     * 重置某 key 為系統預設（刪除 user override）。
     */
    @DeleteMapping("/{key}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "重置某 key 為系統預設")
    public ApiResponse<EffectiveConfigResponse> reset(
            @AuthenticationPrincipal Long userId,
            @PathVariable String key) {
        String fullKey = mapKey(key);
        return ApiResponse.success(preferenceResolver.resetKey(userId, fullKey));
    }

    /**
     * 將 short key 映射到完整的 pref_key。
     * 支援 camelCase 與 kebab-case 兩種格式。
     */
    private String mapKey(String shortKey) {
        return switch (shortKey) {
            case "enabled" -> PreferenceResolver.KEY_ENABLED;
            case "retain" -> PreferenceResolver.KEY_RETAIN;
            case "intervalSeconds", "interval-seconds" -> PreferenceResolver.KEY_INTERVAL_SECONDS;
            case "diffChars", "diff-chars" -> PreferenceResolver.KEY_DIFF_CHARS;
            default -> throw new IllegalArgumentException("Unknown preference key: " + shortKey);
        };
    }
}
