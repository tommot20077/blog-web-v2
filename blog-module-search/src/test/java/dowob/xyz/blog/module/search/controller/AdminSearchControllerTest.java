package dowob.xyz.blog.module.search.controller;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.config.SecurityConfig;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.search.service.SearchService;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
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
    // POST /api/admin/search/reindex 測試
    // =========================================================================

    @Test
    @DisplayName("POST /api/admin/search/reindex — 未認證應回傳 401")
    void reindex_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/search/reindex"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/admin/search/reindex — 一般用戶應回傳 403")
    void reindex_asUser_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/search/reindex")
                        .with(asUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/admin/search/reindex — Admin 用戶應回傳 200 與成功訊息")
    void reindex_asAdmin_returns200WithMessage() throws Exception {
        mockMvc.perform(post("/api/admin/search/reindex")
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.message").value("Elasticsearch 索引全量重建已完成"));

        verify(searchService).reindexAll();
    }
}
