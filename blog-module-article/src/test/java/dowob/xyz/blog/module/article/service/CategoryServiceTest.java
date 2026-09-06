package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.mapper.CategoryMapper;
import dowob.xyz.blog.module.article.model.Category;
import dowob.xyz.blog.module.article.model.dto.request.CreateCategoryRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateCategoryRequest;
import dowob.xyz.blog.module.article.model.dto.response.CategoryResponse;
import dowob.xyz.blog.module.article.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CategoryService 單元測試
 *
 * @author Yuan
 * @version 1.0
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryService 單元測試")
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private CategoryMapper categoryMapper;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    private static final UUID CATEGORY_UUID = UUID.randomUUID();
    private static final Long CATEGORY_ID = 1L;

    private Category buildCategory() {
        Category c = new Category();
        c.setId(CATEGORY_ID);
        c.setUuid(CATEGORY_UUID);
        c.setName("後端");
        c.setSlug("backend");
        c.setDescription("後端技術分類");
        c.setSortOrder(0);
        c.setCreatedAt(LocalDateTime.now());
        return c;
    }

    @Nested
    @DisplayName("createCategory")
    class CreateCategoryTests {

        @Test
        @DisplayName("正常：建立分類成功，回傳 CategoryResponse")
        void createCategory_success() {
            CreateCategoryRequest request = new CreateCategoryRequest();
            request.setName("後端");
            request.setSlug("backend");
            request.setDescription("後端技術");
            request.setSortOrder(1);

            when(categoryRepository.existsBySlug("backend")).thenReturn(false);
            when(categoryRepository.save(any(Category.class))).thenReturn(buildCategory());

            CategoryResponse response = categoryService.createCategory(request);

            assertThat(response).isNotNull();
            assertThat(response.getName()).isEqualTo("後端");
            assertThat(response.getSlug()).isEqualTo("backend");
            verify(categoryRepository).save(any(Category.class));
        }

        @Test
        @DisplayName("異常：slug 重複 → CATEGORY_SLUG_DUPLICATE")
        void createCategory_slugDuplicate_throws() {
            CreateCategoryRequest request = new CreateCategoryRequest();
            request.setName("後端");
            request.setSlug("backend");

            when(categoryRepository.existsBySlug("backend")).thenReturn(true);

            assertThatThrownBy(() -> categoryService.createCategory(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.CATEGORY_SLUG_DUPLICATE.getMessage());

            verify(categoryRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateCategory")
    class UpdateCategoryTests {

        @Test
        @DisplayName("正常：更新分類名稱與描述")
        void updateCategory_success() {
            Category existing = buildCategory();
            when(categoryRepository.findByUuid(CATEGORY_UUID)).thenReturn(Optional.of(existing));
            when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateCategoryRequest request = new UpdateCategoryRequest();
            request.setName("後端技術");
            request.setDescription("更新後描述");

            CategoryResponse response = categoryService.updateCategory(CATEGORY_UUID, request);

            assertThat(response.getName()).isEqualTo("後端技術");
            assertThat(response.getDescription()).isEqualTo("更新後描述");
            verify(categoryRepository).save(any(Category.class));
        }

        @Test
        @DisplayName("正常：slug 不變時，不觸發重複檢查")
        void updateCategory_sameSlug_noConflict() {
            Category existing = buildCategory();
            when(categoryRepository.findByUuid(CATEGORY_UUID)).thenReturn(Optional.of(existing));
            when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateCategoryRequest request = new UpdateCategoryRequest();
            request.setSlug("backend"); // 與現有相同

            when(categoryRepository.existsBySlugAndIdNot("backend", CATEGORY_ID)).thenReturn(false);

            CategoryResponse response = categoryService.updateCategory(CATEGORY_UUID, request);

            assertThat(response.getSlug()).isEqualTo("backend");
        }

        @Test
        @DisplayName("異常：更新 slug 與其他分類衝突 → CATEGORY_SLUG_DUPLICATE")
        void updateCategory_slugConflict_throws() {
            Category existing = buildCategory();
            when(categoryRepository.findByUuid(CATEGORY_UUID)).thenReturn(Optional.of(existing));
            when(categoryRepository.existsBySlugAndIdNot("frontend", CATEGORY_ID)).thenReturn(true);

            UpdateCategoryRequest request = new UpdateCategoryRequest();
            request.setSlug("frontend");

            assertThatThrownBy(() -> categoryService.updateCategory(CATEGORY_UUID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.CATEGORY_SLUG_DUPLICATE.getMessage());
        }

        @Test
        @DisplayName("異常：分類不存在 → CATEGORY_NOT_FOUND")
        void updateCategory_notFound_throws() {
            when(categoryRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> categoryService.updateCategory(UUID.randomUUID(), new UpdateCategoryRequest()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.CATEGORY_NOT_FOUND.getMessage());
        }
    }

    @Nested
    @DisplayName("deleteCategory")
    class DeleteCategoryTests {

        @Test
        @DisplayName("正常：刪除無文章的分類成功")
        void deleteCategory_success() {
            Category existing = buildCategory();
            when(categoryRepository.findByUuid(CATEGORY_UUID)).thenReturn(Optional.of(existing));
            when(categoryMapper.countArticlesByCategoryId(CATEGORY_ID)).thenReturn(0L);

            categoryService.deleteCategory(CATEGORY_UUID);

            verify(categoryRepository).delete(existing);
        }

        @Test
        @DisplayName("異常：分類下有文章 → CATEGORY_HAS_ARTICLES")
        void deleteCategory_hasArticles_throws() {
            Category existing = buildCategory();
            when(categoryRepository.findByUuid(CATEGORY_UUID)).thenReturn(Optional.of(existing));
            when(categoryMapper.countArticlesByCategoryId(CATEGORY_ID)).thenReturn(3L);

            assertThatThrownBy(() -> categoryService.deleteCategory(CATEGORY_UUID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.CATEGORY_HAS_ARTICLES.getMessage());

            verify(categoryRepository, never()).delete(any());
        }

        @Test
        @DisplayName("異常：分類不存在 → CATEGORY_NOT_FOUND")
        void deleteCategory_notFound_throws() {
            when(categoryRepository.findByUuid(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> categoryService.deleteCategory(UUID.randomUUID()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.CATEGORY_NOT_FOUND.getMessage());
        }
    }

    @Nested
    @DisplayName("getAllCategories")
    class GetAllCategoriesTests {

        @Test
        @DisplayName("正常：取得所有分類列表")
        void getAllCategories_success() {
            when(categoryMapper.findAll()).thenReturn(List.of(buildCategory()));

            List<CategoryResponse> result = categoryService.getAllCategories();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getSlug()).isEqualTo("backend");
        }

        @Test
        @DisplayName("正常：沒有分類時回傳空列表")
        void getAllCategories_empty() {
            when(categoryMapper.findAll()).thenReturn(List.of());

            List<CategoryResponse> result = categoryService.getAllCategories();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getCategoryBySlug")
    class GetCategoryBySlugTests {

        @Test
        @DisplayName("正常：根據 slug 取得分類")
        void getCategoryBySlug_success() {
            when(categoryMapper.findBySlug("backend")).thenReturn(buildCategory());

            CategoryResponse response = categoryService.getCategoryBySlug("backend");

            assertThat(response.getSlug()).isEqualTo("backend");
            assertThat(response.getName()).isEqualTo("後端");
        }

        @Test
        @DisplayName("異常：slug 不存在 → CATEGORY_NOT_FOUND")
        void getCategoryBySlug_notFound_throws() {
            when(categoryMapper.findBySlug(anyString())).thenReturn(null);

            assertThatThrownBy(() -> categoryService.getCategoryBySlug("nonexistent"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(ArticleErrorCode.CATEGORY_NOT_FOUND.getMessage());
        }
    }
}
