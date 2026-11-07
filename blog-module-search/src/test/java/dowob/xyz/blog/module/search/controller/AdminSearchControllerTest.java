package dowob.xyz.blog.module.search.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import dowob.xyz.blog.common.api.enums.Permission;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.infrastructure.config.SecurityConfig;
import dowob.xyz.blog.infrastructure.security.AuthStateLoader;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.search.model.dto.response.SearchIndexStatusResponse;
import dowob.xyz.blog.module.search.service.SearchService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminSearchController 單元測試（Web 層）
 *
 * <p>
 * 使用 {@code @WebMvcTest} 僅載入 Web 層，透過 MockMvc 驗證管理員搜尋端點的
 * HTTP 請求處理行為，包含權限控制與回應結構。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest(AdminSearchController.class)
@Import(SecurityConfig.class)
@DisplayName("AdminSearchController 單元測試")
class AdminSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SearchService searchService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private UserAuthService userAuthService;

    /** Mock：認證狀態載入器（JwtAuthenticationFilter 所需） */
    @MockitoBean
    private AuthStateLoader authStateLoader;

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
    // POST /api/admin/search/reindex 測試
    // =========================================================================

    @Test
    @DisplayName("POST /api/admin/search/reindex — 未認證應回傳 401")
    void reindex_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/search/reindex"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/admin/search/reindex — 一般用戶應回傳 403")
    void reindex_asUser_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/search/reindex")
                        .with(asUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/admin/search/reindex — Admin 用戶應回傳 200 與成功訊息")
    void reindex_asAdmin_returns200WithMessage() throws Exception {
        mockMvc.perform(post("/api/v1/admin/search/reindex")
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.message").value("Elasticsearch 索引全量重建已完成"));

        verify(searchService).reindexAll();
    }

    // =========================================================================
    // GET /api/admin/search/status 測試
    // =========================================================================

    @Test
    @DisplayName("GET /api/admin/search/status — 一般用戶應回傳 403")
    void status_asUser_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/search/status")
                        .with(asUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/admin/search/status — Admin 用戶應回傳索引狀態結構")
    void status_asAdmin_returnsStatus() throws Exception {
        SearchIndexStatusResponse response = SearchIndexStatusResponse.builder()
                .documentCount(13L)
                .lastReindexAt("2026-07-20T21:30:00")
                .healthy(true)
                .build();
        when(searchService.getIndexStatus()).thenReturn(response);

        mockMvc.perform(get("/api/v1/admin/search/status")
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.documentCount").value(13))
                .andExpect(jsonPath("$.data.lastReindexAt").value("2026-07-20T21:30:00"))
                .andExpect(jsonPath("$.data.healthy").value(true));

        verify(searchService).getIndexStatus();
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
    @DisplayName("POST /reindex — ROLE_ADMIN 但缺 ADMIN_SEARCH → 403，且不重建索引")
    void reindex_adminWithoutSearchPermission_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/search/reindex")
                        .with(asAdminWithout(Permission.ADMIN_SEARCH)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(searchService);
    }

    @Test
    @DisplayName("GET /status — ROLE_ADMIN 但缺 ADMIN_SEARCH → 403，且不查詢狀態")
    void status_adminWithoutSearchPermission_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/search/status")
                        .with(asAdminWithout(Permission.ADMIN_SEARCH)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(searchService);
    }
}
