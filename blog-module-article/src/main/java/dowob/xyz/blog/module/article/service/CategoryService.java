package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.module.article.model.dto.request.CreateCategoryRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateCategoryRequest;
import dowob.xyz.blog.module.article.model.dto.response.CategoryResponse;

import java.util.List;
import java.util.UUID;

/**
 * 分類服務介面
 *
 * @author Yuan
 * @version 1.0
 */
public interface CategoryService {

    /**
     * 取得所有分類列表（依 sort_order 排序）
     *
     * @return 分類列表
     */
    List<CategoryResponse> getAllCategories();

    /**
     * 根據 slug 取得分類詳情
     *
     * @param slug 分類 slug
     * @return 分類回應
     */
    CategoryResponse getCategoryBySlug(String slug);

    /**
     * 建立分類（Admin Only）
     *
     * @param request 建立請求
     * @return 建立後的分類回應
     */
    CategoryResponse createCategory(CreateCategoryRequest request);

    /**
     * 更新分類（Admin Only）
     *
     * @param categoryUuid 分類公開 UUID
     * @param request      更新請求
     * @return 更新後的分類回應
     */
    CategoryResponse updateCategory(UUID categoryUuid, UpdateCategoryRequest request);

    /**
     * 刪除分類（Admin Only）
     *
     * <p>若分類下仍有文章，拋出 CATEGORY_HAS_ARTICLES 例外。</p>
     *
     * @param categoryUuid 分類公開 UUID
     */
    void deleteCategory(UUID categoryUuid);
}
