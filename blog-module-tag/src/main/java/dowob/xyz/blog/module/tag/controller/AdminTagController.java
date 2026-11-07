package dowob.xyz.blog.module.tag.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.model.dto.UpdateTagRequest;
import dowob.xyz.blog.module.tag.service.TagService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;

/**
 * 標籤管理員 API 控制器
 *
 * <p>
 * 提供管理員更新與刪除標籤的功能，需具備 {@code ADMIN_TAG} 權限。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "標籤管理")
@RestController
@RequestMapping("/api/v1/admin/tags")
@RequiredArgsConstructor
public class AdminTagController {

    /**
     * 標籤業務服務
     */
    private final TagService tagService;

    /**
     * 管理員更新標籤屬性
     *
     * @param id      標籤 ID
     * @param request 更新請求
     * @return 更新後的標籤
     */
    @Operation(summary = "更新標籤",
            description = "需 ADMIN_TAG 權限。更新顏色、圖示與描述，僅更新有帶的欄位。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗；A0301 標籤不存在")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ADMIN_TAG')")
    public ApiResponse<Tag> updateTag(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTagRequest request) {
        return ApiResponse.success(tagService.adminUpdateTag(id, request));
    }

    /**
     * 管理員刪除標籤
     *
     * @param id 標籤 ID
     * @return 成功回應
     */
    @Operation(summary = "刪除標籤",
            description = "需 ADMIN_TAG 權限。標籤仍被任何文章使用（含草稿）時不可刪除。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0301 標籤不存在；A0303 標籤正在使用中")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ADMIN_TAG')")
    public ApiResponse<Void> deleteTag(@PathVariable UUID id) {
        tagService.adminDeleteTag(id);
        return ApiResponse.success();
    }
}
