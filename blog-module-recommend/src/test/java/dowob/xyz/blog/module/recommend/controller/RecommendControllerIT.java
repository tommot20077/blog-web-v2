package dowob.xyz.blog.module.recommend.controller;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.SearchFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryInfo;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.recommend.config.RecommendTestApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 推薦 Controller 整合測試
 *
 * <p>
 * 使用 Redis Testcontainer 測試完整 API 流程，
 * ArticleFacade 與 SearchFacade 以 MockitoBean 替代。
 * 驗證推薦 API 公開存取（無需認證）與回應格式。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = RecommendTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("RecommendController 整合測試")
class RecommendControllerIT {

    /**
     * Redis TestContainer
     */
    @Container
    static com.redis.testcontainers.RedisContainer redis =
            new com.redis.testcontainers.RedisContainer("redis:7-alpine");

    /**
     * 動態注入容器連線設定
     *
     * @param registry Spring 動態屬性源
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", redis::getRedisURI);
    }

    @Autowired
    private MockMvc mockMvc;

    /**
     * Mock ArticleFacade（推薦服務依賴）
     */
    @MockitoBean
    private ArticleFacade articleFacade;

    /**
     * Mock SearchFacade（推薦服務依賴）
     */
    @MockitoBean
    private SearchFacade searchFacade;

    /**
     * Mock JwtService（JWT 認證過濾器依賴）
     */
    @MockitoBean
    private JwtService jwtService;

    /**
     * Mock UserAuthService（JWT 認證過濾器依賴）
     */
    @MockitoBean
    private UserAuthService userAuthService;

    /**
     * Mock RabbitMQ ConnectionFactory（避免啟動時找不到 Bean）
     */
    @MockitoBean
    private ConnectionFactory connectionFactory;

    /**
     * Mock RabbitTemplate（避免 IT 真正發送 MQ 訊息）
     */
    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    private ArticleSummaryInfo summaryInfo(UUID uuid, String title) {
        return new ArticleSummaryInfo(uuid, title, "slug-" + title,
                "summary", "Author", List.of("Java"), 100L, 10L, LocalDateTime.now());
    }

    @Test
    @DisplayName("GET /api/v1/recommend/related/{uuid} - 無需認證，回傳推薦文章列表")
    void getRelatedArticles_公開存取_回傳推薦列表() throws Exception {
        UUID targetUuid = UUID.randomUUID();
        UUID recommendedUuid = UUID.randomUUID();

        when(articleFacade.getPublishedArticleBasicInfo(targetUuid))
                .thenReturn(Optional.of(new dowob.xyz.blog.infrastructure.facade.dto.ArticleBasicInfo(
                        targetUuid, List.of(UUID.randomUUID()))));
        when(articleFacade.getArticlesByTagIds(anyList(), any(), anyInt()))
                .thenReturn(List.of(summaryInfo(recommendedUuid, "Related Article")));
        when(searchFacade.findSimilarArticles(any(), anyInt()))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/recommend/related/{uuid}", targetUuid)
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].uuid").value(recommendedUuid.toString()))
                .andExpect(jsonPath("$.data[0].title").value("Related Article"));
    }

    @Test
    @DisplayName("GET /api/v1/recommend/related/{uuid} - 文章不存在時回傳空列表")
    void getRelatedArticles_文章不存在_回傳空列表() throws Exception {
        UUID targetUuid = UUID.randomUUID();

        when(articleFacade.getPublishedArticleBasicInfo(targetUuid))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/recommend/related/{uuid}", targetUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/recommend/trending - 無需認證，ZSet 空時回傳空列表")
    void getTrendingArticles_ZSet為空_回傳空列表() throws Exception {
        mockMvc.perform(get("/api/v1/recommend/trending")
                        .param("period", "7d")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
