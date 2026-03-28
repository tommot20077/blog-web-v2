package dowob.xyz.blog.module.article.controller;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.infrastructure.config.SecurityConfig;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.config.ArticleWebTestConfiguration;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.service.ArticleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * AdminArticleController 單元測試（Web 層）
 *
 * <p>
 * 使用 {@code @WebMvcTest} 僅載入 Web 層，驗證管理員文章端點的
 * HTTP 請求處理行為，包含權限控制、分頁參數與回應結構。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest(AdminArticleController.class)
@ContextConfiguration(classes = ArticleWebTestConfiguration.class)
@Import(SecurityConfig.class)
@DisplayName("AdminArticleController 單元測試")
class AdminArticleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ArticleService articleService;

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
    // GET /api/admin/articles/pending
    // =========================================================================

    @Test
    @DisplayName("GET /pending → 未認證 → 應回傳 401")
    void getPendingArticles_unauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/admin/articles/pending"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /pending → 一般用戶 → 應回傳 403")
    void getPendingArticles_asUser_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/admin/articles/pending")
                        .with(asUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /pending → Admin → 應回傳 200 與分頁資料")
    void getPendingArticles_asAdmin_shouldReturn200WithPageResult() throws Exception {
        ArticleSummaryResponse article = ArticleSummaryResponse.builder()
                .uuid(UUID.randomUUID())
                .title("測試文章")
                .status(ArticleStatus.PENDING_REVIEW)
                .createdAt(LocalDateTime.now())
                .build();
        PageResult<ArticleSummaryResponse> pageResult = PageResult.of(1, 10, 1L, List.of(article));
        when(articleService.getPendingArticles(1, 10)).thenReturn(pageResult);

        mockMvc.perform(get("/api/admin/articles/pending")
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].title").value("測試文章"));

        verify(articleService).getPendingArticles(1, 10);
    }

    @Test
    @DisplayName("GET /pending → Admin 自訂分頁參數 → 應傳遞正確參數")
    void getPendingArticles_asAdminWithCustomPage_shouldPassCorrectParams() throws Exception {
        PageResult<ArticleSummaryResponse> pageResult = PageResult.of(2, 5, 0L, List.of());
        when(articleService.getPendingArticles(2, 5)).thenReturn(pageResult);

        mockMvc.perform(get("/api/admin/articles/pending")
                        .param("pageNum", "2")
                        .param("pageSize", "5")
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageNum").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(5));

        verify(articleService).getPendingArticles(2, 5);
    }

    @Test
    @DisplayName("GET /pending → Admin 不帶分頁參數 → 應使用預設值 pageNum=1, pageSize=10")
    void getPendingArticles_asAdminDefaultParams_shouldUseDefaults() throws Exception {
        PageResult<ArticleSummaryResponse> pageResult = PageResult.of(1, 10, 0L, List.of());
        when(articleService.getPendingArticles(1, 10)).thenReturn(pageResult);

        mockMvc.perform(get("/api/admin/articles/pending")
                        .with(asAdmin()))
                .andExpect(status().isOk());

        verify(articleService).getPendingArticles(1, 10);
    }

    // =========================================================================
    // GET /api/admin/articles/pending/count
    // =========================================================================

    @Test
    @DisplayName("GET /pending/count → 未認證 → 應回傳 401")
    void getPendingArticleCount_unauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/admin/articles/pending/count"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /pending/count → 一般用戶 → 應回傳 403")
    void getPendingArticleCount_asUser_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/admin/articles/pending/count")
                        .with(asUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /pending/count → Admin → 應回傳 200 與正確數量")
    void getPendingArticleCount_asAdmin_shouldReturn200WithCount() throws Exception {
        when(articleService.getPendingArticleCount()).thenReturn(42L);

        mockMvc.perform(get("/api/admin/articles/pending/count")
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data").value(42));

        verify(articleService).getPendingArticleCount();
    }
}
