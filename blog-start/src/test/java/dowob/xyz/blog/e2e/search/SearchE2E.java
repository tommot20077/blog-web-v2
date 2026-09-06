package dowob.xyz.blog.e2e.search;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.AuthHelper;
import dowob.xyz.blog.e2e.support.DataBuilder;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static dowob.xyz.blog.e2e.support.AuthHelper.bearerToken;
import static dowob.xyz.blog.e2e.support.E2EAssertions.apiSuccess;
import static dowob.xyz.blog.e2e.support.E2EAssertions.hasData;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Search E2E 測試
 *
 * <p>
 * 驗證搜尋模組完整的 HTTP 流程，涵蓋全文搜尋、搜尋建議、
 * 個人搜尋歷史管理，以及管理員 Elasticsearch 索引重建。
 * </p>
 */
@DisplayName("Search E2E 測試")
class SearchE2E extends AbstractE2ETest {

    @Autowired
    private AuthHelper authHelper;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private ElasticsearchOperations esOps;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
        // 清理 ES 索引，確保測試之間不互相干擾
        var indexOps = esOps.indexOps(ArticleDocument.class);
        if (indexOps.exists()) {
            indexOps.delete();
            indexOps.createWithMapping();
        }
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
     * 建立文章並發布，回傳 UUID
     */
    private String createAndPublishArticle(String token, String title, String content) throws Exception {
        String uuid = createArticleAndGetUuid(token, DataBuilder.article(title, content));

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish")
                        .with(bearerToken(token)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());

        return uuid;
    }

    // ========== Test Scenarios ==========

    @Test
    @DisplayName("發布文章後透過 ES 全文搜尋可找到該文章")
    void search_afterArticlePublished_findsArticle() throws Exception {
        String token = authHelper.createUserWithRole(
                "search-author@test.com", "Password1!", "searchauthor", "SearchAuthor", Role.AUTHOR);

        createAndPublishArticle(token, "Elasticsearch 整合測試文章", "這篇文章說明如何使用 Elasticsearch 進行全文檢索");

        // 等待 RabbitMQ consumer 完成 ES 索引
        await().atMost(15, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/search").param("q", "Elasticsearch"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.total").value(greaterThanOrEqualTo(1)))
        );
    }

    @Test
    @DisplayName("搜尋不存在的關鍵字回傳空結果集")
    void search_noResults_returnsEmptyPage() throws Exception {
        mockMvc.perform(get("/api/v1/search")
                        .param("q", "completely_nonexistent_keyword_xyz_12345"))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("已登入使用者搜尋後可取得搜尋歷史")
    void getHistory_asUser_success() throws Exception {
        String token = authHelper.createUserWithRole(
                "search-history@test.com", "Password1!", "searchhistory", "SearchHistory", Role.USER);

        // 執行一次搜尋以產生歷史紀錄
        mockMvc.perform(get("/api/v1/search")
                        .param("q", "test_history_keyword")
                        .with(bearerToken(token)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());

        // 取得搜尋歷史，驗證包含剛才的搜尋詞
        mockMvc.perform(get("/api/v1/search/history")
                        .with(bearerToken(token)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(hasData())
                .andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("已登入使用者清除搜尋歷史後回傳空列表")
    void clearHistory_asUser_success() throws Exception {
        String token = authHelper.createUserWithRole(
                "search-clear@test.com", "Password1!", "searchclear", "SearchClear", Role.USER);

        // 執行搜尋產生歷史
        mockMvc.perform(get("/api/v1/search")
                        .param("q", "clear_history_keyword")
                        .with(bearerToken(token)))
                .andExpect(status().isOk());

        // 清除歷史
        mockMvc.perform(delete("/api/v1/search/history")
                        .with(bearerToken(token)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());

        // 驗證歷史已清空
        mockMvc.perform(get("/api/v1/search/history")
                        .with(bearerToken(token)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("管理員觸發 ES 全量重建索引成功")
    void adminReindex_success() throws Exception {
        String adminToken = authHelper.createUserWithRole(
                "search-admin@test.com", "Password1!", "searchadmin", "SearchAdmin", Role.ADMIN);

        mockMvc.perform(post("/api/v1/admin/search/reindex")
                        .with(bearerToken(adminToken)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());
    }
}
