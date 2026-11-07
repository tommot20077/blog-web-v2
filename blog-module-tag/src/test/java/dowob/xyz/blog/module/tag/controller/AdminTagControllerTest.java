package dowob.xyz.blog.module.tag.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import dowob.xyz.blog.common.api.enums.Permission;
import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.exception.GlobalExceptionHandler;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.infrastructure.config.SecurityConfig;
import dowob.xyz.blog.infrastructure.security.JwtAuthenticationFilter;
import dowob.xyz.blog.infrastructure.security.AuthStateLoader;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.model.dto.UpdateTagRequest;
import dowob.xyz.blog.module.tag.service.TagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminTagController 單元測試（Web 層）
 *
 * <p>
 * 使用 {@code @WebMvcTest} 僅載入 Web 層，透過 MockMvc 驗證管理員標籤端點的
 * HTTP 請求處理行為，包含權限控制與回應結構。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest(value = AdminTagController.class,
        excludeAutoConfiguration = EnableAutoConfiguration.class)
@ContextConfiguration(classes = {
        AdminTagController.class,
        GlobalExceptionHandler.class,
        SecurityConfig.class,
        JwtAuthenticationFilter.class
})
@DisplayName("AdminTagController 單元測試")
class AdminTagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TagService tagService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private UserAuthService userAuthService;

    /** Mock：認證狀態載入器（JwtAuthenticationFilter 所需） */
    @MockitoBean
    private AuthStateLoader authStateLoader;

    private static final UUID TAG_ID = UUID.randomUUID();

    private static RequestPostProcessor asAdmin() {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(Role.ADMIN.getSpringSecurityRole()));
        Role.ADMIN.getPermissions().forEach(p ->
                authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(new AuthUser(new UUID(0L, 1L), Role.ADMIN), null, authorities);
        return authentication(auth);
    }

    private static RequestPostProcessor asUser() {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(Role.USER.getSpringSecurityRole()));
        Role.USER.getPermissions().forEach(p ->
                authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(new AuthUser(new UUID(0L, 2L), Role.USER), null, authorities);
        return authentication(auth);
    }

    // =========================================================================
    // PUT /api/admin/tags/{id} 測試
    // =========================================================================

    @Test
    @DisplayName("PUT /api/admin/tags/{id} — 未認證應回傳 401")
    void updateTag_unauthenticated_returns401() throws Exception {
        UpdateTagRequest request = new UpdateTagRequest();
        request.setColor("#FF0000");

        mockMvc.perform(put("/api/v1/admin/tags/{id}", TAG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /api/admin/tags/{id} — 一般用戶應回傳 403")
    void updateTag_asUser_returns403() throws Exception {
        UpdateTagRequest request = new UpdateTagRequest();
        request.setColor("#FF0000");

        mockMvc.perform(put("/api/v1/admin/tags/{id}", TAG_ID)
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/admin/tags/{id} — Admin 用戶應回傳 200 與更新後標籤")
    void updateTag_asAdmin_returns200() throws Exception {
        UpdateTagRequest request = new UpdateTagRequest();
        request.setColor("#FF0000");
        request.setDescription("Updated description");

        Tag updatedTag = new Tag();
        updatedTag.setId(TAG_ID);
        updatedTag.setName("java");
        updatedTag.setSlug("java");
        updatedTag.setColor("#FF0000");
        updatedTag.setDescription("Updated description");

        when(tagService.adminUpdateTag(eq(TAG_ID), any(UpdateTagRequest.class)))
                .thenReturn(updatedTag);

        mockMvc.perform(put("/api/v1/admin/tags/{id}", TAG_ID)
                        .with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.id").value(TAG_ID.toString()))
                .andExpect(jsonPath("$.data.color").value("#FF0000"))
                .andExpect(jsonPath("$.data.description").value("Updated description"));

        verify(tagService).adminUpdateTag(eq(TAG_ID), any(UpdateTagRequest.class));
    }

    // =========================================================================
    // PUT /api/v1/admin/tags/{id} 輸入驗證（SEC-24）
    // =========================================================================

    @ParameterizedTest(name = "非法 color「{0}」→ 400")
    @ValueSource(strings = {"red", "#FFF", "#12345678", "#GGGGGG", "FF0000", "#FF0000;background:url(x)",
            "expression(alert(1))", "", " "})
    @DisplayName("PUT /api/v1/admin/tags/{id} — color 非 #RRGGBB 回傳 400 且不呼叫 service")
    void updateTag_invalidColor_returns400(String color) throws Exception {
        UpdateTagRequest request = new UpdateTagRequest();
        request.setColor(color);

        mockMvc.perform(put("/api/v1/admin/tags/{id}", TAG_ID)
                        .with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("color")));

        verify(tagService, never()).adminUpdateTag(any(), any());
    }

    @Test
    @DisplayName("PUT /api/v1/admin/tags/{id} — icon 超過 100 字（對齊 tags.icon VARCHAR(100)）回傳 400")
    void updateTag_iconTooLong_returns400() throws Exception {
        UpdateTagRequest request = new UpdateTagRequest();
        request.setIcon("i".repeat(101));

        mockMvc.perform(put("/api/v1/admin/tags/{id}", TAG_ID)
                        .with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("icon")));

        verify(tagService, never()).adminUpdateTag(any(), any());
    }

    @Test
    @DisplayName("PUT /api/v1/admin/tags/{id} — description 超過 200 字（沿用分類描述上限）回傳 400")
    void updateTag_descriptionTooLong_returns400() throws Exception {
        UpdateTagRequest request = new UpdateTagRequest();
        request.setDescription("d".repeat(201));

        mockMvc.perform(put("/api/v1/admin/tags/{id}", TAG_ID)
                        .with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("description")));

        verify(tagService, never()).adminUpdateTag(any(), any());
    }

    @Test
    @DisplayName("PUT /api/v1/admin/tags/{id} — 邊界合法值（#1a2B3c、icon 100 字、description 200 字）回傳 200")
    void updateTag_boundaryValidValues_returns200() throws Exception {
        UpdateTagRequest request = new UpdateTagRequest();
        request.setColor("#1a2B3c");
        request.setIcon("i".repeat(100));
        request.setDescription("d".repeat(200));
        when(tagService.adminUpdateTag(eq(TAG_ID), any(UpdateTagRequest.class))).thenReturn(new Tag());

        mockMvc.perform(put("/api/v1/admin/tags/{id}", TAG_ID)
                        .with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    @Test
    @DisplayName("PUT /api/v1/admin/tags/{id} — 欄位全為 null（不更新）維持合法")
    void updateTag_allNull_returns200() throws Exception {
        when(tagService.adminUpdateTag(eq(TAG_ID), any(UpdateTagRequest.class))).thenReturn(new Tag());

        mockMvc.perform(put("/api/v1/admin/tags/{id}", TAG_ID)
                        .with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // DELETE /api/admin/tags/{id} 測試
    // =========================================================================

    @Test
    @DisplayName("DELETE /api/admin/tags/{id} — 未認證應回傳 401")
    void deleteTag_unauthenticated_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/tags/{id}", TAG_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /api/admin/tags/{id} — 一般用戶應回傳 403")
    void deleteTag_asUser_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/tags/{id}", TAG_ID)
                        .with(asUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/admin/tags/{id} — Admin 用戶應回傳 200")
    void deleteTag_asAdmin_returns200() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/tags/{id}", TAG_ID)
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        verify(tagService).adminDeleteTag(TAG_ID);
    }

    // =========================================================================
    // A9：持有 ROLE_ADMIN、但缺少該端點對應 permission（手工構造 authorities）→ 方法層 403
    // =========================================================================

    /** ROLE_ADMIN + 除了 {@code missing} 之外的所有 permission：URL 層放行，方法層必須擋下。 */
    private static RequestPostProcessor asAdminWithout(Permission missing) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(Role.ADMIN.getSpringSecurityRole()));
        Role.ADMIN.getPermissions().stream().filter(p -> p != missing).forEach(p ->
                authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(new AuthUser(new UUID(0L, 1L), Role.ADMIN), null, authorities);
        return authentication(auth);
    }

    @Test
    @DisplayName("PUT /{id} — ROLE_ADMIN 但缺 ADMIN_TAG → 403，且不更新")
    void updateTag_adminWithoutTagPermission_returns403() throws Exception {
        UpdateTagRequest request = new UpdateTagRequest();
        request.setColor("#FF0000");

        mockMvc.perform(put("/api/v1/admin/tags/{id}", TAG_ID)
                        .with(asAdminWithout(Permission.ADMIN_TAG))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(tagService);
    }

    @Test
    @DisplayName("DELETE /{id} — ROLE_ADMIN 但缺 ADMIN_TAG → 403，且不刪除")
    void deleteTag_adminWithoutTagPermission_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/tags/{id}", TAG_ID)
                        .with(asAdminWithout(Permission.ADMIN_TAG)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(tagService);
    }
}
