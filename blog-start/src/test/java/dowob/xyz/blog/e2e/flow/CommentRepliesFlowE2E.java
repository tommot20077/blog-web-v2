package dowob.xyz.blog.e2e.flow;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dowob.xyz.blog.e2e.support.AuthHelper.bearerToken;
import static dowob.xyz.blog.e2e.support.E2EAssertions.apiSuccess;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 留言流程 E2E（完整應用程式、真實 PostgreSQL／Redis／RabbitMQ／ES）：
 * D3 回覆分頁（列表前 3 則 + replyCount、回覆分頁端點）與 B5 留言數口徑（totalCommentCount 與文章 commentCount）。
 */
@DisplayName("留言回覆分頁與留言數口徑 E2E")
class CommentRepliesFlowE2E extends AbstractE2ETest {

    @Autowired private AuthHelper authHelper;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private ElasticsearchOperations esOps;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
        var indexOps = esOps.indexOps(ArticleDocument.class);
        if (indexOps.exists()) {
            indexOps.delete();
            indexOps.createWithMapping();
        }
    }

    private String postComment(String token, String articleUuid, String content, String parentUuid) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        if (parentUuid != null) {
            body.put("parentUuid", parentUuid);
        }
        MvcResult result = mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/comments")
                        .with(bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("uuid").asText();
    }

    private String createPublishedArticle(String authorToken) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/articles")
                        .with(bearerToken(authorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DataBuilder.article("回覆分頁 E2E 文章", "內容"))))
                .andExpect(status().isOk())
                .andReturn();
        String uuid = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("uuid").asText();
        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish").with(bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());
        return uuid;
    }

    @Test
    @DisplayName("5 則回覆：列表只帶前 3 則且 replyCount=5；分頁端點匿名可讀；刪除後兩個留言數一致")
    void repliesPagingAndCountConsistency() throws Exception {
        String authorToken = authHelper.createUserWithRole(
                "replies-author@test.com", "Password1!", "repliesauthor", "RepliesAuthor", Role.AUTHOR);
        String readerToken = authHelper.createUserWithRole(
                "replies-reader@test.com", "Password1!", "repliesreader", "RepliesReader", Role.USER);
        String articleUuid = createPublishedArticle(authorToken);

        String top = postComment(readerToken, articleUuid, "頂層留言", null);
        List<String> replyUuids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            replyUuids.add(postComment(readerToken, articleUuid, "回覆 " + i, top));
        }

        // 列表：只帶前 3 則（最早的），replyCount 為 5（匿名）
        mockMvc.perform(get("/api/v1/articles/" + articleUuid + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCommentCount").value(6))
                .andExpect(jsonPath("$.data.topLevels.records[0].replyCount").value(5))
                .andExpect(jsonPath("$.data.topLevels.records[0].replies.length()").value(3))
                .andExpect(jsonPath("$.data.topLevels.records[0].replies[0].uuid").value(replyUuids.get(0)))
                .andExpect(jsonPath("$.data.topLevels.records[0].replies[2].uuid").value(replyUuids.get(2)));

        // 回覆分頁（匿名）：page=2,size=3 接續列表內嵌的前 3 則
        mockMvc.perform(get("/api/v1/comments/" + top + "/replies").param("page", "2").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.records[0].uuid").value(replyUuids.get(3)))
                .andExpect(jsonPath("$.data.records[1].uuid").value(replyUuids.get(4)));

        // 刪除一則回覆與（有回覆的）頂層：墓碑仍顯示，留言區總數與文章 commentCount 都是 4
        mockMvc.perform(delete("/api/v1/comments/" + replyUuids.get(0)).with(bearerToken(readerToken)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/comments/" + top).with(bearerToken(readerToken)))
                .andExpect(status().isOk());

        MvcResult list = mockMvc.perform(get("/api/v1/articles/" + articleUuid + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topLevels.records[0].deleted").value(true))
                .andExpect(jsonPath("$.data.topLevels.records[0].replyCount").value(4))
                .andReturn();
        int sectionTotal = objectMapper.readTree(list.getResponse().getContentAsString())
                .path("data").path("totalCommentCount").asInt();
        JsonNode article = objectMapper.readTree(mockMvc.perform(get("/api/v1/articles/" + articleUuid))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(sectionTotal).isEqualTo(4);
        assertThat(article.path("commentCount").asInt()).isEqualTo(sectionTotal);

        // 墓碑底下存活的回覆仍可分頁取得，已刪除的不在其中
        mockMvc.perform(get("/api/v1/comments/" + top + "/replies").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.records.length()").value(4));
    }

    @Test
    @DisplayName("草稿文章底下的回覆分頁：匿名得 200 空頁、作者本人讀得到")
    void draftArticleReplies_anonymousEmpty_authorOk() throws Exception {
        String authorToken = authHelper.createUserWithRole(
                "replies-draft@test.com", "Password1!", "repliesdraft", "RepliesDraft", Role.AUTHOR);
        MvcResult created = mockMvc.perform(post("/api/v1/articles")
                        .with(bearerToken(authorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DataBuilder.article("草稿文章", "內容"))))
                .andExpect(status().isOk())
                .andReturn();
        String draftUuid = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("uuid").asText();
        // 草稿無法經 API 留言（可讀才可互動，作者本人可以）
        String top = postComment(authorToken, draftUuid, "作者在草稿上的留言", null);

        postComment(authorToken, draftUuid, "草稿上的回覆", top);

        mockMvc.perform(get("/api/v1/comments/" + top + "/replies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records.length()").value(0));
        mockMvc.perform(get("/api/v1/comments/" + top + "/replies").with(bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }
}
