package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.mapper.CategoryMapper;
import dowob.xyz.blog.module.article.model.Category;
import dowob.xyz.blog.module.article.model.dto.request.CreateCategoryRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateCategoryRequest;
import dowob.xyz.blog.module.article.model.dto.response.CategoryResponse;
import dowob.xyz.blog.module.article.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 分類服務實作
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;

    @Override
    public List<CategoryResponse> getAllCategories() {
        return categoryMapper.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public CategoryResponse getCategoryBySlug(String slug) {
        Category category = categoryMapper.findBySlug(slug);
        if (category == null) {
            throw new BusinessException(ArticleErrorCode.CATEGORY_NOT_FOUND);
        }
        return toResponse(category);
    }

    @Override
    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        if (categoryRepository.existsBySlug(request.getSlug())) {
            throw new BusinessException(ArticleErrorCode.CATEGORY_SLUG_DUPLICATE);
        }

        Category category = new Category();
        category.setUuid(UUID.randomUUID());
        category.setName(request.getName());
        category.setSlug(request.getSlug());
        category.setDescription(request.getDescription());
        category.setSortOrder(request.getSortOrder());

        Category saved = categoryRepository.save(category);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public CategoryResponse updateCategory(UUID categoryUuid, UpdateCategoryRequest request) {
        Category category = categoryRepository.findByUuid(categoryUuid)
                .orElseThrow(() -> new BusinessException(ArticleErrorCode.CATEGORY_NOT_FOUND));

        if (request.getSlug() != null && !request.getSlug().equals(category.getSlug())) {
            if (categoryRepository.existsBySlugAndIdNot(request.getSlug(), category.getId())) {
                throw new BusinessException(ArticleErrorCode.CATEGORY_SLUG_DUPLICATE);
            }
            category.setSlug(request.getSlug());
        }

        if (request.getName() != null) {
            category.setName(request.getName());
        }
        if (request.getDescription() != null) {
            category.setDescription(request.getDescription());
        }
        if (request.getSortOrder() != null) {
            category.setSortOrder(request.getSortOrder());
        }

        Category updated = categoryRepository.save(category);
        return toResponse(updated);
    }

    @Override
    @Transactional
    public void deleteCategory(UUID categoryUuid) {
        Category category = categoryRepository.findByUuid(categoryUuid)
                .orElseThrow(() -> new BusinessException(ArticleErrorCode.CATEGORY_NOT_FOUND));

        long articleCount = categoryMapper.countArticlesByCategoryId(category.getId());
        if (articleCount > 0) {
            throw new BusinessException(ArticleErrorCode.CATEGORY_HAS_ARTICLES);
        }

        categoryRepository.delete(category);
    }

    private CategoryResponse toResponse(Category category) {
        return CategoryResponse.builder()
                .uuid(category.getUuid())
                .name(category.getName())
                .slug(category.getSlug())
                .description(category.getDescription())
                .sortOrder(category.getSortOrder())
                .build();
    }
}
