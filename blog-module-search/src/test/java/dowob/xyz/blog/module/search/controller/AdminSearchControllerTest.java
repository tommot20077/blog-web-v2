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
import dowob.xyz.blog.module.search.model.dto.response.SearchReindexJobResponse;
import dowob.xyz.blog.module.search.reindex.ReindexJobState;
import dowob.xyz.blog.module.search.reindex.ReindexStartResult;
import dowob.xyz.blog.module.search.reindex.SearchReindexService;
import java.time.LocalDateTime;
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

    /** 背景全量重建服務（D1：reindex 端點改為觸發背景作業） */
    @MockitoBean
    private SearchReindexService reindexService;

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
    @DisplayName("POST /api/admin/search/reindex — 一般用戶應回傳 403，且不觸發重建")
    void reindex_asUser_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/search/reindex")
                        .with(asUser()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reindexService);
    }

    /**
     * D1：重建改在背景執行，端點只負責觸發並立即回 202 ＋ 作業資訊（jobId 供輪詢 status），
     * 不再在 HTTP 執行緒上同步跑完（原本回 200「已完成」）。
     */
    @Test
    @DisplayName("POST /api/admin/search/reindex — Admin 觸發後立即回 202，回應帶 jobId 與 RUNNING 狀態")
    void reindex_asAdmin_returns202WithJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(reindexService.start()).thenReturn(ReindexStartResult.started(SearchReindexJobResponse.builder()
                .jobId(jobId).state(ReindexJobState.RUNNING).processed(0)
                .startedAt(LocalDateTime.of(2026, 11, 7, 12, 0)).build()));

        mockMvc.perform(post("/api/v1/admin/search/reindex")
                        .with(asAdmin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.data.state").value("RUNNING"))
                .andExpect(jsonPath("$.data.processed").value(0))
                .andExpect(jsonPath("$.data.startedAt").value("2026-11-07T12:00:00"));

        verify(reindexService).start();
        verifyNoInteractions(searchService);
    }

    @Test
    @DisplayName("POST /api/admin/search/reindex — 已有重建在跑時回 409（A0501），data 為進行中的作業")
    void reindex_whenAlreadyRunning_returns409WithRunningJob() throws Exception {
        UUID runningJob = UUID.randomUUID();
        when(reindexService.start()).thenReturn(ReindexStartResult.alreadyRunning(SearchReindexJobResponse.builder()
                .jobId(runningJob).state(ReindexJobState.RUNNING).processed(200).total(450L).build()));

        mockMvc.perform(post("/api/v1/admin/search/reindex")
                        .with(asAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("A0501"))
                .andExpect(jsonPath("$.data.jobId").value(runningJob.toString()))
                .andExpect(jsonPath("$.data.processed").value(200))
                .andExpect(jsonPath("$.data.total").value(450));
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
                .reindex(SearchReindexJobResponse.builder().jobId(UUID.randomUUID()).state(ReindexJobState.COMPLETED)
                        .processed(13).total(13L).startedAt(LocalDateTime.of(2026, 7, 20, 21, 29))
                        .finishedAt(LocalDateTime.of(2026, 7, 20, 21, 30)).build())
                .build();
        when(searchService.getIndexStatus()).thenReturn(response);

        mockMvc.perform(get("/api/v1/admin/search/status")
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.documentCount").value(13))
                .andExpect(jsonPath("$.data.lastReindexAt").value("2026-07-20T21:30:00"))
                .andExpect(jsonPath("$.data.healthy").value(true))
                .andExpect(jsonPath("$.data.reindex.state").value("COMPLETED"))
                .andExpect(jsonPath("$.data.reindex.processed").value(13))
                .andExpect(jsonPath("$.data.reindex.finishedAt").value("2026-07-20T21:30:00"));

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
        verifyNoInteractions(searchService, reindexService);
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
