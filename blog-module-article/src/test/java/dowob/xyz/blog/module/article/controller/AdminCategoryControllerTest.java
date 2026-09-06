package dowob.xyz.blog.module.article.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.config.SecurityConfig;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.config.ArticleWebTestConfiguration;
import dowob.xyz.blog.module.article.model.dto.request.CreateCategoryRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateCategoryRequest;
import dowob.xyz.blog.module.article.model.dto.response.CategoryResponse;
import dowob.xyz.blog.module.article.service.CategoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * AdminCategoryController 單元測試（Web 層）
 *
 * <p>
 * 使用 {@code @WebMvcTest} 僅載入 Web 層，驗證管理員分類端點的
 * HTTP 請求處理行為，包含權限控制、請求驗證與回應結構。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest(AdminCategoryController.class)
@ContextConfiguration(classes = ArticleWebTestConfiguration.class)
@Import(SecurityConfig.class)
@DisplayName("AdminCategoryController 單元測試")
class AdminCategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CategoryService categoryService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private UserAuthService userAuthService;

    private static final UUID TEST_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static RequestPostProcessor asAdmin() {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(Role.ADMIN.getSpringSecurityRole()));
        Role.ADMIN.getPermissions().forEach(p ->
                authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(1L, null, authorities);
        return authentication(auth);
    }

    private static RequestPostProcessor asUser() {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(Role.USER.getSpringSecurityRole()));
        Role.USER.getPermissions().forEach(p ->
                authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(2L, null, authorities);
        return authentication(auth);
    }

    // =========================================================================
    // POST /api/admin/categories
    // =========================================================================

    @Test
    @DisplayName("POST / → 未認證 → 應回傳 401")
    void createCategory_unauthenticated_shouldReturn401() throws Exception {
        CreateCategoryRequest request = new CreateCategoryRequest();
        request.setName("技術");
        request.setSlug("tech");

        mockMvc.perform(post("/api/v1/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST / → 一般用戶 → 應回傳 403")
    void createCategory_asUser_shouldReturn403() throws Exception {
        CreateCategoryRequest request = new CreateCategoryRequest();
        request.setName("技術");
        request.setSlug("tech");

        mockMvc.perform(post("/api/v1/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(asUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST / → Admin 合法請求 → 應回傳 200 與分類資料")
    void createCategory_asAdmin_shouldReturn200WithCategory() throws Exception {
        CreateCategoryRequest request = new CreateCategoryRequest();
        request.setName("技術");
        request.setSlug("tech");
        request.setDescription("技術類文章");
        request.setSortOrder(1);

        CategoryResponse response = CategoryResponse.builder()
                .uuid(TEST_UUID)
                .name("技術")
                .slug("tech")
                .description("技術類文章")
                .sortOrder(1)
                .build();
        when(categoryService.createCategory(any(CreateCategoryRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.uuid").value(TEST_UUID.toString()))
                .andExpect(jsonPath("$.data.name").value("技術"))
                .andExpect(jsonPath("$.data.slug").value("tech"))
                .andExpect(jsonPath("$.data.sortOrder").value(1));

        verify(categoryService).createCategory(any(CreateCategoryRequest.class));
    }

    @Test
    @DisplayName("POST / → Admin 缺少必填欄位 → 應回傳驗證錯誤")
    void createCategory_asAdminMissingRequiredField_shouldReturnValidationError() throws Exception {
        CreateCategoryRequest request = new CreateCategoryRequest();
        // name 和 slug 都是 @NotBlank，故意留空

        mockMvc.perform(post("/api/v1/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(asAdmin()))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // PUT /api/admin/categories/{uuid}
    // =========================================================================

    @Test
    @DisplayName("PUT /{uuid} → 未認證 → 應回傳 401")
    void updateCategory_unauthenticated_shouldReturn401() throws Exception {
        UpdateCategoryRequest request = new UpdateCategoryRequest();
        request.setName("更新名稱");

        mockMvc.perform(put("/api/v1/admin/categories/{uuid}", TEST_UUID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /{uuid} → 一般用戶 → 應回傳 403")
    void updateCategory_asUser_shouldReturn403() throws Exception {
        UpdateCategoryRequest request = new UpdateCategoryRequest();
        request.setName("更新名稱");

        mockMvc.perform(put("/api/v1/admin/categories/{uuid}", TEST_UUID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(asUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /{uuid} → Admin 合法請求 → 應回傳 200 與更新後分類")
    void updateCategory_asAdmin_shouldReturn200WithUpdatedCategory() throws Exception {
        UpdateCategoryRequest request = new UpdateCategoryRequest();
        request.setName("更新名稱");
        request.setSlug("updated-slug");

        CategoryResponse response = CategoryResponse.builder()
                .uuid(TEST_UUID)
                .name("更新名稱")
                .slug("updated-slug")
                .sortOrder(0)
                .build();
        when(categoryService.updateCategory(eq(TEST_UUID), any(UpdateCategoryRequest.class))).thenReturn(response);

        mockMvc.perform(put("/api/v1/admin/categories/{uuid}", TEST_UUID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.name").value("更新名稱"))
                .andExpect(jsonPath("$.data.slug").value("updated-slug"));

        verify(categoryService).updateCategory(eq(TEST_UUID), any(UpdateCategoryRequest.class));
    }

    @Test
    @DisplayName("PUT /{uuid} → Admin 傳遞正確 UUID 路徑變數 → 應傳入 service")
    void updateCategory_asAdmin_shouldPassCorrectUuid() throws Exception {
        UUID specificUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UpdateCategoryRequest request = new UpdateCategoryRequest();
        request.setName("另一個分類");

        CategoryResponse response = CategoryResponse.builder()
                .uuid(specificUuid)
                .name("另一個分類")
                .slug("another")
                .sortOrder(0)
                .build();
        when(categoryService.updateCategory(eq(specificUuid), any(UpdateCategoryRequest.class))).thenReturn(response);

        mockMvc.perform(put("/api/v1/admin/categories/{uuid}", specificUuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(asAdmin()))
                .andExpect(status().isOk());

        verify(categoryService).updateCategory(eq(specificUuid), any(UpdateCategoryRequest.class));
    }

    // =========================================================================
    // DELETE /api/admin/categories/{uuid}
    // =========================================================================

    @Test
    @DisplayName("DELETE /{uuid} → 未認證 → 應回傳 401")
    void deleteCategory_unauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/categories/{uuid}", TEST_UUID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /{uuid} → 一般用戶 → 應回傳 403")
    void deleteCategory_asUser_shouldReturn403() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/categories/{uuid}", TEST_UUID)
                        .with(asUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /{uuid} → Admin → 應回傳 200 成功回應")
    void deleteCategory_asAdmin_shouldReturn200() throws Exception {
        doNothing().when(categoryService).deleteCategory(TEST_UUID);

        mockMvc.perform(delete("/api/v1/admin/categories/{uuid}", TEST_UUID)
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        verify(categoryService).deleteCategory(TEST_UUID);
    }

    @Test
    @DisplayName("DELETE /{uuid} → Admin 傳遞正確 UUID → 應傳入 service")
    void deleteCategory_asAdmin_shouldPassCorrectUuid() throws Exception {
        UUID specificUuid = UUID.fromString("33333333-3333-3333-3333-333333333333");
        doNothing().when(categoryService).deleteCategory(specificUuid);

        mockMvc.perform(delete("/api/v1/admin/categories/{uuid}", specificUuid)
                        .with(asAdmin()))
                .andExpect(status().isOk());

        verify(categoryService).deleteCategory(specificUuid);
    }
}
