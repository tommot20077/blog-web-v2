package dowob.xyz.blog.e2e.recommend;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.AuthHelper;
import dowob.xyz.blog.e2e.support.DataBuilder;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;

import static dowob.xyz.blog.e2e.support.AuthHelper.bearerToken;
import static dowob.xyz.blog.e2e.support.E2EAssertions.apiSuccess;
import static dowob.xyz.blog.e2e.support.E2EAssertions.hasData;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recommend E2E 測試
 *
 * <p>
 * 驗證推薦模組完整的 HTTP 流程，涵蓋相關文章推薦與熱門文章排行。
 * </p>
 */
@DisplayName("Recommend E2E 測試")
class RecommendE2E extends AbstractE2ETest {

    @Autowired
    private AuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    // ========== Helper Methods ==========

    /**
     * 建立文章並回傳 UUID
     */
    private String createArticleAndGetUuid(String token, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/articles")
                        .with(bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("uuid").asText();
    }

    /**
     * 建立帶標籤的文章並發布，回傳 UUID
     */
    private String createAndPublishArticleWithTags(String token, String title, String content,
                                                    List<String> tagNames) throws Exception {
        String uuid = createArticleAndGetUuid(token,
                DataBuilder.articleWithTags(title, content, tagNames));

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish")
                        .with(bearerToken(token)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());

        return uuid;
    }

    // ========== Test Scenarios ==========

    @Test
    @DisplayName("發布多篇同標籤文章後，取得相關文章推薦結果")
    void getRelatedArticles_returnsResults() throws Exception {
        String token = authHelper.createUserWithRole(
                "recommend-author@test.com", "Password1!", "recommendauthor", "RecommendAuthor", Role.AUTHOR);

        // 建立多篇同標籤文章
        String uuid1 = createAndPublishArticleWithTags(token,
                "Java 基礎教學", "Java 入門指南內容", List.of("Java", "Tutorial"));
        createAndPublishArticleWithTags(token,
                "Java 進階技巧", "Java 進階內容", List.of("Java", "Advanced"));
        createAndPublishArticleWithTags(token,
                "Java 設計模式", "設計模式應用於 Java", List.of("Java", "Design Pattern"));

        // 等待 ES 索引完成
        await().atMost(15, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).untilAsserted(() -> {
            Integer tagCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tags", Integer.class);
            assertThat(tagCount).isGreaterThanOrEqualTo(2);
        });

        // 取得第一篇文章的相關推薦
        mockMvc.perform(get("/api/v1/recommend/related/{articleUuid}", uuid1)
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());
    }

    @Test
    @DisplayName("取得熱門文章排行成功回傳（可能為空列表）")
    void getTrendingArticles_success() throws Exception {
        mockMvc.perform(get("/api/v1/recommend/trending")
                        .param("period", "7d")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(hasData());
    }

    @Test
    @DisplayName("不存在的文章 UUID 取得相關推薦回傳空列表")
    void getRelatedArticles_nonExistentArticle_returnsEmpty() throws Exception {
        UUID randomUuid = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/recommend/related/{articleUuid}", randomUuid))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("推薦 API 為公開端點，不需要認證即可存取")
    void publicAccess_noAuthRequired() throws Exception {
        // 相關推薦 — 無需 Bearer Token
        mockMvc.perform(get("/api/v1/recommend/related/{articleUuid}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());

        // 熱門排行 — 無需 Bearer Token
        mockMvc.perform(get("/api/v1/recommend/trending"))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());
    }
}
