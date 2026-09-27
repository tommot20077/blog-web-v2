package dowob.xyz.blog.module.article.controller;

import dowob.xyz.blog.common.api.request.PageQuery;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.infrastructure.config.SecurityConfig;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.config.ArticleWebTestConfiguration;
import dowob.xyz.blog.module.article.model.dto.request.ArticleListQuery;
import dowob.xyz.blog.module.article.service.ArticleQueryService;
import dowob.xyz.blog.module.article.service.ArticleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ArticleController 公開列表端點單元測試（Web 層）
 *
 * <p>驗證 {@code GET /api/v1/articles} 的 query string 經真實的 {@code SecurityConfig}
 * 與 Spring MVC 綁定後，以正規化的 {@link ArticleListQuery} 與分頁參數交給 service；
 * 以及既有的單值 {@code categorySlug} 呼叫方式仍然有效（向下相容）。
 * SQL 語意（AND／OR、排序、tie-breaker 確定性）不在此驗證，見 {@code ArticleControllerIT}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest(ArticleController.class)
@ContextConfiguration(classes = ArticleWebTestConfiguration.class)
@Import(SecurityConfig.class)
@DisplayName("ArticleController 公開列表端點單元測試")
class ArticleControllerListTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ArticleService articleService;

    @MockitoBean
    private ArticleQueryService articleQueryService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private UserAuthService userAuthService;

    @BeforeEach
    void setUp() {
        when(articleQueryService.getPublishedArticles(any(ArticleListQuery.class), anyInt(), anyInt()))
                .thenReturn(PageResult.of(1, 10, 0L, List.of()));
    }

    @Test
    @DisplayName("匿名、不帶參數 → 以「不篩選」查詢與端點預設分頁（1, 10）交給 service")
    void getPublishedArticles_anonymousWithoutParams_passesUnfilteredQueryAndDefaultPaging() throws Exception {
        mockMvc.perform(get("/api/v1/articles"))
                .andExpect(status().isOk());

        verify(articleQueryService).getPublishedArticles(ArticleListQuery.unfiltered(), 1, 10);
    }

    @Test
    @DisplayName("帶齊篩選參數 → service 收到正規化後的查詢與指定分頁")
    void getPublishedArticles_withFilters_passesNormalizedQuery() throws Exception {
        UUID author = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/articles")
                        .param("tags", "Java,spring")
                        .param("categorySlug", "tech")
                        .param("authorUuids", author.toString())
                        .param("publishedWithinDays", "30")
                        .param("sort", "popular")
                        .param("page", "2")
                        .param("size", "12"))
                .andExpect(status().isOk());

        ArticleListQuery expected = new ArticleListQuery(
                List.of("java", "spring"), List.of("tech"), List.of(author), 30, "popular");
        verify(articleQueryService).getPublishedArticles(expected, 2, 12);
    }

    @Test
    @DisplayName("既有的單值 categorySlug 呼叫方式仍以分類篩選處理（向下相容）")
    void getPublishedArticles_legacyCategorySlug_stillAppliedAsFilter() throws Exception {
        mockMvc.perform(get("/api/v1/articles").param("categorySlug", "tech"))
                .andExpect(status().isOk());

        ArticleListQuery expected = new ArticleListQuery(null, List.of("tech"), null, null, null);
        verify(articleQueryService).getPublishedArticles(expected, 1, 10);
    }

    @Test
    @DisplayName("size 超過上限 → 400（PageQueryArgumentResolver 經元件掃描註冊於真實 context），且不觸發查詢")
    void getPublishedArticles_sizeAboveMax_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/articles").param("size", String.valueOf(PageQuery.MAX_SIZE + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0001"));

        verifyNoInteractions(articleQueryService);
    }

    @Test
    @DisplayName("page=0 → 400，且不觸發查詢")
    void getPublishedArticles_pageZero_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/articles").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0001"));

        verifyNoInteractions(articleQueryService);
    }

    @Test
    @DisplayName("authorUuids 格式錯誤 → 400，且不觸發查詢")
    void getPublishedArticles_malformedAuthorUuid_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/articles").param("authorUuids", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(articleQueryService);
    }
}
