package dowob.xyz.blog.e2e.article;

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
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static dowob.xyz.blog.e2e.support.AuthHelper.bearerToken;
import static dowob.xyz.blog.e2e.support.E2EAssertions.apiSuccess;
import static dowob.xyz.blog.e2e.support.E2EAssertions.hasData;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Article E2E 測試
 *
 * <p>
 * 驗證文章模組完整的 HTTP 流程，涵蓋 CRUD、狀態機轉換與權限控制。
 * </p>
 */
@DisplayName("Article E2E 測試")
class ArticleE2E extends AbstractE2ETest {

    @Autowired
    private AuthHelper authHelper;

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

    // ========== Test Scenarios ==========

    @Test
    @DisplayName("作者建立文章成功，預設為 DRAFT 狀態")
    void createArticle_asAuthor_success() throws Exception {
        String token = authHelper.createUserWithRole(
                "author@test.com", "Password1!", "author1", "Author", Role.AUTHOR);

        Map<String, Object> body = DataBuilder.article("E2E 測試文章", "這是一篇測試文章的內容");

        mockMvc.perform(post("/api/v1/articles")
                        .with(bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(hasData())
                .andExpect(jsonPath("$.data.uuid").exists())
                .andExpect(jsonPath("$.data.title").value("E2E 測試文章"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    @DisplayName("發布後可透過 slug 公開取得文章")
    void getArticleBySlug_afterPublish_success() throws Exception {
        String token = authHelper.createUserWithRole(
                "author@test.com", "Password1!", "author1", "Author", Role.AUTHOR);

        Map<String, Object> body = DataBuilder.article("Slug 測試文章", "文章內容");
        String uuid = createArticleAndGetUuid(token, body);

        // DRAFT → PUBLISHED
        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish")
                        .with(bearerToken(token)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());

        // 取得 slug
        MvcResult getResult = mockMvc.perform(get("/api/v1/articles/" + uuid))
                .andExpect(status().isOk())
                .andReturn();
        String slug = objectMapper.readTree(getResult.getResponse().getContentAsString())
                .get("data").get("slug").asText();

        // 匿名使用者透過 slug 取得文章
        mockMvc.perform(get("/api/v1/articles/slug/" + slug))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.title").value("Slug 測試文章"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    @Test
    @DisplayName("作者更新自己的文章成功")
    void updateArticle_asOwner_success() throws Exception {
        String token = authHelper.createUserWithRole(
                "author@test.com", "Password1!", "author1", "Author", Role.AUTHOR);

        Map<String, Object> body = DataBuilder.article("原始標題", "原始內容");
        String uuid = createArticleAndGetUuid(token, body);

        Map<String, Object> updateBody = new LinkedHashMap<>();
        updateBody.put("title", "更新後的標題");

        mockMvc.perform(put("/api/v1/articles/" + uuid)
                        .with(bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.title").value("更新後的標題"));
    }

    @Test
    @DisplayName("作者刪除自己的文章成功，再查詢回傳 404")
    void deleteArticle_asOwner_success() throws Exception {
        String token = authHelper.createUserWithRole(
                "author@test.com", "Password1!", "author1", "Author", Role.AUTHOR);

        Map<String, Object> body = DataBuilder.article("待刪除文章", "內容");
        String uuid = createArticleAndGetUuid(token, body);

        // 刪除
        mockMvc.perform(delete("/api/v1/articles/" + uuid)
                        .with(bearerToken(token)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());

        // 再查詢應回傳錯誤（文章不存在）
        mockMvc.perform(get("/api/v1/articles/" + uuid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("00000")));
    }

    @Test
    @DisplayName("取得我的文章列表成功")
    void getMyArticles_success() throws Exception {
        String token = authHelper.createUserWithRole(
                "author@test.com", "Password1!", "author1", "Author", Role.AUTHOR);

        // 建立兩篇文章
        createArticleAndGetUuid(token, DataBuilder.article("我的文章一", "內容一"));
        createArticleAndGetUuid(token, DataBuilder.article("我的文章二", "內容二"));

        mockMvc.perform(get("/api/v1/articles/me")
                        .with(bearerToken(token))
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(hasData())
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    @DisplayName("提交審核成功，DRAFT 轉為 PENDING_REVIEW")
    void submitForReview_success() throws Exception {
        String token = authHelper.createUserWithRole(
                "author@test.com", "Password1!", "author1", "Author", Role.AUTHOR);

        Map<String, Object> body = DataBuilder.article("審核文章", "內容");
        String uuid = createArticleAndGetUuid(token, body);

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/submit")
                        .with(bearerToken(token)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
    }

    @Test
    @DisplayName("管理員發布待審文章成功，PENDING_REVIEW 轉為 PUBLISHED")
    void adminPublishPendingArticle_success() throws Exception {
        // 作者建立並提交審核
        String authorToken = authHelper.createUserWithRole(
                "author@test.com", "Password1!", "author1", "Author", Role.AUTHOR);

        Map<String, Object> body = DataBuilder.article("待審文章", "內容");
        String uuid = createArticleAndGetUuid(authorToken, body);

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/submit")
                        .with(bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());

        // 管理員發布
        String adminToken = authHelper.createUserWithRole(
                "admin@test.com", "Password1!", "admin1", "Admin", Role.ADMIN);

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish")
                        .with(bearerToken(adminToken)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    @Test
    @DisplayName("管理員駁回待審文章成功，PENDING_REVIEW 轉為 REJECTED")
    void adminRejectArticle_success() throws Exception {
        // 作者建立並提交審核
        String authorToken = authHelper.createUserWithRole(
                "author@test.com", "Password1!", "author1", "Author", Role.AUTHOR);

        Map<String, Object> body = DataBuilder.article("將被駁回的文章", "內容");
        String uuid = createArticleAndGetUuid(authorToken, body);

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/submit")
                        .with(bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());

        // 管理員駁回
        String adminToken = authHelper.createUserWithRole(
                "admin@test.com", "Password1!", "admin1", "Admin", Role.ADMIN);

        Map<String, Object> rejectBody = new LinkedHashMap<>();
        rejectBody.put("reason", "內容品質不符標準");

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/reject")
                        .with(bearerToken(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rejectBody)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectReason").value("內容品質不符標準"));
    }

    @Test
    @DisplayName("管理員取得待審文章列表成功")
    void getPendingArticles_asAdmin_success() throws Exception {
        // 作者建立並提交審核
        String authorToken = authHelper.createUserWithRole(
                "author@test.com", "Password1!", "author1", "Author", Role.AUTHOR);

        String uuid = createArticleAndGetUuid(authorToken, DataBuilder.article("待審文章", "內容"));
        mockMvc.perform(post("/api/v1/articles/" + uuid + "/submit")
                        .with(bearerToken(authorToken)))
                .andExpect(status().isOk());

        // 管理員查詢待審列表
        String adminToken = authHelper.createUserWithRole(
                "admin@test.com", "Password1!", "admin1", "Admin", Role.ADMIN);

        mockMvc.perform(get("/api/admin/articles/pending")
                        .with(bearerToken(adminToken))
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(hasData())
                .andExpect(jsonPath("$.data.total").value(1));

        // 同時驗證待審數量端點
        mockMvc.perform(get("/api/admin/articles/pending/count")
                        .with(bearerToken(adminToken)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data").value(1));
    }

    @Test
    @DisplayName("一般使用者建立文章被拒絕，回傳 403")
    void createArticle_asUser_forbidden() throws Exception {
        String token = authHelper.createUserWithRole(
                "user@test.com", "Password1!", "user1", "User", Role.USER);

        Map<String, Object> body = DataBuilder.article("不該成功的文章", "內容");

        mockMvc.perform(post("/api/v1/articles")
                        .with(bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("匿名使用者可列出已發布文章")
    void listPublishedArticles_public_success() throws Exception {
        // 作者建立並發布文章
        String token = authHelper.createUserWithRole(
                "author@test.com", "Password1!", "author1", "Author", Role.AUTHOR);

        String uuid1 = createArticleAndGetUuid(token, DataBuilder.article("公開文章一", "內容一"));
        String uuid2 = createArticleAndGetUuid(token, DataBuilder.article("公開文章二", "內容二"));

        // 發布兩篇
        mockMvc.perform(post("/api/v1/articles/" + uuid1 + "/publish")
                        .with(bearerToken(token)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/articles/" + uuid2 + "/publish")
                        .with(bearerToken(token)))
                .andExpect(status().isOk());

        // 匿名查詢
        mockMvc.perform(get("/api/v1/articles")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(hasData())
                .andExpect(jsonPath("$.data.total").value(2));
    }
}
