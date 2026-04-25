package dowob.xyz.blog.module.search.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.infrastructure.config.SecurityConfig;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.search.model.dto.response.SearchResultResponse;
import dowob.xyz.blog.module.search.service.SearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 搜尋 Controller 整合測試
 *
 * <p>
 * 使用 {@link WebMvcTest} 測試 HTTP 層，
 * 透過 {@link MockitoBean} 隔離 {@link SearchService} 業務邏輯。
 * 驗證公開端點可匿名存取、個人歷史端點需認證、Admin 重建端點需 ADMIN 角色。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest(controllers = {SearchController.class, AdminSearchController.class})
@Import(SecurityConfig.class)
@DisplayName("SearchController 整合測試")
class SearchControllerIT {

    /**
     * MockMvc，用於發送 HTTP 請求
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * 搜尋服務（Mock）
     */
    @MockitoBean
    private SearchService searchService;

    /**
     * JWT 服務（JwtAuthenticationFilter 依賴）
     */
    @MockitoBean
    private JwtService jwtService;

    /**
     * Redis 字串模板（JwtAuthenticationFilter 依賴）
     */
    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 用戶認證服務（JwtAuthenticationFilter 依賴）
     */
    @MockitoBean
    private UserAuthService userAuthService;

    /**
     * 建立一般使用者認證 Token
     *
     * @param userId 用戶 ID
     * @return RequestPostProcessor，可注入至 MockMvc 請求
     */
    private RequestPostProcessor userAuth(Long userId) {
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(
                        userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    /**
     * 建立管理員認證 Token
     *
     * @param userId 管理員用戶 ID
     * @return RequestPostProcessor，可注入至 MockMvc 請求
     */
    private RequestPostProcessor adminAuth(Long userId) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(Role.ADMIN.getSpringSecurityRole()));
        Role.ADMIN.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(userId, null, authorities));
    }

    /**
     * 全文搜尋 API 測試群組
     */
    @Nested
    @DisplayName("GET /api/v1/search — 全文搜尋")
    class SearchEndpointTests {

        /**
         * 公開端點，匿名使用者應可正常搜尋
         */
        @Test
        @DisplayName("匿名使用者應可呼叫公開搜尋 API（200 OK）")
        void search_anonymousUser_returns200() throws Exception {
            when(searchService.search(anyString(), any(), anyString(), anyInt(), anyInt(), isNull()))
                    .thenReturn(PageResult.of(1, 10, 0L, List.of()));

            mockMvc.perform(get("/api/v1/search").param("q", "spring"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("00000"));
        }

        /**
         * 登入使用者搜尋時，Controller 應傳入 userId
         */
        @Test
        @DisplayName("已登入使用者搜尋，應傳入 userId 至 SearchService")
        void search_authenticatedUser_passesUserIdToService() throws Exception {
            when(searchService.search(anyString(), any(), anyString(), anyInt(), anyInt(), eq(1L)))
                    .thenReturn(PageResult.of(1, 10, 0L, List.of()));

            mockMvc.perform(get("/api/v1/search")
                            .param("q", "spring")
                            .with(userAuth(1L)))
                    .andExpect(status().isOk());

            verify(searchService).search(anyString(), any(), anyString(), anyInt(), anyInt(), eq(1L));
        }

        /**
         * 無關鍵字時，應仍回傳 200 OK（空結果）
         */
        @Test
        @DisplayName("無關鍵字時，應回傳 200 OK")
        void search_withoutKeyword_returns200() throws Exception {
            when(searchService.search(isNull(), any(), anyString(), anyInt(), anyInt(), isNull()))
                    .thenReturn(PageResult.of(1, 10, 0L, List.of()));

            mockMvc.perform(get("/api/v1/search"))
                    .andExpect(status().isOk());
        }
    }

    /**
     * 搜尋建議 API 測試群組
     */
    @Nested
    @DisplayName("GET /api/v1/search/suggest — 搜尋建議")
    class SuggestEndpointTests {

        /**
         * 公開端點，匿名使用者應可取得建議
         */
        @Test
        @DisplayName("匿名使用者應可取得搜尋建議（200 OK）")
        void suggest_anonymousUser_returns200() throws Exception {
            when(searchService.suggest("java")).thenReturn(List.of("java基礎", "javascript"));

            mockMvc.perform(get("/api/v1/search/suggest").param("q", "java"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0]").value("java基礎"))
                    .andExpect(jsonPath("$.data[1]").value("javascript"));
        }

        /**
         * 無前綴時，應回傳空列表
         */
        @Test
        @DisplayName("無前綴時，應回傳空建議列表")
        void suggest_withoutPrefix_returnsEmpty() throws Exception {
            when(searchService.suggest("")).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/search/suggest"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    /**
     * 搜尋歷史 GET API 測試群組
     */
    @Nested
    @DisplayName("GET /api/v1/search/history — 個人搜尋歷史")
    class GetHistoryEndpointTests {

        /**
         * 匿名使用者存取歷史 API，應回傳 401
         */
        @Test
        @DisplayName("匿名使用者取得歷史應回傳 401")
        void getHistory_anonymousUser_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/search/history"))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * 已登入使用者應可取得個人歷史
         */
        @Test
        @DisplayName("已登入使用者應可取得個人搜尋歷史（200 OK）")
        void getHistory_authenticatedUser_returns200() throws Exception {
            when(searchService.getHistory(1L)).thenReturn(List.of("spring boot", "java"));

            mockMvc.perform(get("/api/v1/search/history").with(userAuth(1L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0]").value("spring boot"));
        }
    }

    /**
     * 搜尋歷史 DELETE API 測試群組
     */
    @Nested
    @DisplayName("DELETE /api/v1/search/history — 清除搜尋歷史")
    class ClearHistoryEndpointTests {

        /**
         * 匿名使用者存取清除歷史 API，應回傳 401
         */
        @Test
        @DisplayName("匿名使用者清除歷史應回傳 401")
        void clearHistory_anonymousUser_returns401() throws Exception {
            mockMvc.perform(delete("/api/v1/search/history"))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * 已登入使用者應可清除個人歷史
         */
        @Test
        @DisplayName("已登入使用者應可清除個人搜尋歷史（200 OK）")
        void clearHistory_authenticatedUser_returns200() throws Exception {
            mockMvc.perform(delete("/api/v1/search/history").with(userAuth(1L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("00000"));

            verify(searchService).clearHistory(1L);
        }
    }

    /**
     * 管理員重建索引 API 測試群組
     */
    @Nested
    @DisplayName("POST /api/admin/search/reindex — 全量重建索引")
    class ReindexEndpointTests {

        /**
         * 匿名使用者存取 reindex API，應回傳 401
         */
        @Test
        @DisplayName("匿名使用者重建索引應回傳 401")
        void reindex_anonymousUser_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/admin/search/reindex"))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * 一般使用者存取 reindex API，應回傳 403
         */
        @Test
        @DisplayName("一般使用者重建索引應回傳 403")
        void reindex_regularUser_returns403() throws Exception {
            mockMvc.perform(post("/api/v1/admin/search/reindex").with(userAuth(1L)))
                    .andExpect(status().isForbidden());
        }

        /**
         * ADMIN 使用者應可觸發全量重建索引
         */
        @Test
        @DisplayName("ADMIN 使用者應可成功觸發重建索引（200 OK）")
        void reindex_adminUser_returns200() throws Exception {
            mockMvc.perform(post("/api/v1/admin/search/reindex").with(adminAuth(1L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("00000"));

            verify(searchService).reindexAll();
        }
    }
}
