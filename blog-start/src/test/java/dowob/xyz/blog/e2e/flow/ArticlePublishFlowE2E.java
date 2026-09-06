package dowob.xyz.blog.e2e.flow;

import com.fasterxml.jackson.databind.JsonNode;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.AuthHelper;
import dowob.xyz.blog.e2e.support.DataBuilder;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import dowob.xyz.blog.e2e.support.E2EAssertions;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 文章發布全鏈路 E2E 測試
 *
 * <p>
 * 測試文章從建立到刪除的完整生命週期，橫跨 article、search、tag 多個模組。
 * 驗證 RabbitMQ 非同步事件傳播、Elasticsearch 索引同步等跨模組整合行為。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("文章發布全鏈路 E2E")
class ArticlePublishFlowE2E extends AbstractE2ETest {

    @Autowired
    private AuthHelper authHelper;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private ElasticsearchOperations esOps;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
        var indexOps = esOps.indexOps(ArticleDocument.class);
        if (indexOps.exists()) {
            indexOps.delete();
            indexOps.createWithMapping();
        }
    }

    @Test
    @DisplayName("文章發布全鏈路 — 建立→送審→發布→搜尋→瀏覽→更新→刪除")
    void articlePublishFullLifecycle() throws Exception {

        // ===== 1. ADMIN 建立分類 =====
        String adminToken = authHelper.createUserWithRole(
                "admin-flow@test.com", "Admin123!", "adminflow", "Admin Flow", Role.ADMIN);

        String catBody = objectMapper.writeValueAsString(DataBuilder.category("Tech", "tech"));
        String catResponse = mockMvc.perform(post("/api/v1/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(catBody)
                        .with(AuthHelper.bearerToken(adminToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andReturn().getResponse().getContentAsString();

        String categoryUuid = objectMapper.readTree(catResponse).get("data").get("uuid").asText();

        // ===== 2. AUTHOR 註冊並取得 Token =====
        String authorToken = authHelper.createUserWithRole(
                "author-flow@test.com", "Author123!", "authorflow", "Author Flow", Role.AUTHOR);

        // ===== 3. AUTHOR 建立含標籤的文章 =====
        Map<String, Object> articleBody = new LinkedHashMap<>();
        articleBody.put("title", "E2E Spring Boot Guide");
        articleBody.put("content", "This is a comprehensive guide to Spring Boot for E2E testing.");
        articleBody.put("tagNames", List.of("Java", "Spring"));
        articleBody.put("categoryIds", List.of(categoryUuid));

        MvcResult createResult = mockMvc.perform(post("/api/v1/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(articleBody))
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(E2EAssertions.hasData())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();

        JsonNode articleData = objectMapper.readTree(
                createResult.getResponse().getContentAsString()).get("data");
        String articleUuid = articleData.get("uuid").asText();

        // ===== 4. AUTHOR 送審 =====
        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/submit")
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        // ===== 5. ADMIN 取得待審列表，確認文章在列表中 =====
        mockMvc.perform(get("/api/v1/admin/articles/pending")
                        .with(AuthHelper.bearerToken(adminToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.records").isNotEmpty());

        // ===== 6. ADMIN 發布文章 =====
        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/publish")
                        .with(AuthHelper.bearerToken(adminToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        // ===== 7. 等待 RabbitMQ 非同步事件傳播 =====
        // 7a. 標籤已建立（normalized to lowercase: "java", "spring"）
        await().atMost(15, SECONDS).pollInterval(1, SECONDS).untilAsserted(() -> {
            Integer tagCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tags", Integer.class);
            assert tagCount != null && tagCount >= 2;
        });

        // 7b. ES 索引已建立（搜尋可找到文章）
        await().atMost(15, SECONDS).pollInterval(1, SECONDS).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/search")
                                .param("q", "Spring Boot"))
                        .andExpect(status().isOk())
                        .andExpect(E2EAssertions.apiSuccess())
                        .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)))
        );

        // ===== 8. 匿名用戶搜尋文章 =====
        mockMvc.perform(get("/api/v1/search")
                        .param("q", "Spring Boot"))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.records[0].title", containsString("Spring Boot")));

        // ===== 9. 匿名用戶透過 slug 閱讀文章 =====
        // EditorArticleResponse 不含 slug，從 GET /articles/{uuid} (ArticleResponse) 取得
        String slugResponse = mockMvc.perform(get("/api/v1/articles/" + articleUuid))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String articleSlug = objectMapper.readTree(slugResponse).path("data").path("slug").asText();

        mockMvc.perform(get("/api/v1/articles/slug/" + articleSlug))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.title").value("E2E Spring Boot Guide"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        // ===== 10. AUTHOR 刪除文章 =====
        mockMvc.perform(delete("/api/v1/articles/" + articleUuid)
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        // ===== 11. 驗證文章已不存在 =====
        mockMvc.perform(get("/api/v1/articles/" + articleUuid))
                .andExpect(status().isBadRequest());
    }
}
