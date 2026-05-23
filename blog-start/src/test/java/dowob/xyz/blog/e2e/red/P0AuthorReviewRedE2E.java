package dowob.xyz.blog.e2e.red;

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
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("P0 紅燈 - Author 審核流程")
class P0AuthorReviewRedE2E extends AbstractE2ETest {

    @Autowired
    private AuthHelper authHelper;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private ElasticsearchOperations esOps;

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
    @DisplayName("管理員發布作者送審文章後公開列表與搜尋都應看得到")
    void adminPublishAuthorArticleShouldAppearInPublicListAndSearch() throws Exception {
        String adminToken = authHelper.createUserWithRole(
                "p0-author-review-admin@test.local", "Admin123!", "p0reviewadmin", "P0 Review Admin", Role.ADMIN);
        String authorToken = authHelper.createUserWithRole(
                "p0-author-review-author@test.local", "Author123!", "p0reviewauthor", "P0 Review Author", Role.AUTHOR);

        MvcResult categoryResult = mockMvc.perform(post("/api/v1/admin/categories")
                        .with(AuthHelper.bearerToken(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DataBuilder.category(
                                "P0 Author Review", "p0-author-review"))))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andReturn();
        String categoryUuid = objectMapper.readTree(categoryResult.getResponse().getContentAsString())
                .path("data").path("uuid").asText();

        String title = "P0 Author Review Publish Contract";
        String summary = "Author review public visibility summary";
        Map<String, Object> articleBody = new LinkedHashMap<>();
        articleBody.put("title", title);
        articleBody.put("summary", summary);
        articleBody.put("content", "Content that should be searchable after admin publishes the pending article.");
        articleBody.put("categoryIds", List.of(categoryUuid));
        articleBody.put("tagNames", List.of("P0", "AuthorReview"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/articles")
                        .with(AuthHelper.bearerToken(authorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(articleBody)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.summary").value(summary))
                .andReturn();
        String articleUuid = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("uuid").asText();

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/submit")
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/publish")
                        .with(AuthHelper.bearerToken(adminToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        mockMvc.perform(get("/api/v1/articles")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.records[*].uuid", hasItem(articleUuid)))
                .andExpect(jsonPath("$.data.records[*].title", hasItem(title)))
                .andExpect(jsonPath("$.data.records[*].summary", hasItem(summary)));

        await().atMost(15, SECONDS).pollInterval(1, SECONDS).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/search")
                                .param("q", "Author Review Publish")
                                .param("page", "1")
                                .param("size", "10"))
                        .andExpect(status().isOk())
                        .andExpect(E2EAssertions.apiSuccess())
                        .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)))
                        .andExpect(jsonPath("$.data.records[*].articleUuid", hasItem(articleUuid)))
                        .andExpect(jsonPath("$.data.records[*].title", hasItem(title)))
        );
    }

    @Test
    @DisplayName("管理員駁回後作者應看得到原因且可重新送審")
    void rejectedArticleShouldExposeReasonToAuthorAndAllowResubmit() throws Exception {
        String adminToken = authHelper.createUserWithRole(
                "p0-author-reject-admin@test.local", "Admin123!", "p0rejectadmin", "P0 Reject Admin", Role.ADMIN);
        String authorToken = authHelper.createUserWithRole(
                "p0-author-reject-author@test.local", "Author123!", "p0rejectauthor", "P0 Reject Author", Role.AUTHOR);

        MvcResult createResult = mockMvc.perform(post("/api/v1/articles")
                        .with(AuthHelper.bearerToken(authorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DataBuilder.article(
                                "P0 Author Review Rejected Article",
                                "Content that needs editorial changes before publishing."))))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();
        String articleUuid = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("uuid").asText();

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/submit")
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        String rejectReason = "請補充實作細節與審核依據";
        Map<String, Object> rejectBody = new LinkedHashMap<>();
        rejectBody.put("reason", rejectReason);

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/reject")
                        .with(AuthHelper.bearerToken(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rejectBody)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectReason").value(rejectReason));

        mockMvc.perform(get("/api/v1/articles/me")
                        .with(AuthHelper.bearerToken(authorToken))
                        .param("status", "REJECTED")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].uuid").value(articleUuid))
                .andExpect(jsonPath("$.data.records[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.data.records[0].rejectReason").value(rejectReason));

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/submit")
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
    }
}
