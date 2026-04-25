package dowob.xyz.blog.module.tag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.config.SecurityConfig;
import dowob.xyz.blog.infrastructure.security.JwtAuthenticationFilter;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.model.dto.UpdateTagRequest;
import dowob.xyz.blog.module.tag.service.TagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

    private static final UUID TAG_ID = UUID.randomUUID();

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
}
