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
import java.util.UUID;

import static dowob.xyz.blog.e2e.support.AuthHelper.bearerToken;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("P0 紅燈 - Reader 互動流程")
class P0ReaderInteractionRedE2E extends AbstractE2ETest {

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
    @DisplayName("Reader 可冪等按讚收藏並建立 top-level 與 reply 留言")
    void readerCanInteractWithPublishedArticleIdempotently() throws Exception {
        String adminToken = authHelper.createUserWithRole(
                "p0-reader-admin@test.local", "Admin123!", "p0readeradmin", "P0 Reader Admin", Role.ADMIN);
        String authorToken = authHelper.createUserWithRole(
                "p0-reader-author@test.local", "Author123!", "p0readerauthor", "P0 Reader Author", Role.AUTHOR);
        String readerToken = authHelper.createUserWithRole(
                "p0-reader-user@test.local", "Reader123!", "p0readeruser", "P0 Reader User", Role.USER);

        MvcResult categoryResult = mockMvc.perform(post("/api/v1/admin/categories")
                        .with(bearerToken(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DataBuilder.category(
                                "P0 Reader Interaction", "p0-reader-interaction"))))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andReturn();
        String categoryUuid = objectMapper.readTree(categoryResult.getResponse().getContentAsString())
                .path("data").path("uuid").asText();

        String title = "P0 Reader Interaction Contract " + UUID.randomUUID();
        Map<String, Object> articleBody = new LinkedHashMap<>();
        articleBody.put("title", title);
        articleBody.put("summary", "Reader like bookmark and comment contract summary.");
        articleBody.put("content", "Published content for reader interaction red E2E.");
        articleBody.put("categoryIds", List.of(categoryUuid));
        articleBody.put("tagNames", List.of("P0", "ReaderInteraction"));

        MvcResult createResult = mockMvc.perform(post("/api/v1/articles")
                        .with(bearerToken(authorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(articleBody)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();
        String articleUuid = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("uuid").asText();

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/submit")
                        .with(bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/publish")
                        .with(bearerToken(adminToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        await().atMost(15, SECONDS).pollInterval(1, SECONDS).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/search")
                                .param("q", title)
                                .param("page", "1")
                                .param("size", "10"))
                        .andExpect(status().isOk())
                        .andExpect(E2EAssertions.apiSuccess())
                        .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)))
                        .andExpect(jsonPath("$.data.records[*].articleUuid", hasItem(articleUuid)))
                        .andExpect(jsonPath("$.data.records[*].title", hasItem(title)))
        );

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/like")
                        .with(bearerToken(readerToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/like")
                        .with(bearerToken(readerToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        mockMvc.perform(get("/api/v1/articles/" + articleUuid)
                        .with(bearerToken(readerToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(1));

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/bookmark")
                        .with(bearerToken(readerToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/bookmark")
                        .with(bearerToken(readerToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        String topLevelContent = "Reader top-level comment";
        Map<String, Object> topLevelBody = new LinkedHashMap<>();
        topLevelBody.put("content", topLevelContent);

        MvcResult topLevelResult = mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/comments")
                        .with(bearerToken(readerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(topLevelBody)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.uuid").exists())
                .andReturn();
        String topLevelCommentUuid = objectMapper.readTree(topLevelResult.getResponse().getContentAsString())
                .path("data").path("uuid").asText();

        String replyContent = "Reader reply comment";
        Map<String, Object> replyBody = new LinkedHashMap<>();
        replyBody.put("content", replyContent);
        replyBody.put("parentUuid", topLevelCommentUuid);

        MvcResult replyResult = mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/comments")
                        .with(bearerToken(readerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replyBody)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.uuid").exists())
                .andReturn();
        String replyCommentUuid = objectMapper.readTree(replyResult.getResponse().getContentAsString())
                .path("data").path("uuid").asText();

        mockMvc.perform(get("/api/v1/users/me/bookmarks")
                        .with(bearerToken(readerToken))
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[*].uuid", hasItem(articleUuid)))
                .andExpect(jsonPath("$.data.records[*].title", hasItem(title)));

        mockMvc.perform(get("/api/v1/articles/" + articleUuid + "/comments")
                        .param("page", "1")
                        .param("size", "10")
                        .param("sort", "oldest"))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.totalCommentCount").value(2))
                .andExpect(jsonPath("$.data.topLevels.total").value(1))
                .andExpect(jsonPath("$.data.topLevels.records[0].uuid").value(topLevelCommentUuid))
                .andExpect(jsonPath("$.data.topLevels.records[0].content").value(topLevelContent))
                .andExpect(jsonPath("$.data.topLevels.records[0].replies.length()").value(1))
                .andExpect(jsonPath("$.data.topLevels.records[0].replies[0].uuid").value(replyCommentUuid))
                .andExpect(jsonPath("$.data.topLevels.records[0].replies[0].parentUuid").value(topLevelCommentUuid))
                .andExpect(jsonPath("$.data.topLevels.records[0].replies[0].content").value(replyContent));
    }

    @Test
    @DisplayName("Guest 對不存在文章按讚與收藏應回 401 且維持穩定錯誤 envelope")
    void guestCannotLikeOrBookmarkMissingArticle() throws Exception {
        String missingArticleUuid = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/articles/" + missingArticleUuid + "/like"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0005"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(post("/api/v1/articles/" + missingArticleUuid + "/bookmark"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0005"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
