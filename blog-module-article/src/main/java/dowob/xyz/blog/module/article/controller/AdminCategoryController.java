package dowob.xyz.blog.module.article.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.module.article.model.dto.request.CreateCategoryRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateCategoryRequest;
import dowob.xyz.blog.module.article.model.dto.response.CategoryResponse;
import dowob.xyz.blog.module.article.service.CategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 分類 Admin Controller（需 ADMIN_CATEGORY 權限，且 URL 層限 ADMIN 角色）
 *
 * <p>
 * 提供分類 CRUD 的管理端點，僅 Admin 可存取。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    /** 分類業務服務 */
    private final CategoryService categoryService;

    /**
     * 建立分類
     *
     * @param request 建立請求
     * @return 建立後的分類回應
     */
    @PreAuthorize("hasAuthority('ADMIN_CATEGORY')")
    @PostMapping
    public ApiResponse<CategoryResponse> createCategory(@Valid @RequestBody CreateCategoryRequest request) {
        return ApiResponse.success(categoryService.createCategory(request));
    }

    /**
     * 更新分類
     *
     * @param uuid    分類公開 UUID
     * @param request 更新請求
     * @return 更新後的分類回應
     */
    @PreAuthorize("hasAuthority('ADMIN_CATEGORY')")
    @PutMapping("/{uuid}")
    public ApiResponse<CategoryResponse> updateCategory(
            @PathVariable UUID uuid,
            @Valid @RequestBody UpdateCategoryRequest request) {
        return ApiResponse.success(categoryService.updateCategory(uuid, request));
    }

    /**
     * 刪除分類（拒絕有文章的分類）
     *
     * @param uuid 分類公開 UUID
     * @return 成功回應
     */
    @PreAuthorize("hasAuthority('ADMIN_CATEGORY')")
    @DeleteMapping("/{uuid}")
    public ApiResponse<Void> deleteCategory(@PathVariable UUID uuid) {
        categoryService.deleteCategory(uuid);
        return ApiResponse.success();
    }
}
