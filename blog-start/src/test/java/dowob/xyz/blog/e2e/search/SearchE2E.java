package dowob.xyz.blog.e2e.search;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.AuthHelper;
import dowob.xyz.blog.e2e.support.DataBuilder;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import dowob.xyz.blog.e2e.support.SearchIndexCleaner;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.index.ArticleIndexAdmin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private SearchIndexCleaner searchIndexCleaner;

    @Autowired
    private ElasticsearchOperations esOps;

    @Autowired
    private ArticleIndexAdmin indexAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
        /* 讀寫改走 alias 後不可再 delete＋createWithMapping（會拆掉 alias），改為經 alias 清空文件 */
        searchIndexCleaner.clearArticles();
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

    /**
     * D1／PERF-01：重建改在背景執行。觸發立即回 202 ＋ jobId，輪詢 status 的 reindex 直到 COMPLETED，
     * 之後經 alias 仍搜得到已發布文章；alias 已指向新的實體索引、舊索引已刪除、殘留在舊索引的幽靈文件不會跟過去。
     */
    @Test
    @DisplayName("管理員觸發全量重建：立即 202 帶 jobId，輪詢 status 到 COMPLETED，alias 切到新索引後仍搜得到、幽靈文件消失")
    void adminReindex_runsInBackgroundUntilCompleted() throws Exception {
        String adminToken = authHelper.createUserWithRole(
                "search-admin@test.com", "Password1!", "searchadmin", "SearchAdmin", Role.ADMIN);
        String authorToken = authHelper.createUserWithRole(
                "search-reindex-author@test.com", "Password1!", "searchreindexauthor", "ReindexAuthor", Role.AUTHOR);
        String articleUuid = createAndPublishArticle(authorToken, "ReindexChainUnique 背景重建", "背景重建驗證用內文 reindexchainunique");
        await().atMost(15, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/search").param("q", "ReindexChainUnique"))
                        .andExpect(jsonPath("$.data.total").value(1)));
        String ghostUuid = java.util.UUID.randomUUID().toString();
        esOps.save(ArticleDocument.builder().id(ghostUuid).uuid(ghostUuid).title("ReindexChainUnique ghost")
                .slug("ghost-" + ghostUuid).status("PUBLISHED").build());
        esOps.indexOps(ArticleDocument.class).refresh();
        String physicalBefore = indexAdmin.inspect().aliasTargets().get(0);

        MvcResult accepted = mockMvc.perform(post("/api/v1/admin/search/reindex")
                        .with(bearerToken(adminToken)))
                .andExpect(status().isAccepted())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.state").value("RUNNING"))
                .andReturn();
        String jobId = objectMapper.readTree(accepted.getResponse().getContentAsString())
                .get("data").get("jobId").asText();

        await().atMost(60, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/admin/search/status").with(bearerToken(adminToken)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.reindex.jobId").value(jobId))
                        .andExpect(jsonPath("$.data.reindex.state").value("COMPLETED")));
        mockMvc.perform(get("/api/v1/admin/search/status").with(bearerToken(adminToken)))
                .andExpect(jsonPath("$.data.reindex.processed").value(1))
                .andExpect(jsonPath("$.data.reindex.total").value(1))
                .andExpect(jsonPath("$.data.reindex.finishedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.lastReindexAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/search").param("q", "ReindexChainUnique"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].articleUuid").value(articleUuid));
        String physicalAfter = indexAdmin.inspect().aliasTargets().get(0);
        org.assertj.core.api.Assertions.assertThat(physicalAfter).isNotEqualTo(physicalBefore);
        org.assertj.core.api.Assertions.assertThat(indexAdmin.indexExists(physicalBefore)).isFalse();
    }

    @Test
    @DisplayName("B4：搜尋結果的 viewCount／likeCount 與詳情頁一致（DB 值 + Redis 暫存增量／DB 按讚數），不是 ES 的 0")
    void search_counts_matchArticleDetail() throws Exception {
        String token = authHelper.createUserWithRole(
                "search-counts@test.com", "Password1!", "searchcounts", "SearchCounts", Role.AUTHOR);
        String uuid = createAndPublishArticle(token, "CountsChainUnique 計數同源", "計數同源驗證用文章內容 countschainunique");
        await().atMost(15, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/search").param("q", "CountsChainUnique"))
                        .andExpect(jsonPath("$.data.total").value(1)));
        jdbcTemplate.update("UPDATE articles SET view_count = 100, like_count = 7 WHERE uuid = ?::uuid", uuid);
        stringRedisTemplate.opsForValue().set("article:views:" + uuid, "5");

        mockMvc.perform(get("/api/v1/search").param("q", "CountsChainUnique"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].viewCount").value(105))
                .andExpect(jsonPath("$.data.records[0].likeCount").value(7));
        mockMvc.perform(get("/api/v1/articles/" + uuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.viewCount").value(105))
                .andExpect(jsonPath("$.data.likeCount").value(7));
    }

    @Test
    @DisplayName("B4：sort=hot 已移除，回 400 PARAM_VALID_ERROR（A0001）")
    void search_sortHot_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("q", "anything").param("sort", "hot"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0001"));
    }
}
