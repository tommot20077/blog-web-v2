package dowob.xyz.blog.module.article.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.module.article.model.dto.response.CategoryResponse;
import dowob.xyz.blog.module.article.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 分類 REST Controller（公開端點）
 *
 * <p>
 * 提供分類查詢的公開 API，所有用戶均可存取。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Tag(name = "分類")
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    /** 分類業務服務 */
    private final CategoryService categoryService;

    /**
     * 取得所有分類列表（依 sort_order 排序）
     *
     * @return 分類列表
     */
    @Operation(summary = "分類列表",
            description = "公開。列出所有分類。")
    @GetMapping
    public ApiResponse<List<CategoryResponse>> getAllCategories() {
        return ApiResponse.success(categoryService.getAllCategories());
    }

    /**
     * 根據 slug 取得分類詳情
     *
     * @param slug 分類 slug
     * @return 分類詳情
     */
    @Operation(summary = "以 slug 取得分類",
            description = "公開。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0205 分類不存在")
    @GetMapping("/{slug}")
    public ApiResponse<CategoryResponse> getCategoryBySlug(@PathVariable String slug) {
        return ApiResponse.success(categoryService.getCategoryBySlug(slug));
    }
}
