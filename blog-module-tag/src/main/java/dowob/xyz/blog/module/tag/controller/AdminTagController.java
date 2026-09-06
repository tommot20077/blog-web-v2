package dowob.xyz.blog.module.tag.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.model.dto.UpdateTagRequest;
import dowob.xyz.blog.module.tag.service.TagService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 標籤管理員 API 控制器
 *
 * <p>
 * 提供管理員更新與刪除標籤的功能，需具備 {@code SYSTEM_CONFIG} 權限。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
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
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_CONFIG')")
    public ApiResponse<Tag> updateTag(
            @PathVariable UUID id,
            @RequestBody UpdateTagRequest request) {
        return ApiResponse.success(tagService.adminUpdateTag(id, request));
    }

    /**
     * 管理員刪除標籤
     *
     * @param id 標籤 ID
     * @return 成功回應
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_CONFIG')")
    public ApiResponse<Void> deleteTag(@PathVariable UUID id) {
        tagService.adminDeleteTag(id);
        return ApiResponse.success();
    }
}
